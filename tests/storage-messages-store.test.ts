import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { MessageStore, type Message } from "../src/core/session-manager/messages"
import { SessionStore } from "../src/core/session-manager/session-store"
import { FileStore } from "../src/core/files/store"

let tmpDir: string
let store: MessageStore
let sessionStore: SessionStore
let anaId: string
let aliceId: string

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-msgs-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  sessionStore = new SessionStore(db)
  anaId = sessionStore.register({ name: "ana", agent: "claude", workdir: "/tmp/d", tmux_target: "t:d", pid: 1 }).id
  aliceId = sessionStore.register({ name: "alice", agent: "claude", workdir: "/tmp/a", tmux_target: "t:a", pid: 2 }).id
  store = new MessageStore(db)
})
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

function m(text: string, id = String(Math.random())): Message {
  return { id, ts: new Date().toISOString(), direction: "outbound", channel: "telegram", chat_id: "telegram:1", op: "reply", text }
}

test("append + get returns inserted entries newest-last", () => {
  store.append(anaId, m("a", "1"))
  store.append(anaId, m("b", "2"))
  store.append(anaId, m("c", "3"))
  expect(store.get(anaId).map((e) => e.text)).toEqual(["a", "b", "c"])
})

test("get respects limit and session filter", () => {
  store.append(anaId, m("a", "1"))
  store.append(anaId, m("b", "2"))
  store.append(aliceId, m("x", "3"))
  expect(store.get(anaId, 10).map((e) => e.text)).toEqual(["a", "b"])
  expect(store.get(aliceId,  10).map((e) => e.text)).toEqual(["x"])
  expect(store.get(anaId, 1).map((e) => e.text)).toEqual(["b"])  // newest 1
})

test("update mutates text + edited_at", () => {
  const e = m("hello", "1")
  store.append(anaId, e)
  expect(store.update(anaId, e.id, { text: "hi", edited_at: "2026-05-22T00:00:00Z" })).toBe(true)
  expect(store.get(anaId)[0]?.text).toBe("hi")
  expect(store.get(anaId)[0]?.edited_at).toBe("2026-05-22T00:00:00Z")
})

test("addReaction appends to reactions JSON column", () => {
  const e = m("hello", "1")
  store.append(anaId, e)
  store.addReaction(anaId, e.id, "👍", "2026-05-22T00:00:00Z")
  expect(store.get(anaId)[0]?.reactions).toEqual([{ emoji: "👍", ts: "2026-05-22T00:00:00Z" }])
})

test("attachments JSON column round-trips", () => {
  const e: Message = { ...m("with file", "1"), attachments: [{ file_id: "abc", kind: "photo", mime: "image/png", size: 100 }] }
  store.append(anaId, e)
  expect(store.get(anaId)[0]?.attachments).toEqual([{ file_id: "abc", kind: "photo", mime: "image/png", size: 100 }])
})

test("removeSession drops all rows for that session", () => {
  store.append(anaId, m("a", "1"))
  store.append(aliceId,  m("x", "2"))
  store.removeSession(anaId)
  expect(store.get(anaId)).toEqual([])
  expect(store.get(aliceId).length).toBe(1)
})

test("append listener fires after insert", () => {
  let seen: Message | null = null
  store.on("append", (_s, entry) => { seen = entry })
  store.append(anaId, m("a", "1"))
  expect(seen).not.toBeNull()
  expect(seen!.text).toBe("a")
})

test("allSessions returns distinct session IDs", () => {
  store.append(anaId, m("a", "1"))
  store.append(anaId, m("b", "2"))
  store.append(aliceId,  m("x", "3"))
  expect(store.allSessions().sort()).toEqual([aliceId, anaId].sort())
})

test("PRIMARY KEY id allows same telegram message_id across different chats", () => {
  // Regression: telegram message_ids are unique per chat, not globally. If the
  // broker's id-construction site uses `in:telegram:<message_id>` only, the
  // second insert (different chat, same message_id) collides on PRIMARY KEY.
  // The fix is to include the namespaced chat_id in the entry id.
  const ts = "2026-05-22T00:00:00Z"
  const base = { ts, direction: "inbound" as const, channel: "telegram", message_id: "42", op: "reply" as const, text: "hi" }
  store.append(anaId, { ...base, id: "in:telegram:111:42", chat_id: "telegram:111" })
  // Must NOT throw: different chat, same message_id, distinct id.
  expect(() => store.append(anaId, { ...base, id: "in:telegram:222:42", chat_id: "telegram:222" })).not.toThrow()
  expect(store.get(anaId).length).toBe(2)
})

test("survives reopen with same data", () => {
  store.append(anaId, m("a", "1"))
  const dbPath = join(tmpDir, "test.sqlite3")
  ;(store as any).db.close()
  const db2 = openDb(dbPath)
  runMigrations(db2, join(import.meta.dir, "../src/core/storage/migrations"))
  const store2 = new MessageStore(db2)
  expect(store2.get(anaId).map((e) => e.text)).toEqual(["a"])
})

test("append bumps ref_count for each referenced attachment (C1 regression)", async () => {
  // Bug C1: prior to fix, every attachment row stayed at ref_count=0 and got
  // GC'd 24h after upload even though messages rows referenced it. The fix
  // wires FileStore through MessageStore so append bumps ref_count.
  const db = (store as any).db
  const fileStore = new FileStore(db, join(tmpDir, "files"))
  const store2 = new MessageStore(db, fileStore)

  const { file_id } = await fileStore.put({
    kind: "photo", mime: "image/png", origin: "web-upload", device: "iphone",
    bytes: new Uint8Array([1, 2, 3, 4]),
  })
  // Row starts at ref_count=0 (no references yet).
  expect((db.prepare("SELECT ref_count FROM attachments WHERE file_id = ?").get(file_id) as { ref_count: number }).ref_count).toBe(0)

  store2.append(anaId, {
    id: "out:1", ts: new Date().toISOString(), direction: "outbound",
    channel: "web", chat_id: "web:iphone", op: "reply", text: "see this",
    attachments: [{ file_id, kind: "photo", mime: "image/png", size: 4 }],
  })
  // Bump is fire-and-forget — yield once so the .then() lands.
  await new Promise((r) => setTimeout(r, 0))

  expect((db.prepare("SELECT ref_count FROM attachments WHERE file_id = ?").get(file_id) as { ref_count: number }).ref_count).toBe(1)

  // Now backdate created_at past the grace window and confirm the row
  // survives GC (was protected by ref_count > 0).
  db.prepare("UPDATE attachments SET created_at = datetime('now', '-25 hours') WHERE file_id = ?").run(file_id)
  await fileStore.gcOnce({ graceHours: 24 })
  expect(await fileStore.get(file_id)).not.toBeNull()
})

test("removeSession decrements ref_count for referenced attachments (C1 regression)", async () => {
  const db = (store as any).db
  const fileStore = new FileStore(db, join(tmpDir, "files"))
  const store2 = new MessageStore(db, fileStore)

  const { file_id } = await fileStore.put({
    kind: "document", mime: "application/pdf", origin: "web-upload", device: "iphone",
    bytes: new Uint8Array([0, 1, 2]),
  })
  store2.append(anaId, {
    id: "out:1", ts: new Date().toISOString(), direction: "outbound",
    channel: "web", chat_id: "web:iphone", op: "reply", text: "doc",
    attachments: [{ file_id, kind: "document", mime: "application/pdf", size: 3 }],
  })
  await new Promise((r) => setTimeout(r, 0))
  expect((db.prepare("SELECT ref_count FROM attachments WHERE file_id = ?").get(file_id) as { ref_count: number }).ref_count).toBe(1)

  store2.removeSession(anaId)
  await new Promise((r) => setTimeout(r, 0))
  expect((db.prepare("SELECT ref_count FROM attachments WHERE file_id = ?").get(file_id) as { ref_count: number }).ref_count).toBe(0)
})
