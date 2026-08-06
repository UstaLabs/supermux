import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "./store"
import { workspaceDto, viewDto } from "./dto"

function store() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return { db, ws: new WorkspaceStore(db) }
}

test("workspaceDto carries the fields a client needs, with its views inlined", () => {
  const { db, ws } = store()
  // primary_session_id REFERENCES sessions(id); seed the session first (disk FK, not in plan).
  db.run(
    `INSERT INTO sessions (id, name, status, agent, workdir, created_at)
     VALUES ('s1', 'app', 'active', 'claude', '/w', '2026-01-01T00:00:00.000Z')`,
  )
  const w = ws.create({ name: "app", workdir: "/w", repo_root: "/repo", base_branch: "main", branch: "mux/x", primary_session_id: "s1" })
  const v = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })

  const dto = workspaceDto(ws.getById(w.id)!, ws.listViews(w.id))
  expect(dto).toEqual({
    id: w.id,
    name: "app",
    status: "active",
    workdir: "/w",
    repo_root: "/repo",
    base_branch: "main",
    branch: "mux/x",
    layout: { type: "group", id: expect.any(String), viewIds: [v.id], activeViewId: v.id },
    active_view_id: v.id,
    primary_session_id: "s1",
    name_locked: false,
    sort_order: 0,
    created_at: expect.any(String),
    views: [{ id: v.id, workspace_id: w.id, kind: "chat", state: { sessionId: "s1" } }],
  })
})

test("viewDto omits a null title rather than sending title:null", () => {
  const { ws } = store()
  const w = ws.create({ name: "a", workdir: "/w" })
  const v = ws.addView(w.id, { kind: "editor", state: { mode: "tree" } })

  expect(viewDto(ws.getView(v.id)!)).toEqual({
    id: v.id, workspace_id: w.id, kind: "editor", state: { mode: "tree" },
  })
})

test("viewDto keeps a title when one is set", () => {
  const { ws } = store()
  const w = ws.create({ name: "a", workdir: "/w" })
  const v = ws.addView(w.id, { kind: "terminal", title: "build", state: { scope: "workspace", terminalId: "t1" } })

  expect(viewDto(ws.getView(v.id)!)).toMatchObject({ title: "build" })
})

test("workspaceDto omits archived_at when the workspace is active", () => {
  const { ws } = store()
  const w = ws.create({ name: "a", workdir: "/w" })
  expect(workspaceDto(ws.getById(w.id)!, [])).not.toHaveProperty("archived_at")
})
