import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, rmSync, unlinkSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-pa-crud-${process.pid}.json`
const PORT = 18900 + Math.floor(Math.random() * 100)
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
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [
      { id: "pa-1", name: "pa-1", workdir: join(tmpRoot, "workspace", "pa-1"), mute: false, connected: true, agent: "claude" as const, role: "personal_assistant", isDefault: true },
      { id: "worker-1", name: "worker-1", workdir: join(tmpRoot, "project-a"), mute: false, connected: true, agent: "claude" as const, role: "worker" },
    ],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    spawnPA: async (args: any) => {
      spawnPACalls.push(args)
      return { id: "new-pa-id", name: args.name, workdir: args.workdir, agent: args.agent ?? "claude", model: args.model, reasoningLevel: args.reasoningLevel }
    },
    listPAs: () => [
      { id: "pa-1", name: "pa-1", workdir: join(tmpRoot, "workspace", "pa-1"), mute: false, connected: true, agent: "claude" as const, role: "personal_assistant", isDefault: true },
    ],
    updatePA: async (name: string, patch: any) => {
      updatePACalls.push({ name, patch })
      return { ok: true }
    },
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
  expect(spawnPACalls[0].workdir).toBe(join(tmpRoot, ".mux", "workspace", "my-pa"))
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
  expect(spawnPACalls[0].agent).toBe("codex")
  expect(spawnPACalls[0].model).toBe("gpt-4")
  expect(spawnPACalls[0].reasoningLevel).toBe("high")
})

test("POST /api/pas writes focus.md when focusText is provided", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pas`, {
    method: "POST",
    headers: authed(),
    body: JSON.stringify({ name: "focused-pa", focusText: "Build a web app" }),
  })
  expect(res.status).toBe(200)
  const focusPath = join(tmpRoot, ".mux", "workspace", "focused-pa", "focus.md")
  expect(existsSync(focusPath)).toBe(true)
  expect(await Bun.file(focusPath).text()).toBe("Build a web app")
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

test("PATCH /api/pas/:name ignores other fields", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pas/pa-1`, {
    method: "PATCH",
    headers: authed(),
    body: JSON.stringify({ model: "claude-3-opus", name: "hacked", workdir: "/tmp" }),
  })
  expect(res.status).toBe(200)
  expect(updatePACalls[0]!.patch).toEqual({ model: "claude-3-opus" })
})

test("PATCH /api/pas/:name without auth → 401", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pas/pa-1`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ model: "x" }),
  })
  expect(res.status).toBe(401)
})
