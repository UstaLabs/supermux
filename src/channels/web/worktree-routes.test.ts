// Integration tests for the explicit-worktree-cleanup routes (mirrors
// usage-route.test.ts / reasoning-levels-route.test.ts): GET /worktrees,
// GET /worktrees/:id/changes, GET /worktrees/by-workdir, DELETE /worktrees,
// and the repeatable deleteWorktree=<worktreeId> param on the session/workspace/view archive
// routes (delete exactly the ids the user confirmed in the dialog, after the archive).
// Spec: docs/superpowers/specs/2026-09-22-explicit-worktree-cleanup-design.md §3.3
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })
function mintToken(devicesFile: string): string { return new DeviceStore(devicesFile).mint("test-device").token }

function makeChannel(opts: Partial<WebChannelOpts> = {}): { channel: WebChannel; devicesFile: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-worktree-routes-"))
  const devicesFile = join(dir, "devices.json")
  const full: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    ...opts,
  }
  return { channel: new WebChannel(full), devicesFile }
}

function fakeWorktrees() {
  const calls: string[] = []
  return {
    calls,
    api: {
      root: () => "/r",
      list: async () => { calls.push("list"); return [{ id: "s/u", path: "/r/s/u", repoName: "r", owners: [], mtime: 0, uncommitted: 0, unmerged: 0, ignored: [], hasChanges: false }] },
      changes: async (id: string) => {
        calls.push(`changes:${id}`)
        if (id === "missing/id") throw new Error("not a worktree")
        return { id, files: [], commits: [], ignored: [], truncated: { files: 0, commits: 0, ignored: 0 } }
      },
      forWorkdir: async (w: string) => (w === "/r/s/u" ? { id: "s/u", owners: [], changes: { id: "s/u", files: [], commits: [], ignored: [], truncated: { files: 0, commits: 0, ignored: 0 } } } : undefined),
      remove: async (ids: string[]) => { calls.push(`remove:${ids.join(",")}`); return ids.map((id) => ({ id, ok: true })) },
    },
  }
}

async function request(ch: WebChannel, devicesFile: string, method: string, path: string, body?: unknown): Promise<Response> {
  const token = mintToken(devicesFile)
  return fetch(`http://127.0.0.1:${ch.boundPort}${path}`, {
    method,
    headers: {
      authorization: `Bearer ${token}`,
      ...(body !== undefined ? { "content-type": "application/json" } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
}

test("GET /worktrees lists", async () => {
  const w = fakeWorktrees()
  const made = makeChannel({ worktrees: w.api })
  channel = made.channel
  await channel.start()
  const res = await request(channel, made.devicesFile, "GET", "/worktrees")
  expect(res.status).toBe(200)
  const body = await res.json() as { root: string; worktrees: Array<{ id: string }> }
  expect(body.root).toBe("/r")
  expect(body.worktrees[0]!.id).toBe("s/u")
})

test("GET /worktrees/:id/changes decodes the slash in the id", async () => {
  const w = fakeWorktrees()
  const made = makeChannel({ worktrees: w.api })
  channel = made.channel
  await channel.start()
  const res = await request(channel, made.devicesFile, "GET", `/worktrees/${encodeURIComponent("s/u")}/changes`)
  expect(res.status).toBe(200)
  expect(w.calls).toContain("changes:s/u")
})

test("GET /worktrees/:id/changes 404s when the worktree is gone", async () => {
  const w = fakeWorktrees()
  const made = makeChannel({ worktrees: w.api })
  channel = made.channel
  await channel.start()
  const res = await request(channel, made.devicesFile, "GET", `/worktrees/${encodeURIComponent("missing/id")}/changes`)
  expect(res.status).toBe(404)
  expect((await res.json() as { error: string }).error).toBeTruthy()
})

test("GET /worktrees/by-workdir finds the worktree or 404s", async () => {
  const w = fakeWorktrees()
  const made = makeChannel({ worktrees: w.api })
  channel = made.channel
  await channel.start()
  const found = await request(channel, made.devicesFile, "GET", `/worktrees/by-workdir?path=${encodeURIComponent("/r/s/u")}`)
  expect(found.status).toBe(200)
  const missing = await request(channel, made.devicesFile, "GET", `/worktrees/by-workdir?path=${encodeURIComponent("/home/x")}`)
  expect(missing.status).toBe(404)
})

test("DELETE /worktrees passes ids", async () => {
  const w = fakeWorktrees()
  const made = makeChannel({ worktrees: w.api })
  channel = made.channel
  await channel.start()
  const res = await request(channel, made.devicesFile, "DELETE", "/worktrees", { ids: ["s/u"] })
  expect(res.status).toBe(200)
  expect((await res.json() as { results: unknown }).results).toEqual([{ id: "s/u", ok: true }])
})

test("GET /worktrees 503s when not configured", async () => {
  const made = makeChannel({})
  channel = made.channel
  await channel.start()
  const res = await request(channel, made.devicesFile, "GET", "/worktrees")
  expect(res.status).toBe(503)
})

test("DELETE /worktrees 500s with the error only when remove itself throws", async () => {
  const w = fakeWorktrees()
  const made = makeChannel({ worktrees: { ...w.api, remove: async () => { throw new Error("boom") } } })
  channel = made.channel
  await channel.start()
  const res = await request(channel, made.devicesFile, "DELETE", "/worktrees", { ids: ["s/u"] })
  expect(res.status).toBe(500)
  expect((await res.json() as { error: string }).error).toBe("boom")
})

/** The three archive routes, each with its archive fake and the name it records. */
const ARCHIVE_ROUTES = [
  { name: "session", path: "/sessions/abc", step: "kill", opts: (order: string[]) => ({ killSession: async () => { order.push("kill") } }) },
  { name: "workspace", path: "/workspaces/ws1", step: "archive", opts: (order: string[]) => ({ archiveWorkspace: async () => { order.push("archive") } }) },
  { name: "view", path: "/workspaces/ws1/views/v1", step: "close", opts: (order: string[]) => ({ closeWorkspaceView: async () => { order.push("close") } }) },
] as const

for (const r of ARCHIVE_ROUTES) {
  test(`DELETE ${r.name} archive without deleteWorktree stays 204 and deletes nothing (behaviour unchanged)`, async () => {
    const w = fakeWorktrees()
    const made = makeChannel({ worktrees: w.api, ...r.opts([]) })
    channel = made.channel
    await channel.start()
    const res = await request(channel, made.devicesFile, "DELETE", r.path)
    expect(res.status).toBe(204)
    expect(w.calls.some((c) => c.startsWith("remove"))).toBe(false)
  })

  test(`DELETE ${r.name} archive ?deleteWorktree=<id>&deleteWorktree=<id> archives THEN removes exactly those ids`, async () => {
    const w = fakeWorktrees()
    const order: string[] = []
    const seen: string[][] = []
    const made = makeChannel({
      worktrees: { ...w.api, remove: async (ids: string[]) => { order.push("remove"); seen.push(ids); return w.api.remove(ids) } },
      ...r.opts(order),
    })
    channel = made.channel
    await channel.start()
    const q = `deleteWorktree=${encodeURIComponent("s/u")}&deleteWorktree=${encodeURIComponent("t/v")}`
    const res = await request(channel, made.devicesFile, "DELETE", `${r.path}?${q}`)
    expect(res.status).toBe(200)
    expect(order).toEqual([r.step, "remove"])
    expect(seen).toEqual([["s/u", "t/v"]])
    expect((await res.json() as { worktree: unknown }).worktree).toEqual([{ id: "s/u", ok: true }, { id: "t/v", ok: true }])
  })

  test(`DELETE ${r.name} archive: a failed remove is reported per id, the archive stands`, async () => {
    const w = fakeWorktrees()
    const order: string[] = []
    const made = makeChannel({ worktrees: { ...w.api, remove: async () => { throw new Error("boom") } }, ...r.opts(order) })
    channel = made.channel
    await channel.start()
    const res = await request(channel, made.devicesFile, "DELETE", `${r.path}?deleteWorktree=${encodeURIComponent("s/u")}`)
    expect(res.status).toBe(200)
    expect(order).toEqual([r.step])
    expect((await res.json() as { worktree: unknown }).worktree).toEqual([{ id: "s/u", ok: false, error: "boom" }])
  })
}

test("GET /worktrees reaches the route even with a staticDir (not swallowed by the SPA fallback)", async () => {
  const w = fakeWorktrees()
  const staticDir = mkdtempSync(join(tmpdir(), "mux-worktree-static-"))
  const { writeFileSync } = await import("fs")
  writeFileSync(join(staticDir, "index.html"), "<!doctype html><html><body>SPA</body></html>")
  const made = makeChannel({ worktrees: w.api, staticDir })
  channel = made.channel
  await channel.start()
  const res = await request(channel, made.devicesFile, "GET", "/worktrees")
  expect(res.status).toBe(200)
  const body = await res.json() as { worktrees: unknown[] }
  expect(Array.isArray(body.worktrees)).toBe(true)
})
