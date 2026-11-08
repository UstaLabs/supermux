// #105: a workspace's Browser tab is a view of kind "browser" whose state is the page it shows: {url} (http/https
// only), {doc} (#104's agent page), or {} (a new tab). The broker refuses anything else, so a stored javascript: or
// file: URL can never reach a client.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { WorkspaceStore } from "../../core/workspace/store"
import { WorkspaceService } from "../../core/workspace/service"
import { viewDto, workspaceDto } from "../../core/workspace/dto"
import { browserStateError } from "../../core/workspace/browser-state"
import { WebChannel } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function boot() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new WorkspaceStore(db)
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-browser-view-")), "devices.json")
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

test("a Browser tab is stored with its page, a doc, or nothing yet", async () => {
  const { store, call, workspace } = await boot()
  for (const state of [{ url: "https://vitejs.dev/guide/" }, { url: "http://localhost:5173/login" }, { doc: "h_7f3a" }, {}]) {
    const res = await call(`/workspaces/${workspace.id}/views`, "POST", { kind: "browser", state, title: "t" })
    expect(res.status).toBe(200)
    const view = await res.json() as { id: string; kind: string }
    expect(view.kind).toBe("browser")
    expect(store.getView(view.id)!.state).toEqual(state)
  }
})

test("a Browser tab refuses any page that isn't http or https, and anything else in its state", async () => {
  const { call, workspace } = await boot()
  for (const state of [
    { url: "javascript:alert(1)" }, { url: "file:///etc/passwd" }, { url: "data:text/html,<p>x" }, { url: "ftp://x.test/" },
    { url: "not a url" }, { url: `https://x.test/${"a".repeat(4100)}` }, { url: "https://x.test/", doc: "h_1" },
    { doc: "../../etc" }, { doc: "" }, { url: 42 }, { url: "https://x.test/", extra: true }, "https://x.test/",
  ]) {
    const res = await call(`/workspaces/${workspace.id}/views`, "POST", { kind: "browser", state })
    expect({ state, status: res.status }).toEqual({ state, status: 400 })
  }
})

test("navigating a Browser tab updates its page, under the same rule", async () => {
  const { store, call, workspace } = await boot()
  const view = await (await call(`/workspaces/${workspace.id}/views`, "POST", { kind: "browser", state: {} })).json() as { id: string }
  const go = (state: unknown) => call(`/workspaces/${workspace.id}/views/${view.id}`, "PATCH", { state })
  expect((await go({ url: "https://github.com/UstaLabs/supermux/pull/412" })).status).toBe(200)
  expect(store.getView(view.id)!.state).toEqual({ url: "https://github.com/UstaLabs/supermux/pull/412" })
  expect((await go({ url: "javascript:fetch('/me')" })).status).toBe(400)
  expect(store.getView(view.id)!.state).toEqual({ url: "https://github.com/UstaLabs/supermux/pull/412" })
})

test("closing a Browser tab stops nothing on the host", async () => {
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
  const v = store.addView(w.id, { kind: "browser", state: { url: "https://x.test/" } })
  await svc.closeView(v.id)
  expect(store.getView(v.id)).toBeUndefined()
  expect(calls).toEqual([])
})

test("browserStateError explains the refusal", () => {
  expect(browserStateError({ url: "https://x.test/" })).toBeUndefined()
  expect(browserStateError({ url: "javascript:x" })).toContain("http")
  expect(browserStateError({ url: "https://x.test/", doc: "a" })).toContain("both")
})
