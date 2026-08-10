import { test, expect, afterEach } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { WorkspaceStore } from "../../core/workspace/store"
import { WorkspaceService } from "../../core/workspace/service"
import { workspaceDto, viewDto } from "../../core/workspace/dto"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

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

// ── Regression: /workspaces must be an API path, not an SPA route ────────────
// Every GET under /workspaces fell through to the single-page-app document
// handler and returned index.html, because API_PREFIXES never learned about the
// new routes. The sidebar still worked (it is fed by the WS snapshot frame), so
// the only visible symptom was an editor tab that showed nothing — its
// workspaceFsList call was parsing HTML.
//
// POST/PATCH/DELETE were unaffected (the SPA fallback is GET-only), which is why
// creating and archiving worked while reading did not.

import { isApiPath } from "./index"

test("workspace REST paths are treated as API, not as SPA routes", () => {
  expect(isApiPath("/workspaces")).toBe(true)
  expect(isApiPath("/workspaces/abc-123")).toBe(true)
  expect(isApiPath("/workspaces/abc-123/fs")).toBe(true)
  expect(isApiPath("/workspaces/abc-123/fs/read")).toBe(true)
  expect(isApiPath("/workspaces/abc-123/views")).toBe(true)
  expect(isApiPath("/views/v-1/move")).toBe(true)
})

test("an unrelated top-level path is still an SPA route", () => {
  expect(isApiPath("/somewhere-else")).toBe(false)
})

// ── POST /workspaces/:id/views: client-minted ids ────────────────────────────
// A later phase makes every open editor tab a view, and the tab has to appear
// the instant the file opens — before the broker round-trip completes. That
// only works if the client can mint the id itself and the broker just honours
// it. These go through the real HTTP route (not just the opts layer above)
// because the id validation and the duplicate check live in the route body.

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

function viewsHarness() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new WorkspaceStore(db)
  const frames: any[] = []
  const dir = mkdtempSync(join(tmpdir(), "mux-workspace-views-"))
  const devicesFile = join(dir, "devices.json")
  const opts: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getWorkspace: (id) => {
      const ws = store.getById(id)
      return ws ? workspaceDto(ws, store.listViews(id)) : undefined
    },
    addWorkspaceView: (workspaceId, args) => viewDto(store.addView(workspaceId, args as any)),
    getWorkspaceView: (viewId) => {
      const v = store.getView(viewId)
      return v ? viewDto(v) : undefined
    },
  }
  channel = new WebChannel(opts)
  return { store, frames, devicesFile }
}

async function authedFetch(devicesFile: string, path: string, init?: RequestInit) {
  const token = new DeviceStore(devicesFile).mint("test-device").token
  return fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, {
    ...init,
    headers: { ...(init?.headers ?? {}), authorization: `Bearer ${token}` },
  })
}

test("POST /workspaces/:id/views honours a client-supplied id", async () => {
  const { store, devicesFile } = viewsHarness()
  await channel!.start()
  const w = store.create({ name: "a", workdir: "/w" })
  const mintedId = "9c3f6d2a-1e4b-4a7c-8f2d-6b1a2c3d4e5f"

  const res = await authedFetch(devicesFile, `/workspaces/${w.id}/views`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ id: mintedId, kind: "editor", state: { mode: "tree" } }),
  })

  expect(res.status).toBe(200)
  const body = await res.json() as { id: string }
  expect(body.id).toBe(mintedId)
  expect(store.getView(mintedId)?.id).toBe(mintedId)
})

test("POST /workspaces/:id/views rejects a malformed id with 400", async () => {
  const { store, devicesFile } = viewsHarness()
  await channel!.start()
  const w = store.create({ name: "a", workdir: "/w" })

  const res = await authedFetch(devicesFile, `/workspaces/${w.id}/views`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ id: "not-a-uuid", kind: "editor", state: { mode: "tree" } }),
  })

  expect(res.status).toBe(400)
})

test("POST /workspaces/:id/views rejects a duplicate id with 409", async () => {
  const { store, devicesFile } = viewsHarness()
  await channel!.start()
  const w = store.create({ name: "a", workdir: "/w" })
  const existing = store.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })

  const res = await authedFetch(devicesFile, `/workspaces/${w.id}/views`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ id: existing.id, kind: "editor", state: { mode: "tree" } }),
  })

  expect(res.status).toBe(409)
})

test("POST /workspaces/:id/views still mints an id when the client omits one", async () => {
  const { store, devicesFile } = viewsHarness()
  await channel!.start()
  const w = store.create({ name: "a", workdir: "/w" })

  const res = await authedFetch(devicesFile, `/workspaces/${w.id}/views`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ kind: "editor", state: { mode: "tree" } }),
  })

  expect(res.status).toBe(200)
  const body = await res.json() as { id: string }
  expect(typeof body.id).toBe("string")
  expect(body.id.length).toBeGreaterThan(0)
  expect(store.getView(body.id)).toBeDefined()
})
