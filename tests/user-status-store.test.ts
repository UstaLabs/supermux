import { test, expect } from "bun:test"
import { Database } from "bun:sqlite"
import { MIGRATIONS } from "../src/core/storage/migrations"
import { rowToRecord, type SessionRow } from "../src/core/session-manager/types"

function migratedDb(upTo: number = Infinity): Database {
  const db = new Database(":memory:")
  db.exec("PRAGMA foreign_keys = ON")
  for (const m of MIGRATIONS.filter((m) => m.version <= upTo)) db.exec(m.sql)
  return db
}

test("migration 026 adds user_status/sort_order/draft_payload with defaults", () => {
  const db = migratedDb()
  const cols = (db.query("PRAGMA table_info(sessions)").all() as Array<{ name: string }>).map((c) => c.name)
  expect(cols).toContain("user_status")
  expect(cols).toContain("sort_order")
  expect(cols).toContain("draft_payload")
})

test("migration 026 CHECK rejects an invalid user_status", () => {
  const db = migratedDb()
  expect(() =>
    db.run(
      "INSERT INTO sessions (id,name,status,agent,workdir,created_at,user_status) VALUES ('c1','x','active','claude','/tmp','2026-01-01T00:00:00Z','done')"
    )
  ).toThrow()
})

test("migration 026 backfills existing archived rows to settled", () => {
  const db = migratedDb(25)
  db.run(
    "INSERT INTO sessions (id, name, status, agent, workdir, created_at, killed_at) VALUES ('a1','old','archived','claude','/tmp','2026-01-01T00:00:00Z','2026-01-02T00:00:00Z')"
  )
  const m026 = MIGRATIONS.find((m) => m.version === 26)!
  db.exec(m026.sql)
  const row = db.query("SELECT user_status FROM sessions WHERE id='a1'").get() as { user_status: string }
  expect(row.user_status).toBe("settled")
})

test("migration 026 defaults non-archived rows to in_progress", () => {
  const db = migratedDb(25)
  db.run(
    "INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('b1','live','active','claude','/tmp','2026-01-01T00:00:00Z')"
  )
  const m026 = MIGRATIONS.find((m) => m.version === 26)!
  db.exec(m026.sql)
  const row = db.query("SELECT user_status FROM sessions WHERE id='b1'").get() as { user_status: string }
  expect(row.user_status).toBe("in_progress")
})

test("rowToRecord maps user_status, sort_order, draft_payload", () => {
  const row = {
    id: "s1", name: "n", status: "active", agent: "claude", workdir: "/tmp",
    model: null, reasoning_level: null, mute: 0, can_orchestrate: 0, role: "worker",
    is_default: 0, internal: 0, tmux_target: null, tmux_window_id: null,
    agent_session_id: null, agent_home: null, created_at: "2026-01-01T00:00:00Z",
    killed_at: null, base_commit: null, base_commits: null, repo_root: null,
    base_branch: null, session_branch: null, finish_job: null, self_renamed: 0,
    user_status: "draft", sort_order: 3,
    draft_payload: JSON.stringify({ text: "hi", attachments: [] }),
  } as unknown as SessionRow
  const rec = rowToRecord(row)
  expect(rec.user_status).toBe("draft")
  expect(rec.sort_order).toBe(3)
  expect(rec.draft_payload).toEqual({ text: "hi", attachments: [] })
})

test("rowToRecord defaults user_status to in_progress when column absent", () => {
  const row = {
    id: "s2", name: "n", status: "active", agent: "claude", workdir: "/tmp",
    model: null, reasoning_level: null, mute: 0, can_orchestrate: 0, role: "worker",
    is_default: 0, internal: 0, tmux_target: null, tmux_window_id: null,
    agent_session_id: null, agent_home: null, created_at: "2026-01-01T00:00:00Z",
    killed_at: null, base_commit: null, base_commits: null, repo_root: null,
    base_branch: null, session_branch: null, finish_job: null, self_renamed: 0,
  } as unknown as SessionRow
  const rec = rowToRecord(row)
  expect(rec.user_status).toBe("in_progress")
  expect(rec.sort_order).toBe(0)
  expect(rec.draft_payload).toBeUndefined()
})

import { SessionStore } from "../src/core/session-manager/session-store"

test("register defaults new sessions to in_progress with sort_order 0", () => {
  const store = new SessionStore(migratedDb())
  const s = store.register({ name: "t1", agent: "claude", workdir: "/tmp", pid: 100 })
  expect(s.user_status).toBe("in_progress")
  expect(s.sort_order).toBe(0)
  expect(s.draft_payload).toBeUndefined()
})

test("register can create a draft with a payload and no persisted default", () => {
  const db = migratedDb()
  const store = new SessionStore(db)
  const s = store.register({
    name: "d1", agent: "claude", workdir: "/tmp", pid: 0,
    user_status: "draft", draft_payload: { text: "plan", attachments: [] },
  })
  expect(s.user_status).toBe("draft")
  const row = db.query("SELECT user_status, draft_payload FROM sessions WHERE id=?").get(s.id) as { user_status: string; draft_payload: string }
  expect(row.user_status).toBe("draft")
  expect(JSON.parse(row.draft_payload)).toEqual({ text: "plan", attachments: [] })
})

test("setUserStatus updates DB and cache", () => {
  const store = new SessionStore(migratedDb())
  const s = store.register({ name: "u1", agent: "claude", workdir: "/tmp", pid: 1 })
  store.setUserStatus(s.id, "settled")
  expect(store.getById(s.id)!.user_status).toBe("settled")
})

test("setDraftPayload writes and clears", () => {
  const store = new SessionStore(migratedDb())
  const s = store.register({ name: "u2", agent: "claude", workdir: "/tmp", pid: 0, user_status: "draft" })
  store.setDraftPayload(s.id, { text: "edited" })
  expect(store.getById(s.id)!.draft_payload).toEqual({ text: "edited" })
  store.setDraftPayload(s.id, null)
  expect(store.getById(s.id)!.draft_payload).toBeUndefined()
})

test("setSortOrder updates a single moved item's order", () => {
  const store = new SessionStore(migratedDb())
  const a = store.register({ name: "r-a", agent: "claude", workdir: "/w", pid: 1 })
  store.setSortOrder(a.id, 4)
  expect(store.getById(a.id)!.sort_order).toBe(4)
})
