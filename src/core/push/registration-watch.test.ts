import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { DevicePushTokenStore } from "./device-tokens"
import { PushRegistrationWatch } from "./registration-watch"
import type { Logger } from "../../shared/log"

function setup() {
  const db = new Database(":memory:")
  db.run(`CREATE TABLE device_push_tokens (device TEXT PRIMARY KEY, platform TEXT NOT NULL, token TEXT NOT NULL, routing_token TEXT, device_pubkey TEXT, created_at TEXT NOT NULL, last_used_at TEXT)`)
  const store = new DevicePushTokenStore(db)
  const lines: { level: string; msg: string; extra?: Record<string, unknown> }[] = []
  const at = (level: string) => (msg: string, extra?: Record<string, unknown>) => { lines.push({ level, msg, extra }) }
  const log: Logger = { debug: at("debug"), info: at("info"), warn: at("warn"), error: at("error") }
  return { store, lines, watch: new PushRegistrationWatch(store, log) }
}

// Card #86: from 10-27 every phone failed to register and the broker never said
// so. A phone app that connects without a push registration is now a warning.
test("warns when a phone app connects with no push registration", () => {
  const { watch, lines } = setup()
  watch.connected("android-4", "android")
  expect(lines).toEqual([{ level: "warn", msg: "push_device_unregistered", extra: { device: "android-4", surface: "android" } }])
})

test("warns once per device until it registers", () => {
  const { watch, lines } = setup()
  watch.connected("android-4", "android")
  watch.connected("android-4", "android")
  expect(lines.length).toBe(1)
})

test("stays quiet for a registered phone, and for desktop and web clients", () => {
  const { store, watch, lines } = setup()
  store.putNative("fold", "android", "rt", "PK")
  watch.connected("fold", "android")
  watch.connected("macos-3", "desktop")
  watch.connected("browser", "web")
  watch.connected("old-app", undefined)
  expect(lines).toEqual([])
})

test("logs a registration, and warns again if the device later loses it", () => {
  const { store, watch, lines } = setup()
  watch.connected("ios", "ios")
  store.putNative("ios", "ios", "rt", "PK")
  watch.registered("ios", "ios")
  store.remove("ios")
  watch.connected("ios", "ios")
  expect(lines.map((l) => l.msg)).toEqual(["push_device_unregistered", "push_device_registered", "push_device_unregistered"])
  expect(lines[1]).toEqual({ level: "info", msg: "push_device_registered", extra: { device: "ios", platform: "ios" } })
})
