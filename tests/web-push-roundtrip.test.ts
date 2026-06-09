import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { PushSubscriptionStore } from "../src/core/push/subscriptions"
import { createPushSender } from "../src/core/push/sender"
import { extractPreview, firePushForReply } from "../src/core/push/hook"
import type { OutboundAction } from "../src/channels/channel"

let tmpDir: string
let store: PushSubscriptionStore
let calls: Array<{ chatId: string; payload: any }>

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-push-rt-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new PushSubscriptionStore(db)
  store.upsert({ device: "iphone", endpoint: "ep", keys: { p256dh: "a", auth: "b" } })
  calls = []
})
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

function makeSender() {
  return createPushSender({
    vapid: { publicKey: "p", privateKey: "k", subject: "mailto:x@example.com" },
    store,
    webpushAdapter: {
      sendNotification: async (_sub, payload) => {
        calls.push({ chatId: "unknown", payload: JSON.parse(payload) })
        return { statusCode: 201 }
      },
    },
  })
}

test("extractPreview returns truncated text", () => {
  const action: OutboundAction = { op: "reply", chat_id: "web:iphone", text: "x".repeat(200) }
  const preview = extractPreview(action)
  expect(preview.length).toBeLessThanOrEqual(120)
  expect(preview.endsWith("…")).toBe(true)
})

test("extractPreview returns kind placeholder when text empty", () => {
  const action: OutboundAction = {
    op: "reply", chat_id: "web:iphone", text: "",
    attachments: [{ file_id: "f", kind: "voice" }],
  }
  expect(extractPreview(action)).toBe("🎙 Voice message")
})

const fanArgs = () => ({
  sessionId: "ana-id",
  devices: () => store.all().map((s) => s.device),
  anyPresent: () => false,
})

test("firePushForReply fans out to subscribed devices on unmuted sessions", async () => {
  const sender = makeSender()
  const action: OutboundAction = { op: "reply", chat_id: "web", text: "hello" }
  await firePushForReply({ sender, action, sessionName: "ana", isMuted: () => false, ...fanArgs() })
  expect(calls.length).toBe(1) // one subscribed device (iphone)
  expect(calls[0]!.payload.session).toBe("ana")
  expect(calls[0]!.payload.text).toBe("hello")
})

test("firePushForReply skips muted sessions", async () => {
  const sender = makeSender()
  const action: OutboundAction = { op: "reply", chat_id: "web", text: "hello" }
  await firePushForReply({ sender, action, sessionName: "ana", isMuted: () => true, ...fanArgs() })
  expect(calls.length).toBe(0)
})

test("firePushForReply skips non-web chat_ids", async () => {
  const sender = makeSender()
  const action: OutboundAction = { op: "reply", chat_id: "telegram:1234", text: "hello" }
  await firePushForReply({ sender, action, sessionName: "ana", isMuted: () => false, ...fanArgs() })
  expect(calls.length).toBe(0)
})

test("firePushForReply skips non-reply ops", async () => {
  const sender = makeSender()
  const action: OutboundAction = { op: "react", chat_id: "web", message_id: "m", emoji: "👍" }
  await firePushForReply({ sender, action, sessionName: "ana", isMuted: () => false, ...fanArgs() })
  expect(calls.length).toBe(0)
})
