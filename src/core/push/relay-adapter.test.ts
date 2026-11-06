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

function recorder() {
  const lines: { level: string; msg: string; extra?: Record<string, unknown> }[] = []
  const at = (level: string) => (msg: string, extra?: Record<string, unknown>) => { lines.push({ level, msg, extra }) }
  const log = { debug: at("debug"), info: at("info"), warn: at("warn"), error: at("error") }
  return { log, lines }
}

// Card #86: every native push result is logged, so "sent" can be told from
// "never sent" without asking the phone.
test("logs a push the relay accepted", async () => {
  const s = store(); s.putNative("phone", "android", "rt-1", await deviceKey())
  const { log, lines } = recorder()
  const client = createRelayClient({ store: s, relayUrl: "https://relay.test", log, fetchImpl: async () => new Response('{"ok":true}') })
  await client.sendToDevice("phone", { session: "s", ts: "t" })
  expect(lines).toEqual([{ level: "info", msg: "push_result", extra: { device: "phone", transport: "relay", platform: "android", ok: true } }])
})

// The July outage: a relay host answering 404 made res.json() throw, and the
// bare catch dropped every push without a word.
test("a relay answering a non-2xx status is a logged failure", async () => {
  const s = store(); s.putNative("phone", "ios", "rt-1", await deviceKey())
  const { log, lines } = recorder()
  const client = createRelayClient({ store: s, relayUrl: "https://relay.test", log, fetchImpl: async () => new Response("not found", { status: 404 }) })
  expect(await client.sendToDevice("phone", { session: "s", ts: "t" })).toEqual({ ok: false, gone: false })
  expect(s.get("phone")).not.toBeNull()
  expect(lines).toEqual([{ level: "warn", msg: "push_result", extra: { device: "phone", transport: "relay", platform: "ios", ok: false, status: 404, reason: "http_404" } }])
})

test("a network failure is logged", async () => {
  const s = store(); s.putNative("phone", "ios", "rt-1", await deviceKey())
  const { log, lines } = recorder()
  const client = createRelayClient({ store: s, relayUrl: "https://relay.test", log, fetchImpl: async () => { throw new Error("getaddrinfo EAI_AGAIN") } })
  expect(await client.sendToDevice("phone", { session: "s", ts: "t" })).toEqual({ ok: false, gone: false })
  expect(lines).toEqual([{ level: "warn", msg: "push_result", extra: { device: "phone", transport: "relay", platform: "ios", ok: false, reason: "network", err: "getaddrinfo EAI_AGAIN" } }])
})

test("the relay's own failure reason is logged", async () => {
  const s = store(); s.putNative("phone", "ios", "rt-1", await deviceKey())
  const { log, lines } = recorder()
  const client = createRelayClient({ store: s, relayUrl: "https://relay.test", log, fetchImpl: async () => new Response('{"ok":false,"gone":false,"reason":"apns_403_InvalidProviderToken"}') })
  await client.sendToDevice("phone", { session: "s", ts: "t" })
  expect(lines).toEqual([{ level: "warn", msg: "push_result", extra: { device: "phone", transport: "relay", platform: "ios", ok: false, status: 200, reason: "apns_403_InvalidProviderToken" } }])
})

test("a target the relay calls gone is removed, and the removal is logged", async () => {
  const s = store(); s.putNative("phone", "ios", "rt-1", await deviceKey())
  const { log, lines } = recorder()
  const client = createRelayClient({ store: s, relayUrl: "https://relay.test", log, fetchImpl: async () => new Response('{"ok":false,"gone":true,"reason":"apns_400_BadDeviceToken"}') })
  await client.sendToDevice("phone", { session: "s", ts: "t" })
  expect(s.get("phone")).toBeNull()
  expect(lines).toEqual([{ level: "warn", msg: "push_target_removed", extra: { device: "phone", transport: "relay", platform: "ios", reason: "apns_400_BadDeviceToken" } }])
})

test("a device with no routing token is logged as not registered", async () => {
  const s = store()
  const { log, lines } = recorder()
  const client = createRelayClient({ store: s, relayUrl: "https://relay.test", log, fetchImpl: async () => new Response('{"ok":true}') })
  await client.sendToDevice("missing", { session: "s", ts: "t" })
  expect(lines).toEqual([{ level: "warn", msg: "push_result", extra: { device: "missing", transport: "relay", ok: false, reason: "not_registered" } }])
})
