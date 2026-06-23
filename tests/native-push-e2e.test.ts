// End-to-end native push pipeline through the REAL broker building blocks with a
// MOCK relay (no network): DevicePushTokenStore → firePushForReply suppression
// hook → relay-client seal (P-256 ECIES) → relay POST. Proves the reply path
// fans out to native devices, that the payload is encrypted (plaintext never
// leaves the broker), and that mute / presence suppress the native send too.
import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { DevicePushTokenStore } from "../src/core/push/device-tokens"
import { createRelayClient } from "../src/core/push/relay-adapter"
import { firePushForReply } from "../src/core/push/hook"
import type { OutboundAction } from "../src/channels/channel"

async function deviceKey() {
  const kp = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"])
  return Buffer.from(await crypto.subtle.exportKey("raw", kp.publicKey)).toString("base64url")
}

function freshStore() {
  const db = new Database(":memory:")
  db.run(
    `CREATE TABLE device_push_tokens (device TEXT PRIMARY KEY, platform TEXT NOT NULL, token TEXT NOT NULL, routing_token TEXT, device_pubkey TEXT, created_at TEXT NOT NULL, last_used_at TEXT)`,
  )
  return new DevicePushTokenStore(db)
}

const SECRET = "super-secret-reply-plaintext"
const replyAction: OutboundAction & { op: "reply" } = { op: "reply", chat_id: "web", text: SECRET }

async function run(opts: { isMuted: boolean; anyPresent: boolean }) {
  const store = freshStore()
  store.putNative("phone", "ios", "rt-1", await deviceKey())

  const posts: Array<{ url: string; body: any }> = []
  const nativeSender = createRelayClient({
    store,
    relayUrl: "https://relay.test",
    fetchImpl: async (url, init) => {
      posts.push({ url, body: JSON.parse((init as any).body) })
      return new Response('{"ok":true}')
    },
  })
  const nativeDevices = () => store.all().filter((r) => r.routing_token).map((r) => r.device)

  await firePushForReply({
    sender: { sendToChat: async () => ({ ok: true }), sendToDevice: async () => ({ ok: true }) },
    action: replyAction,
    sessionName: "sess",
    sessionId: "sid-1",
    isMuted: () => opts.isMuted,
    anyPresent: () => opts.anyPresent,
    devices: () => [], // web side empty — isolate the native path
    nativeSender,
    nativeDevices,
  })

  return posts
}

test("not muted / not present → one sealed POST to the relay, ciphertext hides the plaintext", async () => {
  const posts = await run({ isMuted: false, anyPresent: false })
  expect(posts).toHaveLength(1)
  expect(posts[0]!.url).toBe("https://relay.test/push")
  expect(posts[0]!.body.routingToken).toBe("rt-1")
  expect(typeof posts[0]!.body.ciphertext).toBe("string")
  // The seal is end-to-end: the reply's plaintext must not appear in the wire blob.
  expect(posts[0]!.body.ciphertext).not.toContain(SECRET)
})

test("muted → no native POST", async () => {
  expect(await run({ isMuted: true, anyPresent: false })).toHaveLength(0)
})

test("present on a device → no native POST", async () => {
  expect(await run({ isMuted: false, anyPresent: true })).toHaveLength(0)
})
