import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { Database } from "bun:sqlite"
import { DevicePushTokenStore } from "../src/core/push/device-tokens"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

let tmpDir: string
let port: number
let channel: WebChannel
let token: string
let deviceStore: DevicePushTokenStore

beforeEach(async () => {
  __resetAuthFailures()
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-push-dev-ep-"))

  const db = new Database(":memory:")
  db.run(`CREATE TABLE device_push_tokens (device TEXT PRIMARY KEY, platform TEXT NOT NULL, token TEXT NOT NULL, routing_token TEXT, device_pubkey TEXT, created_at TEXT NOT NULL, last_used_at TEXT)`)
  deviceStore = new DevicePushTokenStore(db)

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
    deviceTokenStore: deviceStore,
  } as any)
  await channel.start()
  port = channel.boundPort
})

afterEach(async () => {
  await channel.stop()
  rmSync(tmpDir, { recursive: true, force: true })
})

test("POST /push/device stores the native token for the bearer device", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/push/device`, {
    method: "POST",
    headers: { Cookie: `cmux_token=${token}`, "content-type": "application/json" },
    body: JSON.stringify({ platform: "ios", routingToken: "rt-1", pubkey: "PUB" }),
  })
  expect(res.status).toBe(200)
  const row = deviceStore.get("iphone")
  expect(row?.routing_token).toBe("rt-1")
  expect(row?.device_pubkey).toBe("PUB")
})

test("POST /push/device 401 without bearer", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/push/device`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ platform: "ios", routingToken: "rt-1", pubkey: "PUB" }),
  })
  expect(res.status).toBe(401)
})

test("POST /push/device 400 on missing routingToken", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/push/device`, {
    method: "POST",
    headers: { Cookie: `cmux_token=${token}`, "content-type": "application/json" },
    body: JSON.stringify({ platform: "ios", pubkey: "PUB" }),
  })
  expect(res.status).toBe(400)
})

test("DELETE /push/device removes the native token", async () => {
  deviceStore.putNative("iphone", "ios", "rt-1", "PUB")
  const res = await fetch(`http://127.0.0.1:${port}/push/device`, {
    method: "DELETE",
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(res.status).toBe(200)
  expect(deviceStore.get("iphone")).toBeNull()
})
