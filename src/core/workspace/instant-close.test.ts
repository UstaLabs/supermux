// #151 (Ahmet): closing a tab took 1–2 s because the broker answered only once the agent process had died. Now the
// view leaves the workspace at once and the work behind it (archive the chat, kill the terminal, stop the display)
// follows in the background; a slow or failing end is logged, and never brings the tab back.
import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "./../storage/migrations"
import { WorkspaceStore } from "./store"
import { WorkspaceService, type WorkspaceDeps, type ViewClosed } from "./service"

function make(archiveSession: WorkspaceDeps["archiveSession"]) {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new WorkspaceStore(db)
  const closed: ViewClosed[] = []
  const svc = new WorkspaceService(store, {
    archiveSession, resumeSession: async () => {},
    closeTerminal: async () => {}, closeTerminalScope: async () => {}, stopDisplay: async () => {},
    onViewClosed: (e) => { closed.push(e) },
  }, db)
  const w = store.create({ name: "a", workdir: "/w" })
  const v = store.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  return { store, svc, v, closed }
}

test("the view is gone before the agent has stopped", async () => {
  let release!: () => void
  const killed = new Promise<void>((r) => { release = r })
  const archived: string[] = []
  const { store, svc, v, closed } = make(async (id) => { archived.push(id); await killed })

  const work = svc.beginClose(v.id)!.work

  expect(store.getView(v.id)).toBeUndefined()
  expect(archived).toEqual(["s1"])
  expect(closed).toEqual([])
  release()
  await work
  expect(closed).toEqual([expect.objectContaining({ kind: "chat", ok: true })])
  expect(closed[0]!.killMs).toBeGreaterThanOrEqual(0)
  expect(closed[0]!.totalMs).toBeGreaterThanOrEqual(closed[0]!.killMs)
})

test("a failed stop is logged, and the tab stays closed", async () => {
  const { store, svc, v, closed } = make(async () => { throw new Error("agent did not exit") })

  await svc.beginClose(v.id)!.work

  expect(store.getView(v.id)).toBeUndefined()
  expect(closed).toEqual([expect.objectContaining({ kind: "chat", ok: false, error: "agent did not exit" })])
})

test("closing an unknown view does nothing", () => {
  const { svc } = make(async () => {})
  expect(svc.beginClose("nope")).toBeUndefined()
})
