import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

/** Apply every migration strictly before 27, so we can seed pre-027 rows. */
function dbAt026() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS.filter((m) => m.version < 27))
  return db
}

function seedSession(db: ReturnType<typeof openDb>, o: {
  id: string; name: string; workdir: string
  status?: string; repo_root?: string; base_branch?: string; session_branch?: string; sort_order?: number
}) {
  db.run(
    `INSERT INTO sessions (id, name, status, agent, workdir, created_at, repo_root, base_branch, session_branch, sort_order)
     VALUES (?, ?, ?, 'claude', ?, '2026-01-01T00:00:00.000Z', ?, ?, ?, ?)`,
    [o.id, o.name, o.status ?? "active", o.workdir, o.repo_root ?? null, o.base_branch ?? null, o.session_branch ?? null, o.sort_order ?? 0],
  )
}

test("027 gives every session exactly one workspace, carrying its paths", () => {
  const db = dbAt026()
  seedSession(db, {
    id: "s1", name: "Fix Session Renaming", workdir: "/home/u/.mux/worktrees/abc",
    repo_root: "/home/u/projects/app", base_branch: "main", session_branch: "mux/fix", sort_order: 3,
  })
  runMigrations(db, MIGRATIONS)

  const rows = db.query("SELECT * FROM workspaces").all() as any[]
  expect(rows).toHaveLength(1)
  expect(rows[0]).toMatchObject({
    name: "Fix Session Renaming",
    status: "active",
    workdir: "/home/u/.mux/worktrees/abc",
    repo_root: "/home/u/projects/app",
    base_branch: "main",
    branch: "mux/fix",
    primary_session_id: "s1",
    name_locked: 0,
    sort_order: 3,
  })
})

test("027 links the session back to its workspace", () => {
  const db = dbAt026()
  seedSession(db, { id: "s1", name: "a", workdir: "/w" })
  runMigrations(db, MIGRATIONS)

  const ws = db.query("SELECT id FROM workspaces").get() as { id: string }
  const s = db.query("SELECT workspace_id FROM sessions WHERE id = 's1'").get() as { workspace_id: string }
  expect(s.workspace_id).toBe(ws.id)
})

test("027 makes one chat view per workspace pointing at the session", () => {
  const db = dbAt026()
  seedSession(db, { id: "s1", name: "a", workdir: "/w" })
  runMigrations(db, MIGRATIONS)

  const views = db.query("SELECT * FROM views").all() as any[]
  expect(views).toHaveLength(1)
  expect(views[0].kind).toBe("chat")
  expect(JSON.parse(views[0].state)).toEqual({ sessionId: "s1" })
})

test("027 writes a valid one-group layout naming that view", () => {
  const db = dbAt026()
  seedSession(db, { id: "s1", name: "a", workdir: "/w" })
  runMigrations(db, MIGRATIONS)

  const ws = db.query("SELECT layout, active_view_id FROM workspaces").get() as any
  const view = db.query("SELECT id FROM views").get() as { id: string }
  expect(JSON.parse(ws.layout)).toEqual({
    type: "group",
    id: expect.any(String),
    viewIds: [view.id],
    activeViewId: view.id,
  })
  expect(ws.active_view_id).toBe(view.id)
})

test("027 marks an archived session's workspace archived", () => {
  const db = dbAt026()
  seedSession(db, { id: "s1", name: "a", workdir: "/w", status: "archived" })
  runMigrations(db, MIGRATIONS)

  const ws = db.query("SELECT status FROM workspaces").get() as { status: string }
  expect(ws.status).toBe("archived")
})

test("027 gives each session its own workspace, never a shared one", () => {
  const db = dbAt026()
  seedSession(db, { id: "s1", name: "a", workdir: "/same" })
  seedSession(db, { id: "s2", name: "b", workdir: "/same" })
  runMigrations(db, MIGRATIONS)

  const ids = (db.query("SELECT id FROM workspaces").all() as any[]).map((r) => r.id)
  expect(new Set(ids).size).toBe(2)
})

test("027 gives every workspace a distinct id and every view a distinct id", () => {
  const db = dbAt026()
  for (let i = 0; i < 25; i++) seedSession(db, { id: `s${i}`, name: `n${i}`, workdir: "/w" })
  runMigrations(db, MIGRATIONS)

  const ws = (db.query("SELECT id FROM workspaces").all() as any[]).map((r) => r.id)
  const vs = (db.query("SELECT id FROM views").all() as any[]).map((r) => r.id)
  expect(new Set(ws).size).toBe(25)
  expect(new Set(vs).size).toBe(25)
})

test("027 on an empty database creates the tables and no rows", () => {
  const db = dbAt026()
  runMigrations(db, MIGRATIONS)
  expect(db.query("SELECT count(*) c FROM workspaces").get()).toEqual({ c: 0 })
  expect(db.query("SELECT count(*) c FROM views").get()).toEqual({ c: 0 })
})

test("removing a workspace cascades to its views", () => {
  const db = dbAt026()
  seedSession(db, { id: "s1", name: "a", workdir: "/w" })
  runMigrations(db, MIGRATIONS)
  db.exec("PRAGMA foreign_keys = ON")

  const ws = db.query("SELECT id FROM workspaces").get() as { id: string }
  db.run("DELETE FROM workspaces WHERE id = ?", [ws.id])
  expect(db.query("SELECT count(*) c FROM views").get()).toEqual({ c: 0 })
})
