import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { DevicePushTokenStore } from "./device-tokens"
import { createRelayClient } from "./relay-adapter"

async function deviceKey() {
  const kp = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"])
  return Buffer.from(await crypto.subtle.exportKey("raw", kp.publicKey)).toString("base64url")
}
function store() {
  const db = new Database(":memory:")
  db.run(`CREATE TABLE device_push_tokens (device TEXT PRIMARY KEY, platform TEXT NOT NULL, token TEXT NOT NULL, routing_token TEXT, device_pubkey TEXT, created_at TEXT NOT NULL, last_used_at TEXT)`)
  return new DevicePushTokenStore(db)
}

test("seals the payload and POSTs {routingToken, ciphertext} to the relay", async () => {
  const s = store(); s.putNative("phone", "ios", "rt-1", await deviceKey())
  let captured: any
  const client = createRelayClient({ store: s, relayUrl: "https://relay.test", fetchImpl: async (_u, init) => { captured = JSON.parse((init as any).body); return new Response('{"ok":true}') } })
  // A long, distinctive plaintext on purpose. The original asserted the
  // ciphertext did not contain "hi" — but ciphertext is base64, and a 2-char
  // needle turns up in a ~200-char base64 string roughly 5% of the time by pure
  // chance, so the test failed at random with nothing wrong.
  const secret = "plaintext-must-not-appear-in-ciphertext"
  const r = await client.sendToDevice("phone", { session: "s", text: secret, ts: "t" })
  expect(r).toEqual({ ok: true })
  expect(captured.routingToken).toBe("rt-1")
  expect(typeof captured.ciphertext).toBe("string")
  expect(captured.ciphertext).not.toContain(secret)
})

test("relay 'gone' prunes the device row", async () => {
  const s = store(); s.putNative("phone", "ios", "rt-1", await deviceKey())
  const client = createRelayClient({ store: s, relayUrl: "https://relay.test", fetchImpl: async () => new Response('{"ok":false,"gone":true}') })
  expect(await client.sendToDevice("phone", { session: "s", ts: "t" })).toEqual({ ok: false, gone: true })
  expect(s.get("phone")).toBeNull()
})

test("a device with no routing token is reported gone (nothing to send to)", async () => {
  const s = store()
  const client = createRelayClient({ store: s, relayUrl: "https://relay.test", fetchImpl: async () => new Response('{"ok":true}') })
  expect(await client.sendToDevice("missing", { session: "s", ts: "t" })).toEqual({ ok: false, gone: true })
})
