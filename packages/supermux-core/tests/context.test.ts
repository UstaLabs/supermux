import { afterEach, expect, test } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from "node:fs"
import { readFile, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore, type Core } from "../src/core.js"
import { mergeContexts, normalizeContext, resolveContext } from "../src/context/index.js"
import type { DriverContextSupport, LaunchContext, SessionContext } from "../src/context/types.js"
import type { AgentDriver, CoreEvent, DriverContext } from "../src/types.js"
import { TEST_LIMITS } from "./helpers.js"

const dirs: string[] = []
const cores: Core[] = []
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
function scratch(): string { const dir = mkdtempSync(join(tmpdir(), "context-")); dirs.push(dir); return dir }

/** A skills folder, a plugin folder and a workdir under one scratch root. */
function fixtures() {
  const root = scratch()
  const cwd = join(root, "work"); mkdirSync(cwd)
  const skills = join(root, "skills"); mkdirSync(join(skills, "alpha"), { recursive: true })
  writeFileSync(join(skills, "alpha", "SKILL.md"), "---\nname: alpha\ndescription: a\n---\n")
  const plugin = join(root, "plugin"); mkdirSync(join(plugin, ".claude-plugin"), { recursive: true })
  writeFileSync(join(plugin, ".claude-plugin", "plugin.json"), JSON.stringify({ name: "p" }))
  const file = join(root, "file.txt"); writeFileSync(file, "x")
  return { root, cwd, skills, plugin, file }
}

const ALL_SUPPORTED: DriverContextSupport = {
  capabilities: {
    instructions: { support: "supported", note: "i" },
    skills: { support: "supported", note: "s" },
    plugins: { support: "supported", note: "p" },
    mcpServers: { support: "supported", note: "m" },
  },
}

type Opened = { context: DriverContext; sessionContext?: LaunchContext }

function fakeDriver(id: string, support: DriverContextSupport | undefined, opened: Opened[]): AgentDriver {
  let n = 0
  return {
    id,
    ...(support ? { context: support } : {}),
    async open(context) {
      const sessionContext = context.sessionContext
      opened.push({ context, ...(sessionContext ? { sessionContext: structuredClone(sessionContext) } : {}) })
      n++
      return {
        agentSessionId: context.resumeId ?? (context.forkFrom ? `fork-${n}` : `native-${n}`),
        capabilities: { resume: true, steer: false, fork: true, detach: false },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
    },
  }
}

function core(state: string, drivers: AgentDriver[], extra: { context?: SessionContext; contextPolicy?: "error" | "warn" } = {}) {
  const c = createCore({ stateDirectory: state, agents: drivers, limits: TEST_LIMITS, ...extra })
  cores.push(c)
  return c
}

// ------------------------------------------------------------ validation and merge

test("normalizeContext rejects malformed contexts with invalid_context", () => {
  const bad: unknown[] = [
    "x", [], { unknown: 1 }, { instructions: 3 }, { instructions: ["a", 2] }, { skills: ["relative/path"] },
    { plugins: "x" }, { mcpServers: [{ name: "bad name", command: "x" }] }, { mcpServers: [{ name: "ok", command: "" }] },
    { mcpServers: [{ name: "ok", command: "x", args: [1] }] }, { mcpServers: [{ name: "ok", command: "x", env: { A: 1 } }] },
    { mcpServers: [{ name: "ok", command: "x", url: "http://x" }] },
  ]
  for (const value of bad) {
    let error: unknown
    try { normalizeContext(value) } catch (caught) { error = caught }
    expect((error as { code?: string })?.code).toBe("invalid_context")
  }
  expect(normalizeContext(undefined)).toBeUndefined()
  expect(normalizeContext({ mcpServers: [{ name: "a-b_1", command: "x" }] })).toEqual({ mcpServers: [{ name: "a-b_1", command: "x", args: [], env: {} }] })
})

test("merge puts the core default first; resolve validates paths and MCP names", async () => {
  const f = fixtures()
  const merged = mergeContexts(
    { instructions: "core", skills: [f.skills], mcpServers: [{ name: "a", command: "x" }] },
    { instructions: ["one", "two"], plugins: [f.plugin], mcpServers: [{ name: "b", command: "y" }] },
  )
  expect(merged.instructions).toEqual(["core", "one", "two"])
  const resolved = await resolveContext(merged)
  expect(resolved.instructions).toBe("core\n\none\n\ntwo")
  expect(resolved.skills).toEqual([f.skills])
  expect(resolved.plugins).toEqual([f.plugin])
  expect(resolved.mcpServers.map(server => server.name)).toEqual(["a", "b"])
  for (const context of [
    { mcpServers: [{ name: "a", command: "x" }, { name: "a", command: "y" }] },
    { skills: [join(f.root, "missing")] },
    { plugins: [f.file] },
    { skills: [f.file] },
  ] as SessionContext[]) {
    await expect(resolveContext(context)).rejects.toMatchObject({ code: "invalid_context" })
  }
})

// ------------------------------------------------------------ core

test("create passes the merged context in a core-owned folder and stores only the session's own context", async () => {
  const f = fixtures(), state = scratch(), opened: Opened[] = []
  const c = core(state, [fakeDriver("fake", ALL_SUPPORTED, opened)], { context: { instructions: "core default", skills: [f.skills] } })
  const session = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", context: { instructions: "mine", plugins: [f.plugin], mcpServers: [{ name: "m", command: "cmd" }] } })
  const launch = opened[0]!.sessionContext!
  expect(launch.instructions).toBe("core default\n\nmine")
  expect(launch.skills).toEqual([f.skills])
  expect(launch.plugins).toEqual([f.plugin])
  expect(launch.mcpServers).toEqual([{ name: "m", command: "cmd", args: [], env: {} }])
  expect(launch.launch).toBe("create")
  expect(launch.dropped).toEqual([])
  expect(launch.directory).toBe(join(state, "context", "s1"))
  expect(existsSync(launch.directory)).toBe(true)
  expect(launch.directory.startsWith(f.cwd)).toBe(false)
  const record = await c.sessions.get("s1")
  expect(record?.context).toEqual({ instructions: "mine", plugins: [f.plugin], mcpServers: [{ name: "m", command: "cmd", args: [], env: {} }] })
  expect(record?.contextPolicy).toBeUndefined()
  expect(session.snapshot().id).toBe("s1")
})

test("no context anywhere: no sessionContext, no folder, no record fields", async () => {
  const f = fixtures(), state = scratch(), opened: Opened[] = []
  const c = core(state, [fakeDriver("fake", ALL_SUPPORTED, opened)])
  await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1" })
  expect("sessionContext" in opened[0]!.context).toBe(false)
  expect(existsSync(join(state, "context"))).toBe(false)
  const raw = JSON.parse(await readFile(join(state, "sessions", "s1.json"), "utf8"))
  expect(Object.keys(raw).sort()).toEqual(["agent", "agentSessionId", "createdAt", "cwd", "id", "version"])
})

test("invalid context is rejected before anything launches", async () => {
  const f = fixtures(), state = scratch(), opened: Opened[] = []
  const c = core(state, [fakeDriver("fake", ALL_SUPPORTED, opened)])
  await expect(c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", context: { skills: [join(f.root, "nope")] } })).rejects.toMatchObject({ code: "invalid_context" })
  await expect(c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s2", context: { mcpServers: [{ name: "x", command: "a" }, { name: "x", command: "b" }] } })).rejects.toMatchObject({ code: "invalid_context" })
  await expect(c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s3", contextPolicy: "loud" as never })).rejects.toMatchObject({ code: "invalid_context" })
  expect(() => createCore({ stateDirectory: state, agents: [], limits: TEST_LIMITS, context: { skills: ["rel"] } })).toThrow()
  expect(opened).toHaveLength(0)
  expect(await c.sessions.list()).toEqual([])
})

test("policy error: unsupported items → context_unsupported, nothing launches", async () => {
  const f = fixtures(), state = scratch(), opened: Opened[] = []
  const c = core(state, [fakeDriver("plain", undefined, opened)])
  const error = await c.sessions.create({ agent: "plain", cwd: f.cwd, id: "s1", context: { instructions: "x", mcpServers: [{ name: "srv", command: "c" }] } }).catch(e => e)
  expect(error.code).toBe("context_unsupported")
  expect(error.message).toContain("instructions")
  expect(error.message).toContain("srv")
  expect(opened).toHaveLength(0)
  expect(await c.sessions.get("s1")).toBeUndefined()
  expect(existsSync(join(state, "context", "s1"))).toBe(false)
})

test("policy warn: launches without the dropped items and emits context.degraded", async () => {
  const f = fixtures(), state = scratch(), opened: Opened[] = []
  const partial: DriverContextSupport = { capabilities: { ...ALL_SUPPORTED.capabilities, instructions: { support: "unsupported", note: "no channel" } } }
  const c = core(state, [fakeDriver("fake", partial, opened)], { contextPolicy: "warn" })
  const events: CoreEvent[] = []
  c.subscribe(event => { events.push(event) })
  await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", context: { instructions: "x", skills: [f.skills] } })
  await Promise.resolve(); await new Promise(resolve => setTimeout(resolve, 5))
  expect(opened[0]!.sessionContext!.dropped).toEqual([{ kind: "instructions", item: "instructions", reason: "no channel" }])
  const degraded = events.filter(event => event.type === "context.degraded")
  expect(degraded).toEqual([{ type: "context.degraded", sessionId: "s1", dropped: [{ kind: "instructions", item: "instructions", reason: "no channel" }] }])
  // A session-level policy overrides the core one and is stored.
  await expect(c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s2", contextPolicy: "error", context: { instructions: "x" } })).rejects.toMatchObject({ code: "context_unsupported" })
  await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s3", contextPolicy: "warn", context: { instructions: "x" } })
  expect((await c.sessions.get("s3"))?.contextPolicy).toBe("warn")
})

test("resume after a restart reapplies the stored context; resume({context}) replaces it", async () => {
  const f = fixtures(), state = scratch(), opened: Opened[] = []
  const driver = fakeDriver("fake", ALL_SUPPORTED, opened)
  const first = core(state, [driver], { context: { instructions: "core" } })
  await first.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", context: { skills: [f.skills] } })
  await first.close({ agents: "shutdown" })
  const second = core(state, [driver], { context: { instructions: "core" } })
  await second.sessions.resume("s1")
  const resumed = opened[1]!.sessionContext!
  expect(resumed.launch).toBe("resume")
  expect(resumed.instructions).toBe("core")
  expect(resumed.skills).toEqual([f.skills])
  await second.sessions.close("s1", { mode: "shutdown" })
  await second.sessions.resume("s1", { context: { plugins: [f.plugin] } })
  const replaced = opened[2]!.sessionContext!
  expect(replaced.skills).toEqual([])
  expect(replaced.plugins).toEqual([f.plugin])
  expect((await second.sessions.get("s1"))?.context).toEqual({ plugins: [f.plugin] })
})

test("resume({context}) on a live idle session relaunches it; the same context does not", async () => {
  const f = fixtures(), state = scratch(), opened: Opened[] = []
  const c = core(state, [fakeDriver("fake", ALL_SUPPORTED, opened)])
  const live = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", context: { skills: [f.skills] } })
  expect(await c.sessions.resume("s1", { context: { skills: [f.skills] } })).toBe(live)
  expect(opened).toHaveLength(1)
  const next = await c.sessions.resume("s1", { context: { plugins: [f.plugin] } })
  expect(next).not.toBe(live)
  expect(opened).toHaveLength(2)
  expect(opened[1]!.sessionContext!.plugins).toEqual([f.plugin])
  expect(live.snapshot().state).toBe("closed")
})

test("instructions fixed at creation: a changed resume is context_unsupported, an unchanged one is fine", async () => {
  const f = fixtures(), state = scratch(), opened: Opened[] = []
  const c = core(state, [fakeDriver("fixed", { ...ALL_SUPPORTED, instructionsFixedAtCreation: true }, opened)])
  await c.sessions.create({ agent: "fixed", cwd: f.cwd, id: "s1", context: { instructions: "A" } })
  expect((await c.sessions.get("s1"))?.createdInstructions).toBe("A")
  await c.sessions.close("s1", { mode: "shutdown" })
  await expect(c.sessions.resume("s1", { context: { instructions: "B" } })).rejects.toMatchObject({ code: "context_unsupported" })
  expect(opened).toHaveLength(1)
  expect((await c.sessions.get("s1"))?.context).toEqual({ instructions: "A" })
  await c.sessions.resume("s1")
  expect(opened[1]!.sessionContext!.instructions).toBe("A")
  expect(opened[1]!.sessionContext!.dropped).toEqual([])
})

test("fork inherits the parent's context, or takes its own", async () => {
  const f = fixtures(), state = scratch(), opened: Opened[] = []
  const c = core(state, [fakeDriver("fake", ALL_SUPPORTED, opened)])
  const parent = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "p", contextPolicy: "warn", context: { skills: [f.skills] } })
  await parent.fork({ id: "child1" })
  expect(opened[1]!.sessionContext!.launch).toBe("fork")
  expect(opened[1]!.sessionContext!.skills).toEqual([f.skills])
  expect((await c.sessions.get("child1"))?.context).toEqual({ skills: [f.skills] })
  expect((await c.sessions.get("child1"))?.contextPolicy).toBe("warn")
  await parent.fork({ id: "child2", context: { plugins: [f.plugin] } })
  expect(opened[2]!.sessionContext!.skills).toEqual([])
  expect(opened[2]!.sessionContext!.plugins).toEqual([f.plugin])
  expect(opened[2]!.sessionContext!.directory).toBe(join(state, "context", "child2"))
})

test("forget deletes the session's context folder", async () => {
  const f = fixtures(), state = scratch(), opened: Opened[] = []
  const c = core(state, [fakeDriver("fake", ALL_SUPPORTED, opened)])
  await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", context: { instructions: "x" } })
  await writeFile(join(state, "context", "s1", "generated.txt"), "x")
  await c.sessions.close("s1", { mode: "shutdown" })
  await c.sessions.forget("s1")
  expect(existsSync(join(state, "context", "s1"))).toBe(false)
})

test("core.capabilities(agent).context reports the driver's table (unsupported when it declares none)", () => {
  const c = core(scratch(), [fakeDriver("fake", ALL_SUPPORTED, []), fakeDriver("plain", undefined, [])])
  expect(c.capabilities("fake").context).toEqual(ALL_SUPPORTED.capabilities)
  const plain = c.capabilities("plain").context
  for (const kind of ["instructions", "skills", "plugins", "mcpServers"] as const) expect(plain[kind].support).toBe("unsupported")
  expect(() => c.capabilities("nope")).toThrow()
})

test("records written before context existed still load and resume", async () => {
  const f = fixtures(), state = scratch(), opened: Opened[] = []
  mkdirSync(join(state, "sessions"), { recursive: true })
  await writeFile(join(state, "sessions", "old.json"), JSON.stringify({ version: 1, id: "old", agent: "fake", agentSessionId: "native-old", cwd: f.cwd, createdAt: new Date().toISOString() }))
  const c = core(state, [fakeDriver("fake", ALL_SUPPORTED, opened)])
  await c.sessions.resume("old")
  expect("sessionContext" in opened[0]!.context).toBe(false)
  await expect(writeFile(join(state, "sessions", "bad.json"), JSON.stringify({ version: 1, id: "bad", agent: "fake", agentSessionId: "n", cwd: f.cwd, createdAt: new Date().toISOString(), context: { skills: "x" } }))).resolves.toBeUndefined()
  await expect(c.sessions.get("bad")).rejects.toMatchObject({ code: "invalid_session_record" })
})
