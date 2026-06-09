import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { transformOutbound } from "../src/core/routing"
import { Registry } from "../src/core/session-manager/registry"
import { openDb, runMigrations } from "../src/core/storage/db"
import { FileStore } from "../src/core/files/store"
import type { ChannelCapabilities, OutboundAction } from "../src/channels/channel"

const TG_CAPS: ChannelCapabilities = {
  multiplexesSessions: true,
  supportsReactions: true,
  supportsEdit: true,
  supportsAttachments: true,
}

// Web is non-multiplexing: each session is its own chat/timeline in the PWA, so
// the [session] tag prefix is noise (and was leaking inconsistently).
const WEB_CAPS: ChannelCapabilities = {
  multiplexesSessions: false,
  supportsReactions: false,
  supportsEdit: false,
  supportsAttachments: true,
}

let tmpDir: string
let store: FileStore

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-tagprefix-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new FileStore(db, join(tmpDir, "files"))
})
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

function reg() {
  const r = new Registry()
  const ana = r.register({ name: "ana", workdir: "/h", tmux_target: "t", pid: 1 })
  const zoom = r.register({ name: "zoom",   workdir: "/z", tmux_target: "u", pid: 2 })
  r.setActive("chat-1", zoom.id)
  return { r, anaId: ana.id, zoomId: zoom.id }
}

test("no tag prefix when session is active", async () => {
  const { r, zoomId } = reg()
  const action: OutboundAction = { op: "reply", chat_id: "chat-1", text: "hello" }
  const out = await transformOutbound(action, zoomId, TG_CAPS, store, r)
  if (out.op !== "reply") throw new Error()
  expect(out.text).toBe("hello")
  expect(out.disable_notification).toBe(false)
})

test("tag prefix when session is not active", async () => {
  const { r, anaId } = reg()
  const action: OutboundAction = { op: "reply", chat_id: "chat-1", text: "tests passed" }
  const out = await transformOutbound(action, anaId, TG_CAPS, store, r)
  if (out.op !== "reply") throw new Error()
  expect(out.text).toBe("[ana] tests passed")
  expect(out.disable_notification).toBe(false)
})

test("edit_message inherits tag prefix; push policy applied at send time", async () => {
  const { r, anaId } = reg()
  const action: OutboundAction = { op: "edit_message", chat_id: "chat-1", message_id: "1", text: "x" }
  const out = await transformOutbound(action, anaId, TG_CAPS, store, r)
  if (out.op !== "edit_message") throw new Error()
  expect(out.text).toBe("[ana] x")
})

test("react passes through untouched", async () => {
  const { r, zoomId } = reg()
  const action: OutboundAction = { op: "react", chat_id: "chat-1", message_id: "1", emoji: "👀" }
  const out = await transformOutbound(action, zoomId, TG_CAPS, store, r)
  expect(out.op).toBe("react")
})

test("web (non-multiplexing) channel: NO tag prefix even when session is not active", async () => {
  const { r, anaId } = reg()
  const action: OutboundAction = { op: "reply", chat_id: "web:dev", text: "tests passed" }
  const out = await transformOutbound(action, anaId, WEB_CAPS, store, r)
  if (out.op !== "reply") throw new Error()
  expect(out.text).toBe("tests passed") // no "[ana] " prefix on web
  expect(out.disable_notification).toBe(false)
})

test("web channel: mute still suppresses push, and still no prefix", async () => {
  const { r, anaId } = reg()
  r.setMuted(anaId, true)
  const action: OutboundAction = { op: "reply", chat_id: "web:dev", text: "alive" }
  const out = await transformOutbound(action, anaId, WEB_CAPS, store, r)
  if (out.op !== "reply") throw new Error()
  expect(out.text).toBe("alive")
  expect(out.disable_notification).toBe(true)
})

test("web channel: edit_message is not prefixed either", async () => {
  const { r, anaId } = reg()
  const action: OutboundAction = { op: "edit_message", chat_id: "web:dev", message_id: "1", text: "x" }
  const out = await transformOutbound(action, anaId, WEB_CAPS, store, r)
  if (out.op !== "edit_message") throw new Error()
  expect(out.text).toBe("x")
})

test("muted session: reply does not push, still tagged", async () => {
  const { r, anaId } = reg()
  r.setMuted(anaId, true)
  const action: OutboundAction = { op: "reply", chat_id: "chat-1", text: "alive" }
  const out = await transformOutbound(action, anaId, TG_CAPS, store, r)
  if (out.op !== "reply") throw new Error()
  expect(out.text).toBe("[ana] alive")
  expect(out.disable_notification).toBe(true)
})
