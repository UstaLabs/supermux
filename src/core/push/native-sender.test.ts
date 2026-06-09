import { expect, test } from "bun:test"
import { createNativePushSender } from "./native-sender"
import { DevicePushTokenStore } from "./device-tokens"
import { Database } from "bun:sqlite"

function storeWith(rows: Array<[string, "ios" | "android", string]>): DevicePushTokenStore {
  const db = new Database(":memory:")
  db.run(`CREATE TABLE device_push_tokens (device TEXT PRIMARY KEY, platform TEXT NOT NULL, token TEXT NOT NULL, created_at TEXT NOT NULL, last_used_at TEXT)`)
  const s = new DevicePushTokenStore(db)
  for (const [d, p, t] of rows) s.put(d, p, t)
  return s
}

test("routes each device to the sender for its platform", async () => {
  const calls: string[] = []
  const sender = createNativePushSender({
    store: storeWith([["a", "ios", "atok"], ["b", "android", "btok"]]),
    apns: { send: (tok, p) => { calls.push(`apns:${tok}`); return Promise.resolve({ ok: true }) } },
    fcm: { send: (tok, p) => { calls.push(`fcm:${tok}`); return Promise.resolve({ ok: true }) } },
  })
  await sender.sendToDevice("a", { session: "s", ts: "t" })
  await sender.sendToDevice("b", { session: "s", ts: "t" })
  expect(calls).toEqual(["apns:atok", "fcm:btok"])
})

test("returns gone:true when the platform sender reports the token is dead", async () => {
  const store = storeWith([["a", "ios", "atok"]])
  const sender = createNativePushSender({
    store,
    apns: { send: () => Promise.resolve({ ok: false, gone: true }) },
    fcm: { send: () => Promise.resolve({ ok: true }) },
  })
  expect(await sender.sendToDevice("a", { session: "s", ts: "t" })).toEqual({ ok: false, gone: true })
  expect(store.get("a")).toBeNull() // dead token was pruned
})
