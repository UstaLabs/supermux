import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { startSocketServer } from "../../src/core/session-manager/socket-server"
import { connectShim } from "../../src/shim/socket-client"
import { Registry } from "../../src/core/session-manager/registry"
import { transformOutbound, classifyInbound } from "../../src/core/routing"
import { openDb, runMigrations } from "../../src/core/storage/db"
import { FileStore } from "../../src/core/files/store"
import type { ChannelCapabilities, OutboundAction } from "../../src/channels/channel"

const TG_CAPS: ChannelCapabilities = {
  multiplexesSessions: true,
  supportsReactions: true,
  supportsEdit: true,
  supportsAttachments: true,
}

let dir: string
let server: any
let store: FileStore
let db: ReturnType<typeof openDb>

function stringArg(args: Record<string, unknown>, key: string): string {
  const value = args[key]
  if (typeof value !== "string") throw new Error(`${key} must be a string`)
  return value
}

function optionalStringArg(args: Record<string, unknown>, key: string): string | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (typeof value !== "string") throw new Error(`${key} must be a string`)
  return value
}

function optionalStringArrayArg(args: Record<string, unknown>, key: string): string[] | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (!Array.isArray(value) || !value.every((item) => typeof item === "string")) throw new Error(`${key} must be an array of strings`)
  return value
}

function optionalFormatArg(args: Record<string, unknown>, key: string): "text" | "markdownv2" | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (value === "text" || value === "markdownv2") return value
  throw new Error(`${key} must be text or markdownv2`)
}

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "agentmux-e2e-"))
  db = openDb(join(dir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../../src/core/storage/migrations"))
  store = new FileStore(db, join(dir, "files"))
})
afterEach(async () => { await server?.close(); try { db.close() } catch {}; rmSync(dir, { recursive: true, force: true }) })

test("two shims register; inbound routes to active; outbound is tagged for inactive", async () => {
  const registry = new Registry(db)
  const sentToTelegram: any[] = []

  server = await startSocketServer({
    socketsDir: dir,
    handler: {
      onRegister: async (msg) => {
        const name = msg.requested_name!
        const session = registry.register({ name, workdir: msg.workdir, tmux_target: `t:${name}`, pid: msg.pid })
        return { name, session_id: session.id }
      },
      onOutbound: async (msg) => {
        const pathName = msg.session_id
        const s = registry.fuzzyResolve(pathName)
        const fromSession = s?.id ?? pathName
        const op = msg.op
        let initial: OutboundAction
        if (op.name === "reply") {
          initial = {
            op: "reply",
            chat_id: stringArg(op.args, "chat_id"),
            text: stringArg(op.args, "text"),
            reply_to: optionalStringArg(op.args, "reply_to"),
            files: optionalStringArrayArg(op.args, "files"),
            format: optionalFormatArg(op.args, "format"),
            keyboard: optionalStringArrayArg(op.args, "keyboard"),
          }
        } else if (op.name === "edit_message") {
          initial = {
            op: "edit_message",
            chat_id: stringArg(op.args, "chat_id"),
            message_id: stringArg(op.args, "message_id"),
            text: stringArg(op.args, "text"),
            format: optionalFormatArg(op.args, "format"),
          }
        } else if (op.name === "react") {
          initial = {
            op: "react",
            chat_id: stringArg(op.args, "chat_id"),
            message_id: stringArg(op.args, "message_id"),
            emoji: stringArg(op.args, "emoji"),
          }
        } else {
          return { ok: false, error: "unhandled op in test" }
        }
        const action = await transformOutbound(initial, fromSession, TG_CAPS, store, registry)
        sentToTelegram.push({
          from: fromSession,
          op: action.op,
          args: action.op === "reply"
            ? { text: action.text, chat_id: action.chat_id }
            : action.op === "edit_message"
              ? { text: action.text, chat_id: action.chat_id, message_id: action.message_id }
              : action.op === "react"
                ? { chat_id: action.chat_id, message_id: action.message_id, emoji: action.emoji }
                : {},
          // Push policy: reply respects action.disable_notification; edit/react
          // are always silent on Telegram (no field on the action — implicit).
          disable_notification: action.op === "reply"
            ? !!action.disable_notification
            : true,
        })
        return { ok: true, value: { message_id: sentToTelegram.length } }
      },
      onOrchestration: async () => ({ ok: false, error: "deny" }),
    },
  })

  // Deviation from spec: bind() each session_id BEFORE connectShim so the
  // socket file exists for connect() to succeed (mirrors socket-transport.test.ts).
  await server.bind("ana")
  await server.bind("zoom")

  const recvAna: any[] = []
  const recvZoom: any[] = []
  const ana  = await connectShim({ socketsDir: dir, sessionId: "ana",  workdir: "/h", pid: 1, requestedName: "ana",  channelOnly: true, onInbound: m => recvAna.push(m) })
  const zoom = await connectShim({ socketsDir: dir, sessionId: "zoom", workdir: "/z", pid: 2, requestedName: "zoom", channelOnly: true, onInbound: m => recvZoom.push(m) })

  const zoomId = registry.fuzzyResolve("zoom")!.id
  registry.setActive("chat-1", zoomId)

  // Inbound goes to active session
  const decision = classifyInbound({ chat_id: "chat-1", text: "hello", reply_to: undefined }, registry, () => undefined)
  expect(decision).toMatchObject({ kind: "session", name: "zoom", text: "hello", change_active: false, suspended: false })
  await server.sendInbound("zoom", { content: "hello", meta: { chat_id: "chat-1" } })

  await new Promise(r => setTimeout(r, 50))
  expect(recvZoom).toHaveLength(1)
  expect(recvAna).toHaveLength(0)

  // Outbound from zoom (active) → no tag prefix, push
  const zoomUuid = registry.fuzzyResolve("zoom")!.id
  await zoom.callOutbound({ name: "reply", args: { chat_id: "chat-1", text: "world" } })
  expect(sentToTelegram[0]).toMatchObject({ from: zoomUuid, op: "reply", args: { text: "world" }, disable_notification: false })

  // Outbound from ana (inactive) → tag, push
  const anaUuid = registry.fuzzyResolve("ana")!.id
  await ana.callOutbound({ name: "reply", args: { chat_id: "chat-1", text: "fyi" } })
  expect(sentToTelegram[1]).toMatchObject({ from: anaUuid, op: "reply", args: { text: "[ana] fyi" }, disable_notification: false })

  // edit_message never pushes
  await ana.callOutbound({ name: "edit_message", args: { chat_id: "chat-1", message_id: "1", text: "updated" } })
  expect(sentToTelegram[2]).toMatchObject({ disable_notification: true })

  // mute ana
  registry.setMuted(registry.fuzzyResolve("ana")!.id, true);
  await ana.callOutbound({ name: "reply", args: { chat_id: "chat-1", text: "quiet" } })
  expect(sentToTelegram[3]).toMatchObject({ disable_notification: true })

  await ana.close()
  await zoom.close()
})

test("kill-active fallback: most-recent other, else default PA", async () => {
  const r = new Registry(db)
  const ana = r.register({ name: "ana", workdir: "/h", tmux_target: "t", pid: 1 })
  const a   = r.register({ name: "a",   workdir: "/a", tmux_target: "u", pid: 2 })
  const b   = r.register({ name: "b",   workdir: "/b", tmux_target: "v", pid: 3 })
  r.setActive("chat-1", a.id)
  r.setActive("chat-1", b.id) // b active, a in history
  r.unregister(b.id)
  expect(r.activeFallback("chat-1")).toBe(a.id)
})
