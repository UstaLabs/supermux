import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { DevicePushTokenStore } from "./device-tokens"

function freshDb(): Database {
  const db = new Database(":memory:")
  db.run(`CREATE TABLE device_push_tokens (
    device TEXT PRIMARY KEY, platform TEXT NOT NULL, token TEXT NOT NULL,
    created_at TEXT NOT NULL, last_used_at TEXT,
    routing_token TEXT, device_pubkey TEXT)`)
  return db
}

test("upsert then get returns the row", () => {
  const s = new DevicePushTokenStore(freshDb())
  s.put("phone", "android", "fcm-tok")
  expect(s.get("phone")).toMatchObject({ device: "phone", platform: "android", token: "fcm-tok" })
})

test("upsert replaces token + platform for the same device", () => {
  const s = new DevicePushTokenStore(freshDb())
  s.put("phone", "android", "old")
  s.put("phone", "ios", "new")
  expect(s.get("phone")).toMatchObject({ platform: "ios", token: "new" })
})

test("all returns every row; remove deletes one", () => {
  const s = new DevicePushTokenStore(freshDb())
  s.put("a", "ios", "t1"); s.put("b", "android", "t2")
  expect(s.all().length).toBe(2)
  s.remove("a")
  expect(s.all().map((r) => r.device)).toEqual(["b"])
})

test("putNative stores routingToken + pubkey and get returns them", () => {
  const s = new DevicePushTokenStore(freshDb())
  s.putNative("phone", "ios", "rt-123", "PUBKEY")
  expect(s.get("phone")).toMatchObject({ platform: "ios", routing_token: "rt-123", device_pubkey: "PUBKEY" })
})
