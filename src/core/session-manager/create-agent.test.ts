import { afterEach, expect, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createAgent, createAgentFromTool, updateAgent, validateAgentName, type CreateAgentDeps, type UpdateAgentDeps } from "./create-agent"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

function deps(over: Partial<CreateAgentDeps> = {}) {
  const root = mkdtempSync(join(tmpdir(), "create-agent-")); dirs.push(root)
  const spawned: any[] = []
  const announced: string[] = []
  const d: CreateAgentDeps = {
    muxHome: join(root, "mux"),
    takenNames: () => new Set(["taken"]),
    workdirInUse: () => false,
    assertInstalled: () => {},
    spawn: async (a) => { spawned.push(a); return { id: a.id, name: a.name } },
    announce: async (id) => { announced.push(id) },
    ...over,
  }
  return { d, spawned, announced, root }
}

test("validateAgentName refuses path tricks, empty and taken names", () => {
  const taken = new Set(["Dockie"])
  for (const bad of ["", "  ", "../x", "a/b", "a\\b", ".", "..", ".hidden", "Dockie", "x".repeat(81)]) {
    expect(validateAgentName(bad, taken).ok).toBe(false)
  }
  expect(validateAgentName("  Kahya  ", taken)).toEqual({ ok: true, name: "Kahya" })
  expect(validateAgentName("travel assistant", taken)).toEqual({ ok: true, name: "travel assistant" })
})

test("createAgent: workdir, both memory files before the spawn, spawn with that id, then announce", async () => {
  const { d, spawned, announced } = deps({
    spawn: async (a) => {
      // The memory files exist before the agent's first prepare.
      expect(readFileSync(join(d.muxHome, "agent-memory", `${a.id}.md`), "utf8")).toBe("")
      spawned.push(a)
      return { id: a.id, name: a.name }
    },
  })
  const r = await createAgent(d, { name: "Dockie", provider: "claude", model: "opus" })
  expect(r.name).toBe("Dockie")
  expect(spawned[0]).toMatchObject({ name: "Dockie", provider: "claude", model: "opus", workdir: join(d.muxHome, "workspace", "Dockie"), id: r.id })
  expect(existsSync(join(d.muxHome, "workspace", "Dockie"))).toBe(true)
  expect(existsSync(join(d.muxHome, "agent-memory", `${r.id}.digest.md`))).toBe(true)
  expect(announced).toEqual([r.id])
})

test("a bad name touches nothing on disk", async () => {
  const { d, spawned } = deps()
  await expect(createAgent(d, { name: "../../.ssh", provider: "claude" })).rejects.toMatchObject({ status: 400 })
  expect(existsSync(d.muxHome)).toBe(false)
  expect(spawned).toEqual([])
})

test("a taken name is a 409 and never overwrites that agent's files", async () => {
  const { d } = deps()
  mkdirSync(join(d.muxHome, "workspace", "taken"), { recursive: true })
  writeFileSync(join(d.muxHome, "workspace", "taken", "notes.md"), "keep")
  await expect(createAgent(d, { name: "taken", provider: "claude" })).rejects.toMatchObject({ status: 409, code: "name_taken" })
  expect(readFileSync(join(d.muxHome, "workspace", "taken", "notes.md"), "utf8")).toBe("keep")
})

test("a folder that is still another live session's workdir is refused (rename freed the name, not the folder)", async () => {
  const { d, spawned } = deps()
  const liveDir = join(d.muxHome, "workspace", "Ada")
  mkdirSync(liveDir, { recursive: true })
  writeFileSync(join(liveDir, "notes.md"), "Bob's")
  d.workdirInUse = (dir) => dir === liveDir
  await expect(createAgent(d, { name: "Ada", provider: "claude" })).rejects.toMatchObject({ status: 409, code: "name_taken", message: expect.stringContaining("belongs to another session") })
  expect(spawned).toEqual([])
  expect(readFileSync(join(liveDir, "notes.md"), "utf8")).toBe("Bob's")
})

test("a folder left only by archived sessions (or nobody) is reused", async () => {
  const { d, spawned } = deps({ workdirInUse: () => false })
  mkdirSync(join(d.muxHome, "workspace", "Old"), { recursive: true })
  writeFileSync(join(d.muxHome, "workspace", "Old", "notes.md"), "keep")
  await createAgent(d, { name: "Old", provider: "claude" })
  expect(spawned).toHaveLength(1)
  expect(readFileSync(join(d.muxHome, "workspace", "Old", "notes.md"), "utf8")).toBe("keep")
})

test("a missing provider fails before any folder is made", async () => {
  const err = Object.assign(new Error("codex is not installed"), { status: 409, code: "agent_not_installed" })
  const { d } = deps({ assertInstalled: () => { throw err } })
  await expect(createAgent(d, { name: "Coder", provider: "codex" })).rejects.toBe(err)
  expect(existsSync(join(d.muxHome, "workspace", "Coder"))).toBe(false)
})

test("a failed spawn removes the folder and memory files it made", async () => {
  const { d } = deps({ spawn: async () => { throw new Error("boom") } })
  await expect(createAgent(d, { name: "Flaky", provider: "claude" })).rejects.toThrow("boom")
  expect(existsSync(join(d.muxHome, "workspace", "Flaky"))).toBe(false)
  expect(existsSync(join(d.muxHome, "agent-memory"))).toBe(true) // folder may stay; files must not
  expect(require("fs").readdirSync(join(d.muxHome, "agent-memory"))).toEqual([])
})

test("create_agent is refused to workers and accepted from Agents", async () => {
  const { d } = deps()
  const worker = { role: "worker" as const }
  const agent = { role: "personal_assistant" as const }
  expect(await createAgentFromTool(worker, { name: "X", provider: "claude" }, d)).toEqual({ ok: false, error: "only agents can create agents" })
  const ok = await createAgentFromTool(agent, { name: "X", provider: "claude" }, d)
  expect(ok.ok).toBe(true)
  expect(await createAgentFromTool(agent, { name: "Y", provider: "nope" }, d)).toEqual({ ok: false, error: "unknown provider: nope" })
})

function updateDeps(over: Partial<UpdateAgentDeps> = {}) {
  const rows = [
    { id: "a1", name: "Ada", role: "personal_assistant" },
    { id: "a2", name: "Bob", role: "personal_assistant" },
    { id: "w1", name: "work", role: "worker" },
  ]
  const calls: string[] = []
  const d: UpdateAgentDeps = {
    find: (key) => rows.find(r => r.id === key) ?? rows.find(r => r.name === key),
    takenNames: () => new Set(rows.map(r => r.name)),
    setDefault: (id) => { calls.push(`default:${id}`); return true },
    rename: async (id, name) => { calls.push(`rename:${id}:${name}`) },
    switchModel: async (id, m) => { calls.push(`model:${id}:${m}`); return { ok: true } },
    switchReasoning: async (id, r) => { calls.push(`reasoning:${id}:${r}`); return { ok: true } },
    ...over,
  }
  return { d, calls }
}

test("updateAgent finds the Agent by id or by name", async () => {
  const { d, calls } = updateDeps()
  expect(await updateAgent(d, "a2", { isDefault: true })).toEqual({ ok: true })
  expect(await updateAgent(d, "Bob", { isDefault: true })).toEqual({ ok: true })
  expect(calls).toEqual(["default:a2", "default:a2"])
})

test("updateAgent: unknown → 404 not_found, worker → 400 not_an_agent, nothing applied", async () => {
  const { d, calls } = updateDeps()
  expect(await updateAgent(d, "ghost", { isDefault: true })).toMatchObject({ ok: false, status: 404, code: "not_found" })
  expect(await updateAgent(d, "w1", { name: "x" })).toMatchObject({ ok: false, status: 400, code: "not_an_agent" })
  expect(calls).toEqual([])
})

test("updateAgent validates a new name before changing anything", async () => {
  const { d, calls } = updateDeps()
  expect(await updateAgent(d, "a1", { name: "../x", model: "m" })).toMatchObject({ ok: false, status: 400, code: "name_invalid" })
  expect(await updateAgent(d, "a1", { name: "Bob" })).toMatchObject({ ok: false, status: 409, code: "name_taken" })
  expect(calls).toEqual([])
})

test("updateAgent renames (trimmed), keeps its own name as a no-op, and applies every field", async () => {
  const { d, calls } = updateDeps()
  expect(await updateAgent(d, "a1", { name: "Ada" })).toEqual({ ok: true })
  expect(await updateAgent(d, "a1", { name: "  Kahya ", model: "m", reasoningLevel: "high", isDefault: true })).toEqual({ ok: true })
  expect(calls).toEqual(["model:a1:m", "reasoning:a1:high", "rename:a1:Kahya", "default:a1"])
})

test("updateAgent passes a failed model switch through as 400", async () => {
  const { d } = updateDeps({ switchModel: async () => ({ ok: false, error: "unknown model" }) })
  expect(await updateAgent(d, "a1", { model: "zzz" })).toMatchObject({ ok: false, status: 400, error: "unknown model" })
})
