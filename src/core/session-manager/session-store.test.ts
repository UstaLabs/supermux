import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { SessionStore } from "./session-store"

test("setWorktree persists and reads back worktree metadata", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new SessionStore(db)
  const s = store.register({ id: "s1", name: "n", workdir: "/wt", pid: 1, agent: "claude" })
  store.setWorktree(s.id, { repo_root: "/repo", base_branch: "main", session_branch: "mux/n" })
  const got = store.getById(s.id)
  expect(got).toMatchObject({ repo_root: "/repo", base_branch: "main", session_branch: "mux/n" })
})

test("last_read_at: defaults null, sets and reads back", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new SessionStore(db)
  const s = store.register({ id: "s1", name: "n", workdir: "/wt", pid: 1, agent: "claude" })
  expect(store.getLastReadAt(s.id)).toBeNull()
  store.setLastReadAt(s.id, "2026-06-13T10:00:00.000Z")
  expect(store.getLastReadAt(s.id)).toBe("2026-06-13T10:00:00.000Z")
})

test("draft: set, read back, and clear with null", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new SessionStore(db)
  const s = store.register({ id: "s1", name: "n", workdir: "/wt", pid: 1, agent: "claude" })
  expect(store.getDraft(s.id)).toBeNull()
  store.setDraft(s.id, "half typed")
  expect(store.getDraft(s.id)).toBe("half typed")
  store.setDraft(s.id, null)
  expect(store.getDraft(s.id)).toBeNull()
})

test("allReads / allDrafts: only non-null entries, keyed by session id", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new SessionStore(db)
  const a = store.register({ id: "a", name: "a", workdir: "/wt", pid: 1, agent: "claude" })
  const b = store.register({ id: "b", name: "b", workdir: "/wt", pid: 1, agent: "claude" })
  store.setLastReadAt(a.id, "2026-06-13T10:00:00.000Z")
  store.setDraft(b.id, "draft for b")
  expect(store.allReads()).toEqual({ a: "2026-06-13T10:00:00.000Z" })
  expect(store.allDrafts()).toEqual({ b: "draft for b" })
})
