import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, rmSync, unlinkSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-pa-crud-${process.pid}.json`
let PORT = 0
let ch: WebChannel
let token: string
let spawnPACalls: any[] = []
let updatePACalls: Array<{ name: string; patch: any }> = []
let tmpRoot: string
let oldHome: string | undefined

beforeEach(async () => {
  __resetAuthFailures()
  spawnPACalls = []
  updatePACalls = []
  tmpRoot = mkdtempSync(join(tmpdir(), "mux-web-pa-"))
  oldHome = process.env.HOME
  process.env.HOME = tmpRoot
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const store = new DeviceStore(DEV_PATH)
  token = store.mint("test-device").token
  ch = new WebChannel({
    port: 0,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1",
    getSessionsSnapshot: () => [
      { id: "pa-1", name: "pa-1", workdir: join(tmpRoot, "workspace", "pa-1"), mute: false, connected: true, agent: "claude" as const, role: "personal_assistant", isDefault: true },
      { id: "worker-1", name: "worker-1", workdir: join(tmpRoot, "project-a"), mute: false, connected: true, agent: "claude" as const, role: "worker" },
    ],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    createAgent: async (args: any) => {
      spawnPACalls.push(args)
      if (args.name.includes("/") || args.name.startsWith(".")) throw Object.assign(new Error("bad name"), { status: 400, code: "name_invalid" })
      if (args.name === "pa-1") throw Object.assign(new Error("name already in use: pa-1"), { status: 409, code: "name_taken" })
      if (!args.name.trim()) throw Object.assign(new Error("name required"), { status: 400, code: "name_invalid" })
      return { id: "new-pa-id", name: args.name, workdir: join(tmpRoot, ".mux", "workspace", args.name), provider: args.provider ?? "claude" }
    },
    listPAs: () => [
      { id: "pa-1", name: "pa-1", workdir: join(tmpRoot, "workspace", "pa-1"), mute: false, connected: true, agent: "claude" as const, role: "personal_assistant", isDefault: true },
    ],
    updatePA: async (key: string, patch: any) => {
      updatePACalls.push({ name: key, patch })
      if (key === "ghost") return { ok: false, status: 404, code: "not_found", error: "agent not found" }
      if (patch.name === "pa-2") return { ok: false, status: 409, code: "name_taken", error: "name already in use: pa-2" }
      return { ok: true }
    },
  } as any)
  // port 0 = OS-assigned, read back off the channel. A random pick from a
  // fixed range collides under a full `bun test` run (two of these files even
  // shared the 18900+ range) and fails with EADDRINUSE for reasons unrelated to
  // the code under test.
  await ch.start()
  PORT = ch.boundPort
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

// ── POST /api/pas ───────────────────────────────────────────────────────

test("POST /api/pas creates a PA with auto-generated workdir", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pas`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({ name: "my-pa" }),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.id).toBe("new-pa-id")
  expect(body.name).toBe("my-pa")
  expect(body.workdir).toBe(join(tmpRoot, ".mux", "workspace", "my-pa"))
  expect(spawnPACalls).toHaveLength(1)
  expect(spawnPACalls[0].name).toBe("my-pa")
})

test("POST /api/pas accepts optional agent, model, reasoningLevel", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pas`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({ name: "codex-pa", agent: "codex", model: "gpt-4", reasoningLevel: "high" }),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.agent).toBe("codex")
  expect(body.model).toBe("gpt-4")
  expect(body.reasoningLevel).toBe("high")
  expect(spawnPACalls[0].provider).toBe("codex")
  expect(spawnPACalls[0].model).toBe("gpt-4")
  expect(spawnPACalls[0].reasoningLevel).toBe("high")
})

test("POST /api/pas without name → 400", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pas`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({}),
  })
  expect(res.status).toBe(400)
})

test("POST /api/pas without auth → 401", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pas`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ name: "x" }),
  })
  expect(res.status).toBe(401)
})

for (const base of ["/api/pas", "/api/agents"]) {
  test(`POST ${base} with a path-like name → 400 and nothing on disk`, async () => {
    const res = await fetch(`http://127.0.0.1:${PORT}${base}`, { method: "POST", headers: authed(), body: JSON.stringify({ name: "../../.ssh" }) })
    expect(res.status).toBe(400)
    expect((await res.json() as any).code).toBe("name_invalid")
    expect(existsSync(join(tmpRoot, ".ssh"))).toBe(false)
  })
  test(`POST ${base} with a taken name → 409, focusText ignored`, async () => {
    const res = await fetch(`http://127.0.0.1:${PORT}${base}`, { method: "POST", headers: authed(), body: JSON.stringify({ name: "pa-1", focusText: "x" }) })
    expect(res.status).toBe(409)
    expect((await res.json() as any).code).toBe("name_taken")
    expect(existsSync(join(tmpRoot, ".mux", "workspace", "pa-1", "focus.md"))).toBe(false)
  })
  test(`GET ${base} lists Agents under both keys`, async () => {
    const res = await fetch(`http://127.0.0.1:${PORT}${base}`, { headers: authed() })
    const body = await res.json() as any
    expect(body.pas).toHaveLength(1)
    expect(body.agents).toEqual(body.pas)
  })
}

test("POST /api/agents accepts provider (and agent for old clients)", async () => {
  await fetch(`http://127.0.0.1:${PORT}/api/agents`, { method: "POST", headers: authed(), body: JSON.stringify({ name: "a", provider: "codex" }) })
  await fetch(`http://127.0.0.1:${PORT}/api/pas`, { method: "POST", headers: authed(), body: JSON.stringify({ name: "b", agent: "codex" }) })
  expect(spawnPACalls.map(c => c.provider)).toEqual(["codex", "codex"])
})

test("POST /api/agents with an unknown provider → 400 provider_invalid", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/agents`, { method: "POST", headers: authed(), body: JSON.stringify({ name: "a", provider: "nope" }) })
  expect(res.status).toBe(400)
  expect((await res.json() as any).code).toBe("provider_invalid")
  expect(spawnPACalls).toEqual([])
})

// ── GET /api/pas ────────────────────────────────────────────────────────

test("GET /api/pas returns only personal_assistant sessions", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pas`, {
    headers: authed(),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.pas).toHaveLength(1)
  expect(body.pas[0].name).toBe("pa-1")
  expect(body.pas[0].role).toBe("personal_assistant")
})

test("GET /api/pas without auth → 401", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pas`)
  expect(res.status).toBe(401)
})

// ── PATCH /api/pas/:name ──────────────────────────────────────────────

test("PATCH /api/pas/:name updates model and reasoningLevel", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pas/pa-1`, {
    method: "PATCH",
    headers: authed(),
    body: JSON.stringify({ model: "claude-3-opus", reasoningLevel: "high" }),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.ok).toBe(true)
  expect(updatePACalls).toHaveLength(1)
  expect(updatePACalls[0]!.name).toBe("pa-1")
  expect(updatePACalls[0]!.patch).toEqual({ model: "claude-3-opus", reasoningLevel: "high" })
})

test("PATCH passes isDefault and name through, ignores other fields", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pas/pa-1`, {
    method: "PATCH", headers: authed(),
    body: JSON.stringify({ model: "claude-3-opus", name: "Kahya", isDefault: true, workdir: "/tmp" }),
  })
  expect(res.status).toBe(200)
  expect(updatePACalls[0]!.patch).toEqual({ model: "claude-3-opus", name: "Kahya", isDefault: true })
})

test("PATCH only accepts isDefault: true (false is ignored)", async () => {
  await fetch(`http://127.0.0.1:${PORT}/api/agents/pa-1`, { method: "PATCH", headers: authed(), body: JSON.stringify({ isDefault: false }) })
  expect(updatePACalls[0]!.patch).toEqual({})
})

for (const base of ["/api/pas", "/api/agents"]) {
  test(`PATCH ${base}/<unknown> → 404 not_found`, async () => {
    const res = await fetch(`http://127.0.0.1:${PORT}${base}/ghost`, { method: "PATCH", headers: authed(), body: JSON.stringify({ isDefault: true }) })
    expect(res.status).toBe(404)
    expect((await res.json() as any).code).toBe("not_found")
  })
  test(`PATCH ${base} rename to a taken name → 409 name_taken`, async () => {
    const res = await fetch(`http://127.0.0.1:${PORT}${base}/pa-1`, { method: "PATCH", headers: authed(), body: JSON.stringify({ name: "pa-2" }) })
    expect(res.status).toBe(409)
    expect((await res.json() as any).code).toBe("name_taken")
  })
}

test("PATCH /api/pas/:name without auth → 401", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pas/pa-1`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ model: "x" }),
  })
  expect(res.status).toBe(401)
})

test("/api/agents without auth → 401", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/agents`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ name: "x" }) })
  expect(res.status).toBe(401)
  expect(spawnPACalls).toEqual([])
})
