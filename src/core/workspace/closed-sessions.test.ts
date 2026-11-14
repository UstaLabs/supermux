// #135: the New tab page lists the sessions closed in its workspace, newest first, and reopens one, the same
// session with its history, as a tab. Closing a chat tab archives its session and keeps the session's workspace_id,
// so "closed here" is: this workspace's, archived, and in no chat tab of it.
import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "./store"
import { WorkspaceService } from "./service"
import { collectViewIds } from "./layout-tree"

function setup() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new WorkspaceStore(db)
  const w = store.create({ name: "a", workdir: "/w" })
  const other = store.create({ name: "b", workdir: "/b" })
  const session = (id: string, workspaceId: string, status: string, killedAt: string | null, agent = "claude") =>
    db.run(
      "INSERT INTO sessions (id, name, status, agent, workdir, created_at, workspace_id, killed_at) VALUES (?, ?, ?, ?, '/w', 't', ?, ?)",
      [id, `name ${id}`, status, agent, workspaceId, killedAt],
    )
  return { db, store, w, other, session }
}

test("a workspace's closed sessions are its archived ones in no tab, newest first", () => {
  const { store, w, other, session } = setup()
  session("old", w.id, "archived", "2026-11-10T10:00:00Z")
  session("new", w.id, "archived", "2026-11-12T10:00:00Z", "codex")
  session("live", w.id, "active", null)
  session("elsewhere", other.id, "archived", "2026-11-13T10:00:00Z")
  session("shown", w.id, "archived", "2026-11-13T09:00:00Z")
  store.addView(w.id, { kind: "chat", state: { sessionId: "shown" } })

  expect(store.closedSessions(w.id)).toEqual([
    { id: "new", name: "name new", agent: "codex", closedAt: "2026-11-12T10:00:00Z" },
    { id: "old", name: "name old", agent: "claude", closedAt: "2026-11-10T10:00:00Z" },
  ])
})

test("the list stops at its limit", () => {
  const { store, w, session } = setup()
  for (let i = 0; i < 25; i++) session(`s${i}`, w.id, "archived", `2026-11-${String(10 + (i % 3)).padStart(2, "0")}T10:${String(i).padStart(2, "0")}:00Z`)
  expect(store.closedSessions(w.id).length).toBe(20)
  expect(store.closedSessions(w.id, 5).length).toBe(5)
})

test("reopening resumes the same session and puts its tab in the pane asked for", async () => {
  const { db, store, w, session } = setup()
  session("c1", w.id, "archived", "2026-11-12T10:00:00Z")
  const term = store.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "t1" } })
  const resumed: string[] = []
  const svc = new WorkspaceService(store, {
    archiveSession: async () => {}, resumeSession: async (id) => { resumed.push(id) },
    closeTerminal: async () => {}, closeTerminalScope: async () => {}, stopDisplay: async () => {},
  }, db)
  const group = (store.getById(w.id)!.layout as { id: string }).id

  const view = await svc.reopenClosedSession(w.id, "c1", group)

  expect(resumed).toEqual(["c1"])
  expect(view.kind).toBe("chat")
  expect(view.state).toEqual({ sessionId: "c1" })
  expect(collectViewIds(store.getById(w.id)!.layout)).toEqual([term.id, view.id])
  expect(store.closedSessions(w.id)).toEqual([])
})

test("only a session closed in this workspace can be reopened there", async () => {
  const { db, store, w, other, session } = setup()
  session("live", w.id, "active", null)
  session("theirs", other.id, "archived", "2026-11-12T10:00:00Z")
  const resumed: string[] = []
  const svc = new WorkspaceService(store, {
    archiveSession: async () => {}, resumeSession: async (id) => { resumed.push(id) },
    closeTerminal: async () => {}, closeTerminalScope: async () => {}, stopDisplay: async () => {},
  }, db)
  await expect(svc.reopenClosedSession(w.id, "live")).rejects.toThrow()
  await expect(svc.reopenClosedSession(w.id, "theirs")).rejects.toThrow()
  await expect(svc.reopenClosedSession(w.id, "nope")).rejects.toThrow()
  expect(resumed).toEqual([])
})

test("a reopen that fails to resume leaves no tab behind", async () => {
  const { db, store, w, session } = setup()
  session("c1", w.id, "archived", "2026-11-12T10:00:00Z")
  const svc = new WorkspaceService(store, {
    archiveSession: async () => {}, resumeSession: async () => { throw new Error("agent missing") },
    closeTerminal: async () => {}, closeTerminalScope: async () => {}, stopDisplay: async () => {},
  }, db)
  await expect(svc.reopenClosedSession(w.id, "c1")).rejects.toThrow("agent missing")
  expect(store.listViews(w.id)).toEqual([])
})
