// #163: Settings › Notifications is the person's, kept on the broker: a change on one device is every device's.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel } from "./index"
import { DeviceStore } from "./device-store"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { NotificationPrefsStore } from "../../core/push/notification-prefs"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function boot() {
  const db = openDb(":memory:"); runMigrations(db, MIGRATIONS)
  const store = new NotificationPrefsStore(db)
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-notif-dev-")), "devices.json")
  // A served web client, as in production: a route missing from API_PREFIXES would get its index.html.
  const staticDir = mkdtempSync(join(tmpdir(), "mux-notif-static-"))
  writeFileSync(join(staticDir, "index.html"), "<!doctype html><html><body>SPA</body></html>")
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost", updateChecker: null, staticDir,
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    notificationPrefs: { get: () => store.get("owner"), apply: (patch, by) => store.apply("owner", patch, by) },
  })
  const frames: any[] = []
  const orig = channel.broadcastToAll.bind(channel)
  channel.broadcastToAll = ((f: any) => { frames.push(f); return orig(f) }) as any
  await channel.start()
  const devices = new DeviceStore(devicesFile)
  const phone = devices.mint("phone").token, laptop = devices.mint("laptop").token
  const call = (token: string, method: string, body?: unknown) => fetch(`http://127.0.0.1:${channel!.boundPort}/notifications`, {
    method, headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body),
  })
  return { phone, laptop, call, frames }
}

test("a change on the phone is what the laptop reads, signed by the phone, and every device is told", async () => {
  const { phone, laptop, call, frames } = await boot()
  expect(await (await call(laptop, "GET")).json()).toMatchObject({ agents: "all", sessions: "needs", applets: "on" })
  const r = await call(phone, "PATCH", { sessions: "off", session: { s1: "all" } })
  expect(r.status).toBe(200)
  const after = await (await call(laptop, "GET")).json()
  expect(after).toMatchObject({ sessions: "off", overrides: { session: { s1: "all" } }, updatedBy: '{"kind":"device","name":"phone"}' })
  expect(frames.at(-1)).toMatchObject({ type: "notification_prefs", prefs: { sessions: "off" } })
})

test("a value it can't keep is a 400 and changes nothing", async () => {
  const { phone, call } = await boot()
  expect((await call(phone, "PATCH", { sessions: "on" })).status).toBe(400)
  expect((await call(phone, "PATCH", { session: { s1: "loud" } })).status).toBe(400)
  expect(await (await call(phone, "GET")).json()).toMatchObject({ sessions: "needs", overrides: { session: {} } })
})

test("a reply's message_append says whether it may alert (the overlay and desktop notifications follow it)", async () => {
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-notif-dev-")), "devices.json")
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost", updateChecker: null,
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    replyAlerts: (id) => id === "loud",
  })
  const frames: any[] = []
  channel.broadcastToAll = ((f: any) => { frames.push(f) }) as any
  const entry = { id: "e", direction: "outbound", channel: "web", op: "reply", text: "hi" } as any
  await channel.send({ op: "reply", chat_id: "web", text: "hi" } as any, { sessionId: "loud", entry } as any)
  await channel.send({ op: "reply", chat_id: "web", text: "hi" } as any, { sessionId: "quiet", entry } as any)
  expect(frames.map((f) => [f.session, f.alert])).toEqual([["loud", true], ["quiet", false]])
})
