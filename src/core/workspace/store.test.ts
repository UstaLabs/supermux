import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "./store"
import { collectViewIds } from "./layout-tree"

function store() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return { db, ws: new WorkspaceStore(db) }
}

test("create makes a workspace with an empty-safe layout and no views", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w", repo_root: "/repo", base_branch: "main" })
  expect(w).toMatchObject({ name: "app", workdir: "/w", repo_root: "/repo", base_branch: "main", status: "active", name_locked: false })
  expect(ws.listViews(w.id)).toEqual([])
  expect(ws.getById(w.id)).toMatchObject({ id: w.id, name: "app" })
})

test("addView appends the view to the layout and makes it active", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  const v = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })

  const after = ws.getById(w.id)!
  expect(after.active_view_id).toBe(v.id)
  expect(after.layout).toEqual({ type: "group", id: expect.any(String), viewIds: [v.id], activeViewId: v.id })
  expect(ws.listViews(w.id)).toHaveLength(1)
})

test("addView into a group this layout does not have still lands the view somewhere", () => {
  // The desktop client opens a file by putting the tab in a group it split off LOCALLY and
  // PATCHing the layout after, so the POST can reach the broker first — naming a group the
  // stored tree has never seen. addViewToGroup is a no-op for an unknown group, which used to
  // leave the row in no layout at all: an orphan nothing draws and no tab can close. The live
  // db had three of them.
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  const chat = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })

  const v = ws.addView(w.id, { kind: "editor", state: { mode: "file", path: "a.kt" }, groupId: "a-group-only-the-client-has" })

  const after = ws.getById(w.id)!
  expect(collectViewIds(after.layout)).toEqual([chat.id, v.id])
  expect(after.active_view_id).toBe(v.id)
})

test("addView twice puts both views in the same group", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  const v1 = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  const v2 = ws.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "main" } })

  const after = ws.getById(w.id)!
  expect(after.layout).toEqual({ type: "group", id: expect.any(String), viewIds: [v1.id, v2.id], activeViewId: v2.id })
})

test("removeView drops the view from the table and from the layout", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  const v1 = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  const v2 = ws.addView(w.id, { kind: "editor", state: { mode: "tree" } })

  ws.removeView(v2.id)
  const after = ws.getById(w.id)!
  expect(ws.listViews(w.id).map((v) => v.id)).toEqual([v1.id])
  expect(after.layout).toEqual({ type: "group", id: expect.any(String), viewIds: [v1.id], activeViewId: v1.id })
  expect(after.active_view_id).toBe(v1.id)
})

test("removing the last view leaves an empty layout and no active view", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  const v = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  ws.removeView(v.id)

  const after = ws.getById(w.id)!
  expect(ws.listViews(w.id)).toEqual([])
  expect(after.active_view_id).toBeUndefined()
  expect(after.layout).toEqual({ type: "group", id: expect.any(String), viewIds: [], activeViewId: undefined })
})

test("setLayout rejects an invalid tree and keeps the old one", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  const v = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  const before = ws.getById(w.id)!.layout

  expect(() => ws.setLayout(w.id, { type: "group", id: "g", viewIds: [] })).toThrow("empty group: g")
  expect(ws.getById(w.id)!.layout).toEqual(before)
  expect(v.id).toBeTruthy()
})

test("setLayout rejects a tree naming a view that is not in this workspace", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })

  expect(() => ws.setLayout(w.id, { type: "group", id: "g", viewIds: ["ghost"], activeViewId: "ghost" }))
    .toThrow("layout names a view that is not in this workspace: ghost")
})

test("moveView changes the owner and both layouts", () => {
  const { ws } = store()
  const a = ws.create({ name: "a", workdir: "/a" })
  const b = ws.create({ name: "b", workdir: "/b" })
  const v = ws.addView(a.id, { kind: "chat", state: { sessionId: "s1" } })

  ws.moveView(v.id, b.id)

  expect(ws.listViews(a.id)).toEqual([])
  expect(ws.listViews(b.id).map((x) => x.id)).toEqual([v.id])
  expect(ws.getById(a.id)!.layout).toEqual({ type: "group", id: expect.any(String), viewIds: [], activeViewId: undefined })
  expect(ws.getById(b.id)!.layout).toMatchObject({ viewIds: [v.id], activeViewId: v.id })
})

test("rename by the agent leaves name_locked false; rename by the user sets it", () => {
  const { ws } = store()
  const w = ws.create({ name: "old", workdir: "/w" })

  ws.rename(w.id, "agent name", { byUser: false })
  expect(ws.getById(w.id)).toMatchObject({ name: "agent name", name_locked: false })

  ws.rename(w.id, "user name", { byUser: true })
  expect(ws.getById(w.id)).toMatchObject({ name: "user name", name_locked: true })
})

test("archive sets the status and the timestamp and hides it from list()", () => {
  const { ws } = store()
  const w = ws.create({ name: "a", workdir: "/w" })
  ws.archive(w.id)

  expect(ws.list()).toEqual([])
  expect(ws.list({ includeArchived: true }).map((x) => x.id)).toEqual([w.id])
  const got = ws.getById(w.id)!
  expect(got.status).toBe("archived")
  expect(got.archived_at).toBeTruthy()
})

test("unarchive returns the workspace to the live list", () => {
  const { ws } = store()
  const w = ws.create({ name: "a", workdir: "/w" })
  ws.archive(w.id)
  ws.unarchive(w.id)

  expect(ws.list().map((x) => x.id)).toEqual([w.id])
  const got = ws.getById(w.id)!
  expect(got.status).toBe("active")
  expect(got.archived_at).toBeUndefined()
})

test("list returns active workspaces in sort_order then id", () => {
  const { ws } = store()
  const a = ws.create({ name: "a", workdir: "/w", sort_order: 2 })
  const b = ws.create({ name: "b", workdir: "/w", sort_order: 1 })
  expect(ws.list().map((x) => x.id)).toEqual([b.id, a.id])
})

test("reorder assigns sort_order by position", () => {
  const { ws } = store()
  const a = ws.create({ name: "a", workdir: "/w" })
  const b = ws.create({ name: "b", workdir: "/w" })
  const c = ws.create({ name: "c", workdir: "/w" })

  ws.reorder([c.id, a.id, b.id])
  expect(ws.list().map((x) => x.id)).toEqual([c.id, a.id, b.id])
})

test("chatSessionIds returns the session of every chat view, ignoring other kinds", () => {
  const { db, ws } = store()
  const w = ws.create({ name: "a", workdir: "/w" })
  const v1 = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  ws.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "t" } })
  const v2 = ws.addView(w.id, { kind: "chat", state: { sessionId: "s2" } })
  // Distinct created_at so ORDER BY created_at is stable under load (same-ms inserts).
  db.run("UPDATE views SET created_at = '2026-01-01T00:00:01.000Z' WHERE id = ?", [v1.id])
  db.run("UPDATE views SET created_at = '2026-01-01T00:00:03.000Z' WHERE id = ?", [v2.id])

  expect(ws.chatSessionIds(w.id)).toEqual(["s1", "s2"])
})

test("findByPrimarySession finds the workspace a session names", () => {
  // primary_session_id REFERENCES sessions(id) (migration 027); seed the session first.
  const { db, ws } = store()
  db.run(
    `INSERT INTO sessions (id, name, status, agent, workdir, created_at)
     VALUES ('s1', 'a', 'active', 'claude', '/w', '2026-01-01T00:00:00.000Z')`,
  )
  const w = ws.create({ name: "a", workdir: "/w", primary_session_id: "s1" })
  expect(ws.findByPrimarySession("s1")?.id).toBe(w.id)
  expect(ws.findByPrimarySession("nope")).toBeUndefined()
})

// ── Regression: the rpc-worker workspace the user could not archive ──────────
// Two separate bugs produced one symptom. A workspace whose session was archived
// stayed "active" forever, and the sidebar's archive action killed chat sessions
// rather than archiving the workspace — so with the sessions already dead there
// was nothing to kill and the row never left.

test("archive retires a workspace whose session is already gone", () => {
  const { ws } = store()
  const w = ws.create({ name: "rpc-voice", workdir: "/home/u/.mux/state/rpc-workers" })
  ws.addView(w.id, { kind: "chat", state: { sessionId: "dead-session" } })

  ws.archive(w.id)

  expect(ws.list().map((x) => x.id)).not.toContain(w.id)
  expect(ws.getById(w.id)!.status).toBe("archived")
})

test("a workspace keeping a terminal view is not an empty shell", () => {
  // Spec 9.3: closing the last chat does NOT close the workspace — a workspace
  // holding a terminal is still a workspace.
  const { ws } = store()
  const w = ws.create({ name: "keeps a terminal", workdir: "/w" })
  ws.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "main" } })

  expect(ws.listViews(w.id).some((v) => v.kind !== "chat")).toBe(true)
  expect(ws.getById(w.id)!.status).toBe("active")
})
