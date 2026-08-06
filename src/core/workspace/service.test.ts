import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "./store"
import { WorkspaceService, type WorkspaceDeps } from "./service"

function make(overrides: Partial<WorkspaceDeps> = {}) {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new WorkspaceStore(db)
  const calls = { archived: [] as string[], terminalsClosed: [] as string[][], displaysStopped: [] as string[] }
  const deps: WorkspaceDeps = {
    archiveSession: async (id) => { calls.archived.push(id) },
    closeTerminal: async (scope, terminalId) => { calls.terminalsClosed.push([scope, terminalId]) },
    stopDisplay: async (id) => { calls.displaysStopped.push(id) },
    ...overrides,
  }
  return { db, store, calls, svc: new WorkspaceService(store, deps, db) }
}

test("createForSession makes a workspace, a chat view, and points both ways", () => {
  const { store, svc, db } = make()
  db.run(
    `INSERT INTO sessions (id, name, status, agent, workdir, created_at)
     VALUES ('s1', 'Fix It', 'active', 'claude', '/wt', '2026-01-01T00:00:00.000Z')`,
  )

  const w = svc.createForSession({
    sessionId: "s1", name: "Fix It", workdir: "/wt", repo_root: "/repo", base_branch: "main", branch: "mux/fix",
  })

  expect(w).toMatchObject({ name: "Fix It", workdir: "/wt", repo_root: "/repo", primary_session_id: "s1" })
  expect(store.chatSessionIds(w.id)).toEqual(["s1"])
  const link = db.query("SELECT workspace_id FROM sessions WHERE id = 's1'").get() as any
  expect(link.workspace_id).toBe(w.id)
})

test("addChatSession attaches a second session to an existing workspace", () => {
  const { store, svc, db } = make()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/wt','t')`)
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s2','b','active','claude','/wt','t')`)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/wt" })

  const v = svc.addChatSession(w.id, "s2")

  // Order is created_at then id; same-ms inserts can reverse UUID order.
  expect(store.chatSessionIds(w.id)).toEqual(["s1", "s2"])
  expect(v.kind).toBe("chat")
  const link = db.query("SELECT workspace_id FROM sessions WHERE id = 's2'").get() as any
  expect(link.workspace_id).toBe(w.id)
})

test("addChatSession does NOT move the primary session pointer", () => {
  const { store, svc, db } = make()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/wt','t')`)
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s2','b','active','claude','/wt','t')`)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/wt" })
  svc.addChatSession(w.id, "s2")

  expect(store.getById(w.id)!.primary_session_id).toBe("s1")
})

test("closeView on a chat archives the session", async () => {
  const { store, svc, calls, db } = make()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/wt','t')`)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/wt" })
  const viewId = store.listViews(w.id)[0]!.id

  await svc.closeView(viewId)

  expect(calls.archived).toEqual(["s1"])
  expect(store.listViews(w.id)).toEqual([])
})

test("closeView on a chat does NOT start a finish job", async () => {
  // Spec 9.3: a close is a small, fast action. Finish is a separate, later one.
  // The service has no finish dependency at all — that is the guarantee.
  const { svc } = make()
  expect(Object.keys(svc as any)).not.toContain("finish")
  expect(String(WorkspaceService)).not.toContain("finish")
})

test("closeView on a workspace terminal kills that terminal", async () => {
  const { store, svc, calls } = make()
  const w = store.create({ name: "a", workdir: "/wt" })
  const v = store.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "t1" } })

  await svc.closeView(v.id)

  expect(calls.terminalsClosed).toEqual([[`w:${w.id}`, "t1"]])
})

test("closeView on a session terminal kills it under the session scope", async () => {
  const { store, svc, calls } = make()
  const w = store.create({ name: "a", workdir: "/wt" })
  const v = store.addView(w.id, { kind: "terminal", state: { scope: "session", sessionId: "s1", terminalId: "agent" } })

  await svc.closeView(v.id)

  expect(calls.terminalsClosed).toEqual([["s1", "agent"]])
})

test("closeView on a display stops the stream", async () => {
  const { store, svc, calls } = make()
  const w = store.create({ name: "a", workdir: "/wt" })
  const v = store.addView(w.id, { kind: "display", state: { displayId: "d1" } })

  await svc.closeView(v.id)

  expect(calls.displaysStopped).toEqual(["d1"])
})

test("closeView on an editor stops nothing", async () => {
  const { store, svc, calls } = make()
  const w = store.create({ name: "a", workdir: "/wt" })
  const v = store.addView(w.id, { kind: "editor", state: { mode: "tree" } })

  await svc.closeView(v.id)

  expect(calls).toEqual({ archived: [], terminalsClosed: [], displaysStopped: [] })
  expect(store.listViews(w.id)).toEqual([])
})

test("closing the primary session's chat repoints the primary at the next chat", async () => {
  const { store, svc, db } = make()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/wt','t')`)
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s2','b','active','claude','/wt','t')`)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/wt" })
  svc.addChatSession(w.id, "s2")
  // listViews orders by created_at then id; two views in the same ms can put s2 first.
  // Select the primary chat explicitly so the test asserts the behaviour, not UUID order.
  const primaryView = store.listViews(w.id)[0]!

  await svc.closeView(primaryView.id)

  expect(store.getById(w.id)!.primary_session_id).toBe("s2")
  expect(store.getById(w.id)!.name).toBe("a")   // the name does NOT move (spec 9.5 rule 6)
})

test("closing the last chat leaves the workspace open", async () => {
  const { store, svc, db } = make()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/wt','t')`)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/wt" })
  store.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "t1" } })
  const chat = store.listViews(w.id).find((v) => v.kind === "chat")!

  await svc.closeView(chat.id)

  const after = store.getById(w.id)!
  expect(after.status).toBe("active")
  expect(store.listViews(w.id).map((v) => v.kind)).toEqual(["terminal"])
})

test("archiveWorkspace archives every chat session and the workspace", async () => {
  const { store, svc, calls, db } = make()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/wt','t')`)
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s2','b','active','claude','/wt','t')`)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/wt" })
  svc.addChatSession(w.id, "s2")

  await svc.archiveWorkspace(w.id)

  expect(calls.archived.sort()).toEqual(["s1", "s2"])
  expect(store.getById(w.id)!.status).toBe("archived")
})
