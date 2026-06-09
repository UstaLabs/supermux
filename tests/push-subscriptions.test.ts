import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { PushSubscriptionStore } from "../src/core/push/subscriptions"

let tmpDir: string
let store: PushSubscriptionStore

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-pushsubs-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new PushSubscriptionStore(db)
})
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

test("upsert + get round-trip", () => {
  store.upsert({
    device: "iphone",
    endpoint: "https://fcm.googleapis.com/fcm/send/foo",
    keys: { p256dh: "pubkey", auth: "secret" },
    userAgent: "Mozilla/5.0",
  })
  const got = store.get("iphone")
  expect(got).not.toBeNull()
  expect(got?.endpoint).toBe("https://fcm.googleapis.com/fcm/send/foo")
  expect(got?.keys.p256dh).toBe("pubkey")
  expect(got?.keys.auth).toBe("secret")
})

test("upsert replaces existing row", () => {
  store.upsert({ device: "iphone", endpoint: "ep1", keys: { p256dh: "a", auth: "b" } })
  store.upsert({ device: "iphone", endpoint: "ep2", keys: { p256dh: "c", auth: "d" } })
  expect(store.get("iphone")?.endpoint).toBe("ep2")
})

test("remove returns true when row existed", () => {
  store.upsert({ device: "iphone", endpoint: "ep", keys: { p256dh: "a", auth: "b" } })
  expect(store.remove("iphone")).toBe(true)
  expect(store.get("iphone")).toBeNull()
})

test("remove returns false when row missing", () => {
  expect(store.remove("nonexistent")).toBe(false)
})

test("forChatId resolves web:<device>", () => {
  store.upsert({ device: "iphone", endpoint: "ep", keys: { p256dh: "a", auth: "b" } })
  expect(store.forChatId("web:iphone")?.endpoint).toBe("ep")
  expect(store.forChatId("web:laptop")).toBeNull()
  expect(store.forChatId("telegram:1234")).toBeNull()
})

test("markUsed updates last_used_at", () => {
  store.upsert({ device: "iphone", endpoint: "ep", keys: { p256dh: "a", auth: "b" } })
  store.markUsed("iphone")
  const got = store.get("iphone")
  expect(got?.lastUsedAt).not.toBeUndefined()
})
