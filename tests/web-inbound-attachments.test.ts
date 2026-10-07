// tests/web-inbound-attachments.test.ts
//
// Regression test for Bug C2: the web inbound handler used to drop
// msg.attachments — it neither persisted them on the messages row nor forwarded
// the attachment_* meta keys to the shim, so Claude never saw PWA uploads.
//
// The handler now mirrors the Telegram inbound path. This test pins that
// behavior so the parity gap can't silently reopen.

import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { MessageStore } from "../src/core/session-manager/messages"
import { FileStore } from "../src/core/files/store"
import { handleWebInbound } from "../src/channels/web/inbound-handler"
import type { InboundMessage } from "../src/channels/channel"

let tmpDir: string
let db: ReturnType<typeof openDb>
let messageLog: MessageStore
let fileStore: FileStore
let anaId: string
let sentInbound: Array<{ session_id: string; payload: { content: string; meta: Record<string, string> } }>
let replied: Array<{ chat_id: string; sessionName: string }>

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-web-inbound-"))
  db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  fileStore = new FileStore(db, join(tmpDir, "files"))
  messageLog = new MessageStore(db, fileStore)

  // Insert an "ana" session row so FK constraints are satisfied.
  // handleWebInbound calls messageLog.append(sessionName, ...) where sessionName
  // is the target_session_id from the web message. In tests we use "ana".
  // Since messages.session_id is a FK to sessions.id, we need a real row.
  const now = new Date().toISOString()
  anaId = "test-ana-id"
  db.run(
    `INSERT INTO sessions (id, name, status, agent, workdir, model, mute, can_orchestrate, tmux_target, created_at)
     VALUES (?, 'ana', 'active', 'claude', '/home', NULL, 0, 1, 'cmux:ana', ?)`,
    [anaId, now],
  )

  sentInbound = []
  replied = []
})

afterEach(() => {
  try { db.close() } catch {}
  rmSync(tmpDir, { recursive: true, force: true })
})

// Build deps using a messageLog wrapper that translates session name → UUID
// (mirroring what main.ts does in the web inbound handler wiring).
const deps = () => ({
  messageLog: {
    append: (sessionName: string, entry: any) => {
      // Simulate the main.ts wrapper: look up by name, use UUID
      const row = db.query("SELECT id FROM sessions WHERE name = ? AND status != 'archived'").get(sessionName) as { id: string } | null
      messageLog.append(row?.id ?? sessionName, entry)
    },
  } as any,
  // THE inbound funnel (SessionManager.deliver in production) — the handler no
  // longer branches adapter-vs-socket itself.
  deliver: async (session_id: string, text: string, meta: Record<string, string>) => {
    sentInbound.push({ session_id, payload: { content: text, meta } })
  },
  hasSession: (_name: string) => true,
  replyNoSuchSession: async (chat_id: string, sessionName: string) => {
    replied.push({ chat_id, sessionName })
  },
})

test("web inbound with attachment: messages row carries attachments + shim receives attachment_* meta (C2 regression)", async () => {
  // Synthesize the post-/upload state: a web-upload row already exists.
  const { file_id } = await fileStore.put({
    kind: "photo", mime: "image/png", name: "shot.png",
    origin: "web-upload", device: "iphone",
    bytes: new Uint8Array([1, 2, 3, 4]),
  })

  const msg: InboundMessage = {
    channel: "web",
    chat_id: "web:iphone",
    message_id: "m-1",
    user: "iphone",
    user_id: "iphone",
    ts: "2026-05-23T00:00:00Z",
    text: "look at this",
    target_session_id: "ana",
    attachments: [{ file_id, kind: "photo", mime: "image/png", size: 4, name: "shot.png" }],
  }

  await handleWebInbound(msg, deps())

  // 1. messages row persisted attachments (query by session UUID)
  const entries = messageLog.get(anaId)
  expect(entries.length).toBe(1)
  expect(entries[0]!.attachments).toEqual([
    { file_id, kind: "photo", mime: "image/png", size: 4, name: "shot.png" },
  ])

  // 2. shim received the attachment_* meta keys (matches telegram inbound shape)
  expect(sentInbound.length).toBe(1)
  expect(sentInbound[0]!.session_id).toBe("ana")
  expect(sentInbound[0]!.payload.meta).toMatchObject({
    chat_id: "web:iphone",
    message_id: "m-1",
    user: "iphone",
    user_id: "iphone",
    ts: "2026-05-23T00:00:00Z",
    attachment_kind: "photo",
    attachment_file_id: file_id,
    attachment_mime: "image/png",
    attachment_size: "4",
    attachment_name: "shot.png",
  })
})

test("web inbound without attachments: no attachment_* meta keys", async () => {
  const msg: InboundMessage = {
    channel: "web",
    chat_id: "web:iphone",
    message_id: "m-2",
    user: "iphone",
    user_id: "iphone",
    ts: "2026-05-23T00:00:00Z",
    text: "plain text",
    target_session_id: "ana",
  }

  await handleWebInbound(msg, deps())

  expect(sentInbound.length).toBe(1)
  const metaKeys = Object.keys(sentInbound[0]!.payload.meta)
  expect(metaKeys.filter((k) => k.startsWith("attachment_"))).toEqual([])
})

test("web inbound for unknown session: replies, no shim send", async () => {
  const msg: InboundMessage = {
    channel: "web",
    chat_id: "web:iphone",
    message_id: "m-3",
    user: "iphone",
    user_id: "iphone",
    ts: "2026-05-23T00:00:00Z",
    text: "hi",
    target_session_id: "ghost",
  }
  await handleWebInbound(msg, { ...deps(), hasSession: () => false })
  expect(sentInbound.length).toBe(0)
  expect(replied).toEqual([{ chat_id: "web:iphone", sessionName: "ghost" }])
})
