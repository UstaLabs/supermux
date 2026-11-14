// #135: GET /workspaces/:id/closed-sessions lists the sessions closed in a workspace, newest first, and
// POST /workspaces/:id/closed-sessions/:sessionId/reopen brings one back as a chat tab in the pane asked for.
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
  const resumed: string[] = []
  const svc = new WorkspaceService(store, {
    archiveSession: async () => {}, resumeSession: async (id) => { resumed.push(id) },
    closeTerminal: async () => {}, closeTerminalScope: async () => {}, stopDisplay: async () => {},
  }, db)
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-closed-sessions-")), "devices.json")
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    getWorkspace: (id) => { const ws = store.getById(id); return ws ? workspaceDto(ws, store.listViews(id)) : undefined },
    getClosedSessions: (workspaceId) => store.closedSessions(workspaceId),
    reopenClosedSession: async (workspaceId, sessionId, groupId) => viewDto(await svc.reopenClosedSession(workspaceId, sessionId, groupId)),
  })
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("t").token
  const call = (path: string, method: string, body?: unknown) => fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, {
    method, headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body),
  })
  const w = store.create({ name: "a", workdir: "/w" })
  db.run("INSERT INTO sessions (id, name, status, agent, workdir, created_at, workspace_id, killed_at) VALUES ('c1','Fix login','archived','claude','/w','t',?, '2026-11-12T10:00:00Z')", [w.id])
  return { store, call, w, resumed }
}

test("the closed sessions of a workspace are listed", async () => {
  const { call, w } = await boot()
  const res = await call(`/workspaces/${w.id}/closed-sessions`, "GET")
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ sessions: [{ id: "c1", name: "Fix login", agent: "claude", closedAt: "2026-11-12T10:00:00Z" }] })
})

test("reopening one resumes it and answers with its new tab", async () => {
  const { store, call, w, resumed } = await boot()
  const group = (store.getById(w.id)!.layout as { id: string }).id
  const res = await call(`/workspaces/${w.id}/closed-sessions/c1/reopen`, "POST", { groupId: group })
  expect(res.status).toBe(200)
  const view = await res.json() as { kind: string; state: unknown }
  expect(view.kind).toBe("chat")
  expect(view.state).toEqual({ sessionId: "c1" })
  expect(resumed).toEqual(["c1"])
})

test("a session not closed here is refused", async () => {
  const { call, w } = await boot()
  expect((await call(`/workspaces/${w.id}/closed-sessions/nope/reopen`, "POST", {})).status).toBe(400)
})
