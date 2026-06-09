import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { PushSubscriptionStore } from "../src/core/push/subscriptions"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

let tmpDir: string
let port: number
let channel: WebChannel
let token: string
let store: PushSubscriptionStore

beforeEach(async () => {
  __resetAuthFailures()
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-push-ep-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new PushSubscriptionStore(db)

  const devicesFile = join(tmpDir, "devices.json")
  const ds = new DeviceStore(devicesFile)
  token = ds.mint("iphone").token

  channel = new WebChannel({
    port: 0,
    devicesFile,
    publicUrl: "http://127.0.0.1",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    pushStore: store,
    vapidPublicKey: "TEST-PUBKEY-VALUE",
  } as any)
  await channel.start()
  port = channel.boundPort
})
afterEach(async () => {
  await channel.stop()
  rmSync(tmpDir, { recursive: true, force: true })
})

test("GET /push/vapid-public-key returns the public key without auth", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/push/vapid-public-key`)
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.publicKey).toBe("TEST-PUBKEY-VALUE")
})

test("POST /push/subscribe stores the subscription for the bearer device", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/push/subscribe`, {
    method: "POST",
    headers: { Cookie: `cmux_token=${token}`, "content-type": "application/json" },
    body: JSON.stringify({ endpoint: "https://example.com/push/abc", keys: { p256dh: "pkey", auth: "secret" } }),
  })
  expect(res.status).toBe(200)
  const got = store.get("iphone")
  expect(got?.endpoint).toBe("https://example.com/push/abc")
  expect(got?.keys.p256dh).toBe("pkey")
  expect(got?.keys.auth).toBe("secret")
})

test("POST /push/subscribe 401 without bearer", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/push/subscribe`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ endpoint: "ep", keys: { p256dh: "a", auth: "b" } }),
  })
  expect(res.status).toBe(401)
})

test("POST /push/subscribe 400 on missing fields", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/push/subscribe`, {
    method: "POST",
    headers: { Cookie: `cmux_token=${token}`, "content-type": "application/json" },
    body: JSON.stringify({ endpoint: "ep" }),  // no keys
  })
  expect(res.status).toBe(400)
})

test("DELETE /push/subscribe removes the subscription", async () => {
  store.upsert({ device: "iphone", endpoint: "ep", keys: { p256dh: "a", auth: "b" } })
  const res = await fetch(`http://127.0.0.1:${port}/push/subscribe`, {
    method: "DELETE",
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(res.status).toBe(200)
  expect(store.get("iphone")).toBeNull()
})

test("DELETE /push/subscribe is idempotent on missing row", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/push/subscribe`, {
    method: "DELETE",
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(res.status).toBe(200)
})
