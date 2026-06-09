import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { PushSubscriptionStore } from "../src/core/push/subscriptions"
import { createPushSender } from "../src/core/push/sender"

let tmpDir: string
let store: PushSubscriptionStore

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-sender-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new PushSubscriptionStore(db)
  store.upsert({ device: "iphone", endpoint: "https://example.com/push/abc", keys: { p256dh: "pkey", auth: "secret" } })
})
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

test("sendToChat returns ok:false if no subscription", async () => {
  const sender = createPushSender({
    vapid: { publicKey: "pub", privateKey: "priv", subject: "mailto:test@example.com" },
    store,
    webpushAdapter: { sendNotification: async () => ({ statusCode: 201 }) },
  })
  const res = await sender.sendToChat("web:nobody", { session: "x", text: "hi", ts: "t" })
  expect(res.ok).toBe(false)
})

test("sendToChat calls webpush and marks used on success", async () => {
  const calls: any[] = []
  const sender = createPushSender({
    vapid: { publicKey: "pub", privateKey: "priv", subject: "mailto:test@example.com" },
    store,
    webpushAdapter: {
      sendNotification: async (sub: any, payload: string) => {
        calls.push({ sub, payload })
        return { statusCode: 201 }
      },
    },
  })
  const res = await sender.sendToChat("web:iphone", { session: "ana", text: "hi", ts: "t" })
  expect(res.ok).toBe(true)
  expect(calls.length).toBe(1)
  expect(calls[0].sub.endpoint).toBe("https://example.com/push/abc")
  expect(JSON.parse(calls[0].payload).session).toBe("ana")
  expect(store.get("iphone")?.lastUsedAt).not.toBeUndefined()
})

test("sendToChat removes the subscription on 410 Gone", async () => {
  const sender = createPushSender({
    vapid: { publicKey: "pub", privateKey: "priv", subject: "mailto:test@example.com" },
    store,
    webpushAdapter: {
      sendNotification: async () => {
        const err: any = new Error("Gone")
        err.statusCode = 410
        throw err
      },
    },
  })
  const res = await sender.sendToChat("web:iphone", { session: "ana", text: "hi", ts: "t" })
  expect(res.ok).toBe(false)
  if (!res.ok) expect(res.gone).toBe(true)
  expect(store.get("iphone")).toBeNull()
})

test("sendToChat removes the subscription on 404", async () => {
  const sender = createPushSender({
    vapid: { publicKey: "pub", privateKey: "priv", subject: "mailto:test@example.com" },
    store,
    webpushAdapter: {
      sendNotification: async () => {
        const err: any = new Error("Not Found")
        err.statusCode = 404
        throw err
      },
    },
  })
  const res = await sender.sendToChat("web:iphone", { session: "ana", text: "hi", ts: "t" })
  expect(res.ok).toBe(false)
  expect(store.get("iphone")).toBeNull()
})

test("sendToChat keeps the subscription on 500-class errors", async () => {
  const sender = createPushSender({
    vapid: { publicKey: "pub", privateKey: "priv", subject: "mailto:test@example.com" },
    store,
    webpushAdapter: {
      sendNotification: async () => {
        const err: any = new Error("Server down")
        err.statusCode = 503
        throw err
      },
    },
  })
  const res = await sender.sendToChat("web:iphone", { session: "ana", text: "hi", ts: "t" })
  expect(res.ok).toBe(false)
  if (!res.ok) expect(res.gone).toBe(false)
  expect(store.get("iphone")).not.toBeNull()
})
