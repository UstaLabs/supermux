import { afterEach, expect, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createAgent, createAgentFromTool, validateAgentName, type CreateAgentDeps } from "./create-agent"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

function deps(over: Partial<CreateAgentDeps> = {}) {
  const root = mkdtempSync(join(tmpdir(), "create-agent-")); dirs.push(root)
  const spawned: any[] = []
  const announced: string[] = []
  const d: CreateAgentDeps = {
    muxHome: join(root, "mux"),
    takenNames: () => new Set(["taken"]),
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
