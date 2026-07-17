import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { ClaimStore } from "./pair-claim"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })
function base(): string { return `http://127.0.0.1:${channel!.boundPort}` }

function makeChannel(opts: Partial<WebChannelOpts>): { channel: WebChannel; devicesFile: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-host-wiring-"))
  const devicesFile = join(dir, "devices.json")
  const full: WebChannelOpts = {
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    ...opts,
  }
  return { channel: new WebChannel(full), devicesFile }
}

test("GET /host is public and identity-only without auth", async () => {
  const made = makeChannel({
    getHostInfo: () => ({ hostId: "h123", name: "box", platform: "linux", version: "0.11.0", protocolVersion: 1 }),
  })
  channel = made.channel; await channel.start()
  const res = await fetch(`${base()}/host`)
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ hostId: "h123", name: "box", protocolVersion: 1 })
})

test("POST /pair/claim mints a device token for a valid one-time secret, even with devices present", async () => {
  const claimStore = new ClaimStore({ clock: () => 0 })
  const secret = claimStore.mint()
  const made = makeChannel({
    claimStore,
    getAppConfig: () => ({ onboarded: true } as never),
    getHostInfo: () => ({ hostId: "h123", name: "box", platform: "linux", version: "0.11.0", protocolVersion: 1 }),
  })
  channel = made.channel; await channel.start()
  new DeviceStore(made.devicesFile).mint("existing-device")

  const res = await fetch(`${base()}/pair/claim`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ claimSecret: secret, deviceName: "phone" }),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as { host: unknown; deviceToken: unknown }
  expect(body.host).toEqual({ hostId: "h123", name: "box", platform: "linux", version: "0.11.0" })
  expect(typeof body.deviceToken).toBe("string")
})

test("fresh broker WITH a claimStore still bootstraps the first device secretlessly (Codex-found regression)", async () => {
  // Production always constructs a ClaimStore; the secretless trust-on-first-connect
  // path must still work for the very first device or fresh onboarding deadlocks.
  const claimStore = new ClaimStore({ clock: () => 0 })
  const made = makeChannel({ claimStore, getHostInfo: () => ({ hostId: "h", name: "b", platform: "linux", version: "0", protocolVersion: 1 }) })
  channel = made.channel; await channel.start()
  const res = await fetch(`${base()}/pair/claim`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ deviceName: "first" }),
  })
  expect(res.status).toBe(200)
  expect((await res.json() as { paired: boolean }).paired).toBe(true)
  // second secretless attempt is now refused (broker has a device)
  const again = await fetch(`${base()}/pair/claim`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ deviceName: "second" }),
  })
  expect(again.status).toBe(403)
})

test("POST /pair/mint-claim requires auth and returns a fresh claimSecret", async () => {
  const claimStore = new ClaimStore({ clock: () => 0 })
  const made = makeChannel({ claimStore, getHostInfo: () => ({ hostId: "h", name: "b", platform: "linux", version: "0", protocolVersion: 1 }) })
  channel = made.channel; await channel.start()
  expect((await fetch(`${base()}/pair/mint-claim`, { method: "POST" })).status).toBe(401)
  const token = new DeviceStore(made.devicesFile).mint("dev").token
  const res = await fetch(`${base()}/pair/mint-claim`, { method: "POST", headers: { authorization: `Bearer ${token}` } })
  expect(res.status).toBe(200)
  const { claimSecret, expiresAt } = await res.json() as { claimSecret: string; expiresAt: string }
  expect(typeof claimSecret).toBe("string")
  expect(expiresAt).toBe("1970-01-01T00:10:00.000Z")
  const claimed = await fetch(`${base()}/pair/claim`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ claimSecret, deviceName: "phone2" }),
  })
  expect(claimed.status).toBe(200)
})

test("POST /devices publishes the live relay URL instead of localhost", async () => {
  const made = makeChannel({
    publicUrl: "http://127.0.0.1:9898",
    getRelayUrl: () => "https://h-live.relay.supermux.dev",
  })
  channel = made.channel; await channel.start()
  const token = new DeviceStore(made.devicesFile).mint("admin").token

  const res = await fetch(`${base()}/devices`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ name: "phone" }),
  })

  expect(res.status).toBe(200)
  const body = await res.json() as { url: string; name: string }
  expect(body.name).toBe("phone")
  expect(body.url).toStartWith("https://h-live.relay.supermux.dev/pair?t=")
})

test("cookie-authenticated mutation accepts the live relay origin but rejects unrelated origins", async () => {
  const relayUrl = "https://h-live.relay.supermux.dev"
  const made = makeChannel({
    publicUrl: "http://localhost:8787",
    getRelayUrl: () => relayUrl,
  })
  channel = made.channel; await channel.start()
  const token = new DeviceStore(made.devicesFile).mint("admin").token

  const postDevice = (origin: string, name: string) => fetch(`${base()}/devices`, {
    method: "POST",
    headers: {
      Cookie: `cmux_token=${token}`,
      Origin: origin,
      "content-type": "application/json",
    },
    body: JSON.stringify({ name }),
  })

  expect((await postDevice(relayUrl, "relay-device")).status).toBe(200)
  expect((await postDevice("https://evil.example", "evil-device")).status).toBe(403)
})

test("cookie-authenticated mutation does not trust the push relay while connectivity relay is offline", async () => {
  const made = makeChannel({
    publicUrl: "http://localhost:8787",
    relayUrl: "https://push.supermux.dev",
    getRelayUrl: () => undefined,
  })
  channel = made.channel; await channel.start()
  const token = new DeviceStore(made.devicesFile).mint("admin").token

  const res = await fetch(`${base()}/devices`, {
    method: "POST",
    headers: {
      Cookie: `cmux_token=${token}`,
      Origin: "https://push.supermux.dev",
      "content-type": "application/json",
    },
    body: JSON.stringify({ name: "push-relay-device" }),
  })

  expect(res.status).toBe(403)
})

test("POST /pair/claim rejects a reused secret", async () => {
  const claimStore = new ClaimStore({ clock: () => 0 })
  const secret = claimStore.mint()
  const made = makeChannel({ claimStore, getHostInfo: () => ({ hostId: "h", name: "b", platform: "linux", version: "0", protocolVersion: 1 }) })
  channel = made.channel; await channel.start()
  const once = { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ claimSecret: secret, deviceName: "p" }) }
  expect((await fetch(`${base()}/pair/claim`, once)).status).toBe(200)
  expect((await fetch(`${base()}/pair/claim`, once)).status).toBe(401)
})
