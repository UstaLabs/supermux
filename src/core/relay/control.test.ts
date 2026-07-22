import { expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { loadOrCreateHostKey } from "../host-identity"
import type { AuthRejectionEvent } from "./auth-plugin"
import { createRelayControl, FileKnownHostRegistry } from "./control"

const SECRET = "test-relay-secret-at-least-32-bytes"

async function signedLeaseRequest(control: ReturnType<typeof createRelayControl>, keyPath: string) {
  const identity = loadOrCreateHostKey(keyPath)
  const nonceResponse = await control.handle(new Request("http://relay/relay/nonce"), "203.0.113.1")
  const { nonce } = await nonceResponse.json() as { nonce: string }
  const body = {
    hostId: identity.hostId,
    publicKey: identity.publicKeyRaw.toString("base64url"),
    nonce,
    signature: identity.sign(Buffer.from(nonce)).toString("base64url"),
  }
  return { identity, nonce, body, response: await control.handle(new Request("http://relay/relay/lease", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  }), "203.0.113.1") }
}

test("a host proves its key and receives a lease bound to its hostId", async () => {
  let now = 1_000
  const known = new Map<string, string>()
  const control = createRelayControl({ secret: SECRET, domain: "relay.supermux.dev", knownHosts: known, now: () => now })
  const keyPath = join(mkdtempSync(join(tmpdir(), "mux-relay-key-")), "host-key")

  const { identity, response } = await signedLeaseRequest(control, keyPath)
  expect(response.status).toBe(200)
  const result = await response.json() as { lease: string; expiresAt: number }
  expect(result.lease.startsWith(`${identity.hostId}.`)).toBe(true)
  expect(result.expiresAt).toBe(now + 24 * 60 * 60 * 1000)
  expect(known.has(identity.hostId)).toBe(true)
})

test("a nonce is one-time even when the signed request is replayed", async () => {
  const control = createRelayControl({ secret: SECRET, domain: "relay.supermux.dev" })
  const keyPath = join(mkdtempSync(join(tmpdir(), "mux-relay-key-")), "host-key")
  const first = await signedLeaseRequest(control, keyPath)
  expect(first.response.status).toBe(200)

  const replay = await control.handle(new Request("http://relay/relay/lease", {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(first.body),
  }), "203.0.113.1")
  expect(replay.status).toBe(401)
})

test("hostId must be derived from the submitted public key", async () => {
  const control = createRelayControl({ secret: SECRET, domain: "relay.supermux.dev" })
  const keyPath = join(mkdtempSync(join(tmpdir(), "mux-relay-key-")), "host-key")
  const identity = loadOrCreateHostKey(keyPath)
  const nonceResponse = await control.handle(new Request("http://relay/relay/nonce"), "203.0.113.2")
  const { nonce } = await nonceResponse.json() as { nonce: string }
  const response = await control.handle(new Request("http://relay/relay/lease", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      hostId: "aaaaaaaaaaaaaaaaaaaaaaaaaa",
      publicKey: identity.publicKeyRaw.toString("base64url"),
      nonce,
      signature: identity.sign(Buffer.from(nonce)).toString("base64url"),
    }),
  }), "203.0.113.2")
  expect(response.status).toBe(401)
})

test("Caddy may issue only the control name or certificates for registered hosts", async () => {
  const known = new Map<string, string>()
  const control = createRelayControl({ secret: SECRET, domain: "relay.supermux.dev", knownHosts: known })
  expect((await control.handle(new Request("http://relay/relay/caddy-ask?domain=control.relay.supermux.dev"))).status).toBe(200)
  expect((await control.handle(new Request("http://relay/relay/caddy-ask?domain=h-aaaaaaaaaaaaaaaaaaaaaaaaaa.relay.supermux.dev"))).status).toBe(403)

  const keyPath = join(mkdtempSync(join(tmpdir(), "mux-relay-key-")), "host-key")
  const { identity, response } = await signedLeaseRequest(control, keyPath)
  expect(response.status).toBe(200)
  expect((await control.handle(new Request(`http://relay/relay/caddy-ask?domain=h-${identity.hostId}.relay.supermux.dev`))).status).toBe(200)
})

test("frps Login and NewProxy hooks are served by the control handler", async () => {
  let now = 10_000
  const control = createRelayControl({ secret: SECRET, domain: "relay.supermux.dev", now: () => now })
  const keyPath = join(mkdtempSync(join(tmpdir(), "mux-relay-key-")), "host-key")
  const { identity, response } = await signedLeaseRequest(control, keyPath)
  const { lease } = await response.json() as { lease: string }

  const login = await control.handle(new Request("http://relay/handler", {
    method: "POST", body: JSON.stringify({ op: "Login", content: { metas: { lease } } }),
  }))
  expect(await login.json()).toEqual({ reject: false, unchange: true })

  const wrongSubdomain = await control.handle(new Request("http://relay/handler", {
    method: "POST", body: JSON.stringify({
      op: "NewProxy",
      content: { user: { metas: { lease } }, proxy_type: "http", subdomain: "h-aaaaaaaaaaaaaaaaaaaaaaaaaa" },
    }),
  }))
  expect((await wrongSubdomain.json() as { reject: boolean }).reject).toBe(true)
})

test("the control handler forwards structured auth rejection events", async () => {
  let now = 1_000
  const events: AuthRejectionEvent[] = []
  const control = createRelayControl({
    secret: SECRET,
    domain: "relay.supermux.dev",
    leaseTtlMs: 5_000,
    now: () => now,
    onAuthRejected: event => events.push(event),
  })
  const keyPath = join(mkdtempSync(join(tmpdir(), "mux-relay-key-")), "host-key")
  const first = await signedLeaseRequest(control, keyPath)
  const { lease: expiredLease } = await first.response.json() as { lease: string }
  now = 7_000

  const login = await control.handle(new Request("http://relay/handler", {
    method: "POST",
    body: JSON.stringify({
      op: "Login",
      content: { metas: { lease: expiredLease }, client_address: "203.0.113.9:1234" },
    }),
  }))

  expect(await login.json()).toEqual({ reject: true, reject_reason: "invalid or missing lease" })
  expect(events).toEqual([{
    operation: "Login",
    reason: "expired_lease",
    hostId: first.identity.hostId,
    leaseExpiresAt: 6_000,
    expiredByMs: 1_000,
    clientAddress: "203.0.113.9:1234",
  }])

  const second = await signedLeaseRequest(control, keyPath)
  const { lease: validLease } = await second.response.json() as { lease: string }
  const wrongSubdomain = await control.handle(new Request("http://relay/handler", {
    method: "POST",
    body: JSON.stringify({
      op: "NewProxy",
      content: { user: { metas: { lease: validLease } }, proxy_type: "http", subdomain: "h-wrong" },
    }),
  }))

  expect(await wrongSubdomain.json()).toEqual({
    reject: true,
    reject_reason: "subdomain does not match leased hostId",
  })
  expect(events[1]).toEqual({
    operation: "NewProxy",
    reason: "subdomain_mismatch",
    hostId: second.identity.hostId,
    subdomain: "h-wrong",
  })
  expect(JSON.stringify(events)).not.toContain(expiredLease)
  expect(JSON.stringify(events)).not.toContain(validLease)
})

test("known host registry persists verified keys across restarts", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-relay-registry-"))
  const path = join(dir, "hosts.json")
  const first = new FileKnownHostRegistry(path)
  expect(first.register("aaaaaaaaaaaaaaaaaaaaaaaaaa", "key-one")).toBe("registered")
  expect(new FileKnownHostRegistry(path).get("aaaaaaaaaaaaaaaaaaaaaaaaaa")).toBe("key-one")
  expect(first.register("aaaaaaaaaaaaaaaaaaaaaaaaaa", "different-key")).toBe("mismatch")
})
