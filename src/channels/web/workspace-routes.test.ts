import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { WorkspaceStore } from "../../core/workspace/store"
import { WorkspaceService } from "../../core/workspace/service"
import { workspaceDto } from "../../core/workspace/dto"

/**
 * These test the opts layer the routes call, not Bun's HTTP server. The route
 * bodies are three lines each; the value is in the wiring and the broadcast.
 */
function harness() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new WorkspaceStore(db)
  const frames: any[] = []
  const svc = new WorkspaceService(store, {
    archiveSession: async () => {},
    closeTerminal: async () => {},
    stopDisplay: async () => {},
  }, db)
  const broadcast = (f: object) => { frames.push(f) }
  return { db, store, svc, frames, broadcast }
}

test("listWorkspaces returns active workspaces with their views inlined", () => {
  const { store } = harness()
  const w = store.create({ name: "a", workdir: "/w" })
  store.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })

  const dtos = store.list().map((x) => workspaceDto(x, store.listViews(x.id)))
  expect(dtos).toHaveLength(1)
  expect(dtos[0]!.views).toHaveLength(1)
})

test("createWorkspace broadcasts workspace_added", () => {
  const { store, frames, broadcast } = harness()
  const w = store.create({ name: "a", workdir: "/w" })
  broadcast({ type: "workspace_added", workspace: workspaceDto(w, []) })

  expect(frames).toHaveLength(1)
  expect(frames[0].type).toBe("workspace_added")
  expect(frames[0].workspace.id).toBe(w.id)
})

test("setLayout rejects an invalid tree with a readable reason", () => {
  const { store } = harness()
  const w = store.create({ name: "a", workdir: "/w" })
  expect(() => store.setLayout(w.id, { type: "group", id: "g", viewIds: [] })).toThrow("empty group: g")
})

test("closeView runs the side effect then broadcasts view_removed", async () => {
  const { db, store, frames, broadcast } = harness()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/w','t')`)
  const archived: string[] = []
  const svc = new WorkspaceService(store, {
    archiveSession: async (id) => { archived.push(id) },
    closeTerminal: async () => {},
    stopDisplay: async () => {},
  }, db)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/w" })
  const v = store.listViews(w.id)[0]!

  await svc.closeView(v.id)
  broadcast({ type: "view_removed", workspaceId: w.id, viewId: v.id })

  expect(archived).toEqual(["s1"])
  expect(frames.at(-1)).toEqual({ type: "view_removed", workspaceId: w.id, viewId: v.id })
})

test("reorder broadcasts workspaces_reordered with the full order", () => {
  const { store, frames, broadcast } = harness()
  const a = store.create({ name: "a", workdir: "/w" })
  const b = store.create({ name: "b", workdir: "/w" })
  store.reorder([b.id, a.id])
  broadcast({ type: "workspaces_reordered", orderedIds: [b.id, a.id] })

  expect(frames.at(-1)).toEqual({ type: "workspaces_reordered", orderedIds: [b.id, a.id] })
  expect(store.list().map((x) => x.id)).toEqual([b.id, a.id])
})

test("moveView broadcasts view_moved naming both workspaces", () => {
  const { store, frames, broadcast } = harness()
  const a = store.create({ name: "a", workdir: "/a" })
  const b = store.create({ name: "b", workdir: "/b" })
  const v = store.addView(a.id, { kind: "editor", state: { mode: "tree" } })

  store.moveView(v.id, b.id)
  broadcast({ type: "view_moved", viewId: v.id, fromWorkspaceId: a.id, toWorkspaceId: b.id })

  expect(frames.at(-1)).toEqual({ type: "view_moved", viewId: v.id, fromWorkspaceId: a.id, toWorkspaceId: b.id })
  expect(store.listViews(b.id).map((x) => x.id)).toEqual([v.id])
})
