// tests/integration/web-cross-channel.test.ts
import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync, mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { WebChannel } from "../../src/channels/web"
import { DeviceStore } from "../../src/channels/web/device-store"
import { MessageStore } from "../../src/core/session-manager/messages"
import { openDb, runMigrations } from "../../src/core/storage/db"
import type { Database } from "bun:sqlite"
import type { InboundMessage, OutboundAction, OutboundResult, Channel } from "../../src/channels/channel"

class MockTelegram implements Channel {
  name = "telegram"
  capabilities = { multiplexesSessions: true, supportsReactions: true, supportsEdit: true, supportsAttachments: true }
  sent: OutboundAction[] = []
  private handlers: Array<(m: InboundMessage) => void> = []
  async start() {}
  async stop() {}
  async send(a: OutboundAction): Promise<OutboundResult> { this.sent.push(a); return { ok: true, value: { message_id: "tg-1" } } }
  on(_e: "inbound", h: (m: InboundMessage) => void) { this.handlers.push(h) }
  emit(m: InboundMessage) { for (const h of this.handlers) h(m) }
}

const DEV_PATH = `/tmp/devices-xchan-${process.pid}.json`
const PORT = 18789
let tg: MockTelegram
let web: WebChannel
let log: MessageStore
let token: string
let tmpDir: string
let db: Database
let anaSessionId: string

beforeEach(async () => {
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const store = new DeviceStore(DEV_PATH)
  token = store.mint("test").token
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-xchan-"))
  db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../../src/core/storage/migrations"))
  log = new MessageStore(db)

  // Insert an "ana" session row so message FK constraints are satisfied.
  // Messages are keyed by session UUID; tests use "ana" as session name.
  anaSessionId = "test-ana-uuid"
  const now = new Date().toISOString()
  db.run(
    `INSERT INTO sessions (id, name, status, agent, workdir, model, mute, can_orchestrate, tmux_target, created_at)
     VALUES (?, 'ana', 'active', 'claude', '/home', NULL, 0, 1, 'cmux:ana', ?)`,
    [anaSessionId, now],
  )

  tg = new MockTelegram()
  web = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [{ name: "ana", workdir: "/h", mute: false, connected: true, agent: "claude" as const }],
    // getSessionLog receives a session name; return messages by UUID
    getSessionLog: (_name) => log.get(anaSessionId),
    setMute: () => {},
    onSendFromWeb: (msg) => {
      // Use the session UUID (not name) for the FK constraint
      log.append(anaSessionId, {
        id: `in:web:${msg.message_id}`, ts: msg.ts, direction: "inbound", channel: "web",
        chat_id: msg.chat_id, message_id: msg.message_id, text: msg.text,
      })
    },
  })
  // Broadcast uses session UUID; for web display we use the name via a lookup.
  // In this test we just pass the UUID through (web clients receive session name
  // from the message_append event; tests only check entry.text).
  log.on("append", (sessionId, entry) => (web as any).broadcastToAll({ type: "message_append", session: sessionId, entry }))
  await web.start()
})

afterEach(async () => {
  await web.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  db.close()
  rmSync(tmpDir, { recursive: true, force: true })
})

test("Telegram inbound → web subscribers see message_append", async () => {
  const ws = await new Promise<WebSocket>((resolve, reject) => {
    const s = new WebSocket(`ws://127.0.0.1:${PORT}/ws`, { headers: { Cookie: `cmux_token=${token}` } })
    s.onopen = () => resolve(s); s.onerror = reject
    setTimeout(() => reject(new Error("timeout")), 2000)
  })
  ws.send(JSON.stringify({ type: "subscribe" }))
  await new Promise((r) => setTimeout(r, 50))
  const received: any[] = []
  ws.onmessage = (e) => received.push(JSON.parse(String(e.data)))
  log.append(anaSessionId, {
    id: "in:telegram:7", ts: new Date().toISOString(), direction: "inbound", channel: "telegram",
    chat_id: "telegram:1", message_id: "7", text: "from telegram",
  })
  await new Promise((r) => setTimeout(r, 100))
  expect(received.some((f) => f.type === "message_append" && f.entry.text === "from telegram")).toBe(true)
  ws.close()
})

test("web send → onSendFromWeb fires → log entry surfaces", async () => {
  const ws = await new Promise<WebSocket>((resolve, reject) => {
    const s = new WebSocket(`ws://127.0.0.1:${PORT}/ws`, { headers: { Cookie: `cmux_token=${token}` } })
    s.onopen = () => resolve(s); s.onerror = reject
    setTimeout(() => reject(new Error("timeout")), 2000)
  })
  ws.send(JSON.stringify({ type: "send", session: "ana", op: "reply", args: { text: "from web" } }))
  await new Promise((r) => setTimeout(r, 100))
  // Query by session UUID (not name)
  const entries = log.get(anaSessionId)
  expect(entries.some((e) => e.text === "from web" && e.direction === "inbound" && e.channel === "web")).toBe(true)
  ws.close()
})

// ── The web channel is a TRANSPORT (step 3) ─────────────────────────────────
// WebChannel.send() used to invent a message id and write to no socket; the
// message reached the screen only because a listener on the message log
// broadcast it. These tests pin the new contract: send() writes the frame.

test("send() delivers exactly one message_append to a connected client", async () => {
  const ws = await new Promise<WebSocket>((resolve, reject) => {
    const s = new WebSocket(`ws://127.0.0.1:${PORT}/ws`, { headers: { Cookie: `cmux_token=${token}` } })
    s.onopen = () => resolve(s); s.onerror = reject
    setTimeout(() => reject(new Error("timeout")), 2000)
  })
  ws.send(JSON.stringify({ type: "subscribe" }))
  await new Promise((r) => setTimeout(r, 50))
  const received: any[] = []
  ws.onmessage = (e) => received.push(JSON.parse(String(e.data)))

  const entry = {
    id: "out:web:uuid-1", ts: new Date().toISOString(), direction: "outbound" as const,
    channel: "web", chat_id: "web", op: "reply", text: "hello from the agent",
  }
  const res = await web.send({ op: "reply", chat_id: "web", text: entry.text }, { sessionId: anaSessionId, entry })
  await new Promise((r) => setTimeout(r, 100))

  expect(res.ok).toBe(true)
  const appends = received.filter((f) => f.type === "message_append" && f.entry?.text === entry.text)
  expect(appends.length).toBe(1)                 // not zero, and not two
  expect(appends[0]!.session).toBe(anaSessionId) // Bug I1: the frame must name its session
  ws.close()
})

// The agent is not bound to a present client. The message is already in the
// transcript before send() runs, so nobody listening is still a success.
test("send() with no connected client succeeds", async () => {
  const entry = {
    id: "out:web:uuid-2", ts: new Date().toISOString(), direction: "outbound" as const,
    channel: "web", chat_id: "web", op: "reply", text: "nobody is looking",
  }
  const res = await web.send({ op: "reply", chat_id: "web", text: entry.text }, { sessionId: anaSessionId, entry })
  expect(res.ok).toBe(true)
  expect((res as any).value.clients).toBe(0)
})

// Bug I1 guard: a frame with no session lands in the client's bySession[undefined].
test("send() refuses a message with no entry", async () => {
  const res = await web.send({ op: "reply", chat_id: "web", text: "unaddressed" })
  expect(res.ok).toBe(false)
})

test("send() refuses an op the web channel cannot do", async () => {
  const res = await web.send({ op: "react", chat_id: "web", message_id: "1", emoji: "👍" })
  expect(res.ok).toBe(false)
  expect((res as any).error).toContain("does not support")
})
