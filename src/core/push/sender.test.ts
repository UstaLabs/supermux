import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { PushSubscriptionStore } from "./subscriptions"
import { createPushSender } from "./sender"
import type { Logger } from "../../shared/log"

function store() {
  const db = new Database(":memory:")
  db.run(`CREATE TABLE push_subscriptions (device TEXT PRIMARY KEY, endpoint TEXT NOT NULL, p256dh TEXT NOT NULL, auth TEXT NOT NULL, user_agent TEXT, created_at TEXT NOT NULL, last_used_at TEXT)`)
  const s = new PushSubscriptionStore(db)
  s.upsert({ device: "browser", endpoint: "https://push.example/ep", keys: { p256dh: "a", auth: "b" } })
  return s
}

function recorder() {
  const lines: { level: string; msg: string; extra?: Record<string, unknown> }[] = []
  const at = (level: string) => (msg: string, extra?: Record<string, unknown>) => { lines.push({ level, msg, extra }) }
  const log: Logger = { debug: at("debug"), info: at("info"), warn: at("warn"), error: at("error") }
  return { log, lines }
}

const vapid = { publicKey: "pub", privateKey: "priv", subject: "mailto:a@b" }
const failWith = (statusCode: number) => async () => { throw Object.assign(new Error(`status ${statusCode}`), { statusCode }) }

// Card #86: web push results use the same lines as native ones.
test("logs a delivered web push", async () => {
  const { log, lines } = recorder()
  const sender = createPushSender({ vapid, store: store(), log, webpushAdapter: { sendNotification: async () => ({ statusCode: 201 }) } })
  await sender.sendToDevice("browser", { session: "s", ts: "t" })
  expect(lines).toEqual([{ level: "info", msg: "push_result", extra: { device: "browser", transport: "web", ok: true } }])
})

test("a subscription the push service calls gone is removed, and the removal is logged", async () => {
  const s = store()
  const { log, lines } = recorder()
  const sender = createPushSender({ vapid, store: s, log, webpushAdapter: { sendNotification: failWith(410) } })
  expect(await sender.sendToDevice("browser", { session: "s", ts: "t" })).toEqual({ ok: false, gone: true })
  expect(s.get("browser")).toBeNull()
  expect(lines).toEqual([{ level: "warn", msg: "push_target_removed", extra: { device: "browser", transport: "web", reason: "http_410" } }])
})

test("a failed web push is logged with its status", async () => {
  const { log, lines } = recorder()
  const sender = createPushSender({ vapid, store: store(), log, webpushAdapter: { sendNotification: failWith(500) } })
  expect(await sender.sendToDevice("browser", { session: "s", ts: "t" })).toEqual({ ok: false, gone: false })
  expect(lines).toEqual([{ level: "warn", msg: "push_result", extra: { device: "browser", transport: "web", ok: false, status: 500, reason: "http_500", err: "status 500" } }])
})
