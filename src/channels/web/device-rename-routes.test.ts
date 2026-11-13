// #97: PATCH /devices/:name renames a device. The name keys its push rows and its uploads, so they move with it.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel } from "./index"
import { DeviceStore } from "./device-store"
import { FileStore } from "../../core/files/store"
import { PushSubscriptionStore } from "../../core/push/subscriptions"
import { DevicePushTokenStore } from "../../core/push/device-tokens"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function start() {
  const dir = mkdtempSync(join(tmpdir(), "mux-device-rename-"))
  const devicesFile = join(dir, "devices.json")
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const files = new FileStore(db, join(dir, "files"))
  const push = new PushSubscriptionStore(db)
  const native = new DevicePushTokenStore(db as any)
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    fileStore: files, pushStore: push, deviceTokenStore: native, updateChecker: null,
  })
  await channel.start()
  const base = `http://127.0.0.1:${channel.boundPort}`
  const devices = new DeviceStore(devicesFile)
  const laptop = devices.mint("laptop").token
  const phone = devices.mint("phone").token
  const rename = (name: string, body: unknown, token: string | null = laptop) =>
    fetch(`${base}/devices/${encodeURIComponent(name)}`, {
      method: "PATCH",
      headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), "content-type": "application/json", origin: "http://localhost" },
      body: JSON.stringify(body),
    })
  return { base, devices, laptop, phone, rename, files, push, native }
}

test("renames a device and answers the device", async () => {
  const { rename, devices, phone } = await start()
  const r = await rename("phone", { name: "  Pixel 9  " })
  expect(r.status).toBe(200)
  const body = await r.json() as Record<string, unknown>
  expect(body).toEqual({ name: "Pixel 9", created_at: expect.any(String), last_seen_at: null, current: false })
  expect(devices.verify(phone)?.name).toBe("Pixel 9")
})

test("asks for a signed-in device", async () => {
  const { rename } = await start()
  expect((await rename("phone", { name: "Pixel" }, null)).status).toBe(401)
})

test("refuses another device's name with 409 and says why", async () => {
  const { rename, devices } = await start()
  const r = await rename("phone", { name: "laptop" })
  expect(r.status).toBe(409)
  expect(await r.json()).toEqual({ error: "Another device already has that name.", code: "name_taken" })
  expect(devices.list().map((d) => d.name).sort()).toEqual(["laptop", "phone"])
})

test("an unknown or revoked device is 404", async () => {
  const { rename, devices } = await start()
  expect((await rename("ghost", { name: "x" })).status).toBe(404)
  devices.revoke("phone")
  expect((await rename("phone", { name: "x" })).status).toBe(404)
})

test("an empty, missing, or too long name is 400", async () => {
  const { rename } = await start()
  expect((await rename("phone", { name: "   " })).status).toBe(400)
  expect((await rename("phone", {})).status).toBe(400)
  expect((await rename("phone", { name: 7 })).status).toBe(400)
  const long = await rename("phone", { name: "x".repeat(41) })
  expect(long.status).toBe(400)
  expect(await long.json()).toEqual({ error: "Use a name of 40 characters or fewer.", code: "name_invalid" })
})

test("the same name is a no-op that answers 200", async () => {
  const { rename, devices } = await start()
  const r = await rename("phone", { name: "phone" })
  expect(r.status).toBe(200)
  expect((await r.json() as { name: string }).name).toBe("phone")
  expect(devices.list().map((d) => d.name).sort()).toEqual(["laptop", "phone"])
})

test("a device can rename itself: it stays signed in under the new name and is still current", async () => {
  const { base, rename, laptop } = await start()
  const r = await rename("laptop", { name: "Work laptop" })
  expect(r.status).toBe(200)
  expect((await r.json() as { current: boolean }).current).toBe(true)
  const me = await fetch(`${base}/me`, { headers: { authorization: `Bearer ${laptop}` } })
  expect((await me.json() as { device: string }).device).toBe("Work laptop")
})

test("the device's push rows and uploads move to the new name", async () => {
  const { rename, push, native, files } = await start()
  push.upsert({ device: "phone", endpoint: "ep", keys: { p256dh: "a", auth: "b" } })
  native.putNative("phone", "android", "rt", "pk")
  const { file_id } = await files.put({ kind: "photo", mime: "image/png", name: "a.png", session: "s", origin: "web-upload", device: "phone", bytes: new Uint8Array([1, 2, 3]) })
  expect((await rename("phone", { name: "Pixel" })).status).toBe(200)
  expect(push.all().map((r) => r.device)).toEqual(["Pixel"])
  expect(native.all().map((r) => [r.device, r.routing_token])).toEqual([["Pixel", "rt"]])
  expect(await files.resolveOwnedWebUpload(file_id, "Pixel")).not.toBeNull()
  expect(await files.resolveOwnedWebUpload(file_id, "phone")).toBeNull()
})
