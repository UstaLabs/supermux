import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { DevicePushTokenStore } from "./device-tokens"
import { PushSubscriptionStore } from "./subscriptions"
import { auditPushTargets } from "./audit"
import type { Logger } from "../../shared/log"

function stores() {
  const db = new Database(":memory:")
  db.run(`CREATE TABLE device_push_tokens (device TEXT PRIMARY KEY, platform TEXT NOT NULL, token TEXT NOT NULL, routing_token TEXT, device_pubkey TEXT, created_at TEXT NOT NULL, last_used_at TEXT)`)
  db.run(`CREATE TABLE push_subscriptions (device TEXT PRIMARY KEY, endpoint TEXT NOT NULL, p256dh TEXT NOT NULL, auth TEXT NOT NULL, user_agent TEXT, created_at TEXT NOT NULL, last_used_at TEXT)`)
  return { native: new DevicePushTokenStore(db), web: new PushSubscriptionStore(db) }
}

function recorder() {
  const lines: { level: string; msg: string; extra?: Record<string, unknown> }[] = []
  const at = (level: string) => (msg: string, extra?: Record<string, unknown>) => { lines.push({ level, msg, extra }) }
  const log: Logger = { debug: at("debug"), info: at("info"), warn: at("warn"), error: at("error") }
  return { log, lines }
}

// A device revoked by editing devices.json (or by the CLI, in another process)
// never fired the broker's revoke listeners, so its push rows stayed and kept
// receiving every push: the "dev iphone" row on the live broker (card #86).
test("removes push rows whose device is no longer paired and keeps the paired ones", () => {
  const { native, web } = stores()
  native.putNative("fold", "android", "rt-fold", "PK1")
  native.putNative("dev iphone", "ios", "rt-old", "PK2")
  web.upsert({ device: "fold", endpoint: "ep1", keys: { p256dh: "a", auth: "b" } })
  web.upsert({ device: "old browser", endpoint: "ep2", keys: { p256dh: "a", auth: "b" } })
  const { log } = recorder()

  auditPushTargets({ paired: new Set(["fold"]), deviceTokenStore: native, pushStore: web, log })

  expect(native.all().map((r) => r.device)).toEqual(["fold"])
  expect(web.all().map((r) => r.device)).toEqual(["fold"])
})

test("logs each removed orphan with its kind", () => {
  const { native, web } = stores()
  native.putNative("dev iphone", "ios", "rt-old", "PK2")
  web.upsert({ device: "old browser", endpoint: "ep2", keys: { p256dh: "a", auth: "b" } })
  const { log, lines } = recorder()

  auditPushTargets({ paired: new Set(["fold"]), deviceTokenStore: native, pushStore: web, log })

  expect(lines.filter((l) => l.msg === "push_target_orphan_removed")).toEqual([
    { level: "warn", msg: "push_target_orphan_removed", extra: { device: "dev iphone", kind: "native", platform: "ios" } },
    { level: "warn", msg: "push_target_orphan_removed", extra: { device: "old browser", kind: "web" } },
  ])
})

test("works with only one of the two stores", () => {
  const { native } = stores()
  native.putNative("ghost", "android", "rt", "PK")
  const { log } = recorder()

  auditPushTargets({ paired: new Set(["fold"]), deviceTokenStore: native, log })

  expect(native.all()).toEqual([])
})

test("logs the targets that remain, so a boot says who can be pushed to", () => {
  const { native, web } = stores()
  native.putNative("fold", "android", "rt-fold", "PK1")
  web.upsert({ device: "browser", endpoint: "ep1", keys: { p256dh: "a", auth: "b" } })
  const { log, lines } = recorder()

  auditPushTargets({ paired: new Set(["fold", "browser"]), deviceTokenStore: native, pushStore: web, log })

  expect(lines).toEqual([
    { level: "info", msg: "push_targets", extra: { web: ["browser"], native: [{ device: "fold", platform: "android" }] } },
  ])
})

// An unreadable devices.json reads as no devices at all. That must not cost every
// phone its push registration; with nobody paired nothing can connect anyway.
test("drops nothing when no device is paired at all", () => {
  const { native, web } = stores()
  native.putNative("fold", "android", "rt-fold", "PK1")
  web.upsert({ device: "browser", endpoint: "ep1", keys: { p256dh: "a", auth: "b" } })
  const { log } = recorder()

  auditPushTargets({ paired: new Set(), deviceTokenStore: native, pushStore: web, log })

  expect(native.all().map((r) => r.device)).toEqual(["fold"])
  expect(web.all().map((r) => r.device)).toEqual(["browser"])
})
