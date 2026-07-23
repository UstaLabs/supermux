import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, rmSync, unlinkSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-crud-${process.pid}.json`
const PORT = 18800 + Math.floor(Math.random() * 100)
let ch: WebChannel
let token: string
let spawnCalls: any[] = []
let killCalls: string[] = []
let renameCalls: Array<{ old: string; new: string }> = []
let orderCalls: Array<{ id: string; order: number }> = []
let draftCalls: any[] = []
let tmpRoot: string
let oldHome: string | undefined

beforeEach(async () => {
  __resetAuthFailures()
  spawnCalls = []
  killCalls = []
  renameCalls = []
  orderCalls = []
  draftCalls = []
  tmpRoot = mkdtempSync(join(tmpdir(), "mux-web-session-"))
  oldHome = process.env.HOME
  process.env.HOME = tmpRoot
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const store = new DeviceStore(DEV_PATH)
  token = store.mint("test-device").token
  mkdirSync(join(tmpRoot, "project-a"))
  mkdirSync(join(tmpRoot, "project-b"))
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [{ id: "sess-a", name: "sess-a", workdir: join(tmpRoot, "project-a") + "/", mute: false, connected: true, agent: "claude" as const }],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    spawnSession: async (args: any) => {
      spawnCalls.push(args)
      return { id: "new-id", name: args.name ?? "derived-name", workdir: args.workdir, agent: args.agent ?? "claude", model: args.model }
    },
    createDraft: async (args: any) => {
      draftCalls.push(args)
      return { id: "draft-id", name: args.name ?? "draft-name", workdir: args.workdir, agent: args.agent ?? "claude" }
    },
    killSession: async (name: string) => { killCalls.push(name) },
    renameSession: async (oldName: string, newName: string) => { renameCalls.push({ old: oldName, new: newName }) },
    setSessionOrder: (id: string, order: number) => { orderCalls.push({ id, order }) },
    listArchivedSessions: () => [{ id: "archived-a", name: "archived-a", workdir: join(tmpRoot, "project-a"), agent: "claude" as const }],
  } as any)
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (oldHome === undefined) delete process.env.HOME
  else process.env.HOME = oldHome
  rmSync(tmpRoot, { recursive: true, force: true })
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

function authed(headers?: Record<string, string>) {
  return { Cookie: `cmux_token=${token}`, "content-type": "application/json", ...headers }
}

test("POST /sessions → spawns and returns session", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({ workdir: "~/project-b/", agent: "claude" }),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.id).toBe("new-id")
  expect(body.name).toBe("derived-name")
  expect(body.workdir).toBe(join(tmpRoot, "project-b"))
  expect(spawnCalls).toHaveLength(1)
  expect(spawnCalls[0].workdir).toBe(join(tmpRoot, "project-b"))
})

test("POST /sessions with userStatus:draft → creates draft, does not spawn", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({ workdir: "~/project-b/", agent: "claude", userStatus: "draft", draftPayload: { text: "hello" } }),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.id).toBe("draft-id")
  expect(body.name).toBe("draft-name")
  expect(body.workdir).toBe(join(tmpRoot, "project-b"))
  expect(draftCalls).toHaveLength(1)
  expect(draftCalls[0].workdir).toBe(join(tmpRoot, "project-b"))
  expect(draftCalls[0].draftPayload).toEqual({ text: "hello" })
  expect(spawnCalls).toHaveLength(0)
})

test("POST /sessions with userStatus:in_progress → spawns as usual", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({ workdir: "~/project-b/", agent: "claude", userStatus: "in_progress" }),
  })
  expect(res.status).toBe(200)
  expect(spawnCalls).toHaveLength(1)
  expect(draftCalls).toHaveLength(0)
})

test("POST /sessions without workdir → 400", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({}),
  })
  expect(res.status).toBe(400)
})

test("POST /sessions rejects a missing workdir before spawning", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({ workdir: "~/missing" }),
  })
  expect(res.status).toBe(400)
  const body = await res.json() as any
  expect(body.error).toMatch(/does not exist/)
  expect(spawnCalls).toHaveLength(0)
})

test("GET /projects returns unique known project paths", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/projects`, {
    headers: authed(),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.projects).toEqual([{ path: join(tmpRoot, "project-a") }])
})

test("POST /paths/validate resolves a valid typed path", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/paths/validate`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({ path: "~/project-b/" }),
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ ok: true, path: join(tmpRoot, "project-b") })
})

test("POST /paths/validate reports invalid paths without a 500", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/paths/validate`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({ path: "~/missing" }),
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ ok: false, error: `working directory does not exist: ${join(tmpRoot, "missing")}` })
})

test("POST /sessions without auth → 401", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ workdir: "/tmp" }),
  })
  expect(res.status).toBe(401)
})

test("DELETE /sessions/:name → kills and returns 204", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions/sess-a`, {
    method: "DELETE",
    headers: authed(),
  })
  expect(res.status).toBe(204)
  expect(killCalls).toEqual(["sess-a"])
})

test("DELETE /sessions/:name without auth → 401", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions/sess-a`, { method: "DELETE" })
  expect(res.status).toBe(401)
})

test("POST /sessions/:name/rename → renames", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions/sess-a/rename`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({ name: "better-name" }),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.ok).toBe(true)
  expect(renameCalls).toEqual([{ old: "sess-a", new: "better-name" }])
})

test("POST /sessions/:name/rename without name → 400", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions/sess-a/rename`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({}),
  })
  expect(res.status).toBe(400)
})

test("PATCH /sessions/:id { order } → sets sort order", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions/sess-a`, {
    method: "PATCH",
    headers: authed(),
    body: JSON.stringify({ order: 2 }),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.ok).toBe(true)
  expect(orderCalls).toEqual([{ id: "sess-a", order: 2 }])
})

test("PATCH /sessions/:id with non-number order → 400", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions/sess-a`, {
    method: "PATCH",
    headers: authed(),
    body: JSON.stringify({ order: "nope" }),
  })
  expect(res.status).toBe(400)
  expect(orderCalls).toHaveLength(0)
})
