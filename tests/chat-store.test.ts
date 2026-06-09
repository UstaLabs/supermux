import { test, expect, describe, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { ChatStore } from "../src/core/session-manager/chat-store"
import { SessionStore } from "../src/core/session-manager/session-store"

let tmpDir: string
let db: any
let sessions: SessionStore
let chats: ChatStore

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-cs-"))
  db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  sessions = new SessionStore(db)
  chats = new ChatStore(db)
})

afterEach(() => {
  rmSync(tmpDir, { recursive: true, force: true })
})

function reg(name: string) {
  return sessions.register({
    name,
    agent: "claude",
    workdir: `/tmp/${name}`,
    tmux_target: `t:${name}`,
    pid: 1,
  })
}

describe("setActive / getActive", () => {
  test("sets and retrieves active session for a chat", () => {
    const s = reg("s1")
    chats.setActive("chat-1", s.id)
    expect(chats.getActive("chat-1")).toBe(s.id)
  })

  test("returns undefined for unknown chat", () => {
    expect(chats.getActive("nonexistent-chat")).toBeUndefined()
  })

  test("setting active creates the chat row in SQLite", () => {
    const s = reg("s1")
    chats.setActive("chat-1", s.id)
    const row = db.query("SELECT * FROM chats WHERE chat_id = ?").get("chat-1")
    expect(row).not.toBeNull()
    expect(row.active_session_id).toBe(s.id)
  })
})

describe("switching active pushes previous to history", () => {
  test("switching A→B pushes A into history", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-1", sB.id)
    expect(chats.getActive("chat-1")).toBe(sB.id)
    expect(chats.getHistory("chat-1")).toContain(sA.id)
    expect(chats.getHistory("chat-1")).not.toContain(sB.id)
  })

  test("history is ordered most-recent first", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    const sC = reg("sC")
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-1", sB.id)
    chats.setActive("chat-1", sC.id)
    const history = chats.getHistory("chat-1")
    expect(history[0]).toBe(sB.id)
    expect(history[1]).toBe(sA.id)
  })

  test("empty history for fresh chat with single active", () => {
    const sA = reg("sA")
    chats.setActive("chat-1", sA.id)
    expect(chats.getHistory("chat-1")).toEqual([])
  })

  test("history is persisted to SQLite", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-1", sB.id)
    const rows = db
      .query("SELECT session_id FROM chat_history WHERE chat_id = ? ORDER BY position ASC")
      .all("chat-1") as { session_id: string }[]
    expect(rows.map((r) => r.session_id)).toContain(sA.id)
  })
})

describe("activeFallback", () => {
  test("falls back to most recent non-archived session in history", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-1", sB.id)
    chats.clearActive("chat-1")
    const fallback = chats.activeFallback("chat-1", sessions)
    expect(fallback).toBe(sA.id)
  })

  test("skips archived sessions in history", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    const sC = reg("sC")
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-1", sB.id)
    chats.setActive("chat-1", sC.id)
    // History is [sB, sA]. Archive sB → should fall back to sA
    sessions.archive(sB.id)
    chats.clearActive("chat-1")
    const fallback = chats.activeFallback("chat-1", sessions)
    expect(fallback).toBe(sA.id)
  })

  test("returns undefined when all history sessions are archived", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-1", sB.id)
    sessions.archive(sA.id)
    chats.clearActive("chat-1")
    const fallback = chats.activeFallback("chat-1", sessions)
    expect(fallback).toBeUndefined()
  })

  test("returns undefined for unknown chat", () => {
    expect(chats.activeFallback("no-such-chat", sessions)).toBeUndefined()
  })
})

describe("removeSessionFromChats", () => {
  test("clears active when removed session is active", () => {
    const s = reg("s1")
    chats.setActive("chat-1", s.id)
    chats.removeSessionFromChats(s.id)
    expect(chats.getActive("chat-1")).toBeUndefined()
    const row = db.query("SELECT active_session_id FROM chats WHERE chat_id = ?").get("chat-1")
    expect(row.active_session_id).toBeNull()
  })

  test("removes session from history across all chats", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    // chat-1: active=sB, history=[sA]
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-1", sB.id)
    // chat-2: active=sB, history=[sA]
    chats.setActive("chat-2", sA.id)
    chats.setActive("chat-2", sB.id)
    // remove sA from all chats
    chats.removeSessionFromChats(sA.id)
    expect(chats.getHistory("chat-1")).not.toContain(sA.id)
    expect(chats.getHistory("chat-2")).not.toContain(sA.id)
  })

  test("no-op for session not referenced by any chat", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    chats.setActive("chat-1", sA.id)
    // sB was never set for any chat
    expect(() => chats.removeSessionFromChats(sB.id)).not.toThrow()
    expect(chats.getActive("chat-1")).toBe(sA.id)
  })
})

describe("clearActive", () => {
  test("clears active without affecting history", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-1", sB.id)
    chats.clearActive("chat-1")
    expect(chats.getActive("chat-1")).toBeUndefined()
    expect(chats.getHistory("chat-1")).toContain(sA.id)
  })

  test("updates SQLite active_session_id to NULL", () => {
    const sA = reg("sA")
    chats.setActive("chat-1", sA.id)
    chats.clearActive("chat-1")
    const row = db.query("SELECT active_session_id FROM chats WHERE chat_id = ?").get("chat-1")
    expect(row.active_session_id).toBeNull()
  })

  test("no-op for unknown chat", () => {
    expect(() => chats.clearActive("ghost-chat")).not.toThrow()
  })
})

describe("persistence across restart", () => {
  test("new ChatStore loads active_session_id from SQLite", () => {
    const sA = reg("sA")
    chats.setActive("chat-1", sA.id)
    const chats2 = new ChatStore(db)
    expect(chats2.getActive("chat-1")).toBe(sA.id)
  })

  test("new ChatStore loads history from SQLite", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    const sC = reg("sC")
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-1", sB.id)
    chats.setActive("chat-1", sC.id)
    // history = [sB, sA]
    const chats2 = new ChatStore(db)
    const history = chats2.getHistory("chat-1")
    expect(history[0]).toBe(sB.id)
    expect(history[1]).toBe(sA.id)
  })

  test("new ChatStore loads multiple chats", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-2", sB.id)
    const chats2 = new ChatStore(db)
    expect(chats2.getActive("chat-1")).toBe(sA.id)
    expect(chats2.getActive("chat-2")).toBe(sB.id)
  })
})

describe("history deduplication", () => {
  test("switching back to a session already in history doesn't create duplicates", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    // A → B (history: [A]) → A (history: [B])
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-1", sB.id)
    chats.setActive("chat-1", sA.id)
    const history = chats.getHistory("chat-1")
    // sB should appear exactly once
    expect(history.filter((id) => id === sB.id).length).toBe(1)
    // sA should not be in history since it's currently active
    expect(history).not.toContain(sA.id)
  })

  test("history contains no duplicates after multiple switches", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    const sC = reg("sC")
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-1", sB.id)
    chats.setActive("chat-1", sC.id)
    chats.setActive("chat-1", sA.id)
    const history = chats.getHistory("chat-1")
    const unique = new Set(history)
    expect(unique.size).toBe(history.length)
  })
})

describe("allChats", () => {
  test("returns a snapshot of all tracked chats", () => {
    const sA = reg("sA")
    const sB = reg("sB")
    chats.setActive("chat-1", sA.id)
    chats.setActive("chat-2", sB.id)
    const all = chats.allChats()
    expect(all.size).toBe(2)
    expect(all.has("chat-1")).toBe(true)
    expect(all.has("chat-2")).toBe(true)
  })

  test("returns a copy, not the live map", () => {
    const sA = reg("sA")
    chats.setActive("chat-1", sA.id)
    const all = chats.allChats()
    const sB = reg("sB")
    chats.setActive("chat-2", sB.id)
    // snapshot should not reflect the new chat
    expect(all.has("chat-2")).toBe(false)
  })
})
