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
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-rev-cas-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new PushSubscriptionStore(db)
  port = 0
  const devicesFile = join(tmpDir, "devices.json")
  const ds = new DeviceStore(devicesFile)
  token = ds.mint("iphone").token
  channel = new WebChannel({
    port, devicesFile,
    publicUrl: `http://127.0.0.1:${port}`,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    pushStore: store,
    vapidPublicKey: "k",
  } as any)
  await channel.start()
  port = channel.boundPort
  store.upsert({ device: "iphone", endpoint: "ep", keys: { p256dh: "a", auth: "b" } })
})
afterEach(async () => {
  await channel.stop()
  rmSync(tmpDir, { recursive: true, force: true })
})

test("DELETE /devices/iphone cascades to push_subscriptions", async () => {
  expect(store.get("iphone")).not.toBeNull()
  const res = await fetch(`http://127.0.0.1:${port}/devices/iphone`, {
    method: "DELETE",
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(res.status === 200 || res.status === 204).toBe(true)
  expect(store.get("iphone")).toBeNull()
})
