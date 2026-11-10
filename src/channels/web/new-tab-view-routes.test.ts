// #91: a workspace's New tab is a view of kind "new" with nothing in it ({}), so closing, dragging, splitting,
// reordering and resizing it go through the normal view paths. Picking what it shows replaces it with a real view.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { WorkspaceStore } from "../../core/workspace/store"
import { WorkspaceService } from "../../core/workspace/service"
import { viewDto, workspaceDto } from "../../core/workspace/dto"
import { WebChannel } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function boot() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new WorkspaceStore(db)
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-new-tab-view-")), "devices.json")
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    getWorkspace: (id) => { const ws = store.getById(id); return ws ? workspaceDto(ws, store.listViews(id)) : undefined },
    addWorkspaceView: (workspaceId, args) => viewDto(store.addView(workspaceId, args as any)),
    getWorkspaceView: (viewId) => { const v = store.getView(viewId); return v ? viewDto(v) : undefined },
    patchWorkspaceView: (viewId, patch) => {
      if (patch.state !== undefined) store.setViewState(viewId, patch.state as any)
      return viewDto(store.getView(viewId)!)
    },
  })
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("t").token
  const call = (path: string, method: string, body: unknown) => fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, {
    method, headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: JSON.stringify(body),
  })
  return { store, call, workspace: store.create({ name: "a", workdir: "/w" }) }
}

test("a New tab is stored as a view of kind new, holding nothing", async () => {
  const { store, call, workspace } = await boot()
  const res = await call(`/workspaces/${workspace.id}/views`, "POST", { kind: "new", state: {} })
  expect(res.status).toBe(200)
  const view = await res.json() as { id: string; kind: string }
  expect(view.kind).toBe("new")
  expect(store.getView(view.id)!.state).toEqual({})
})

test("a New tab refuses any state but nothing", async () => {
  const { call, workspace } = await boot()
  for (const state of [{ url: "https://x.test/" }, { sessionId: "s1" }, "x", 1, []]) {
    const res = await call(`/workspaces/${workspace.id}/views`, "POST", { kind: "new", state })
    expect({ state, status: res.status }).toEqual({ state, status: 400 })
  }
})

test("closing a New tab stops nothing on the host", async () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new WorkspaceStore(db)
  const calls: string[] = []
  const svc = new WorkspaceService(store, {
    archiveSession: async () => { calls.push("archive") }, resumeSession: async () => {},
    closeTerminal: async () => { calls.push("terminal") }, closeTerminalScope: async () => {},
    stopDisplay: async () => { calls.push("display") },
  }, db)
  const w = store.create({ name: "a", workdir: "/w" })
  const v = store.addView(w.id, { kind: "new", state: {} })
  await svc.closeView(v.id)
  expect(store.getView(v.id)).toBeUndefined()
  expect(calls).toEqual([])
})
