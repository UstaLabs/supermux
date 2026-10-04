import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs"
import { readFile, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore, type Core } from "../src/core.js"
import { CLAUDE_CONTEXT, CODEX_CONTEXT, grokContext, opencodeContext } from "../src/context/agents.js"
import { normalizePatch, planChanges } from "../src/context/update.js"
import type {
  ContextChange, DriverContextSupport, LaunchContext, RuntimeContextControl, SessionContext, UpdateContextOptions,
} from "../src/context/types.js"
import type { AgentDriver, CoreEvent, DriverContext } from "../src/types.js"
import { connectKeeper, type KeeperConnection } from "../src/keeper/index.js"
import { fileURLToPath } from "node:url"
import { TEST_LIMITS } from "./helpers.js"

setDefaultTimeout(20_000)
const dirs: string[] = []
const cores: Core[] = []
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
function scratch(): string { const dir = mkdtempSync(join(tmpdir(), "context-update-")); dirs.push(dir); return dir }

function fixtures() {
  const root = scratch()
  const cwd = join(root, "work"); mkdirSync(cwd)
  const folder = (name: string) => { const path = join(root, name); mkdirSync(join(path, "x"), { recursive: true }); writeFileSync(join(path, "x", "SKILL.md"), "---\nname: x\ndescription: x\n---\n"); return path }
  return { root, cwd, skillsA: folder("skills-a"), skillsB: folder("skills-b"), pluginA: folder("plugin-a"), pluginB: folder("plugin-b") }
}

const SUPPORTED = { support: "supported" as const, note: "n" }
/** All items supported; skills and MCP add/remove live, plugins reload, instructions reload. */
const MIXED: DriverContextSupport = {
  capabilities: { instructions: SUPPORTED, skills: SUPPORTED, plugins: SUPPORTED, mcpServers: SUPPORTED },
  update: {
    instructions: { how: "reload", note: "instructions reload" },
    skills: { add: "live", remove: "live", note: "skills live" },
    plugins: { add: "reload", remove: "reload", note: "plugins reload" },
    mcpServers: { add: "live", remove: "live", note: "mcp live" },
  },
}

type Fake = {
  driver: AgentDriver
  opened: DriverContext[]
  applies: Array<{ next: LaunchContext; changes: ContextChange[]; options: UpdateContextOptions; recordAtApply?: unknown }>
  appended: string[]
  fingerprints: string[]
  prompts: string[]
  interrupts: number
  /** Resolves the running prompt (when `blockPrompts`). */
  releasePrompt(): void
  blockPrompts: boolean
  failApply?: Error
  refuse?: (change: ContextChange) => string | undefined
  readRecord?: () => Promise<unknown>
}

function fakeDriver(id: string, support: DriverContextSupport | undefined, extra: { append?: boolean } = {}): Fake {
  const fake: Fake = {
    opened: [], applies: [], appended: [], fingerprints: [], prompts: [], interrupts: 0, blockPrompts: false,
    releasePrompt: () => {},
    driver: undefined as never,
  }
  let n = 0
  fake.driver = {
    id,
    ...(support ? { context: support } : {}),
    async open(context) {
      fake.opened.push(structuredClone({ ...context, signal: undefined, onUpdate: undefined, onExit: undefined, requestPermission: undefined, requestAnswers: undefined, onActivity: undefined }) as never)
      n++
      const control: RuntimeContextControl = {
        live: change => change.kind !== "plugins",
        async apply(next, changes, options) {
          const recordAtApply = fake.readRecord ? await fake.readRecord() : undefined
          fake.applies.push({ next: structuredClone(next), changes: structuredClone(changes), options: structuredClone(options), recordAtApply })
          if (fake.failApply) throw fake.failApply
          return changes.flatMap(change => { const reason = fake.refuse?.(change); return reason ? [{ change, reason }] : [] })
        },
        ...(extra.append ? { appendInstructions: (text: string) => { fake.appended.push(text) } } : {}),
        recordFingerprint: async fingerprint => { fake.fingerprints.push(fingerprint) },
      }
      return {
        agentSessionId: context.resumeId ?? `native-${n}`,
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        context: control,
        async prompt(content) {
          fake.prompts.push((content[0] as { text: string }).text)
          if (fake.blockPrompts) await new Promise<void>(resolve => { fake.releasePrompt = resolve })
          return { stopReason: "end_turn" }
        },
        async interrupt() { fake.interrupts++ },
        async close() {},
      }
    },
  }
  return fake
}

function core(state: string, drivers: AgentDriver[], extra: { context?: SessionContext; contextPolicy?: "error" | "warn" } = {}) {
  const c = createCore({ stateDirectory: state, agents: drivers, limits: TEST_LIMITS, ...extra })
  cores.push(c)
  return c
}

const text = (t: string) => ({ content: [{ type: "text" as const, text: t }], whenBusy: "queue" as const })
const tick = (ms = 20) => new Promise(resolve => setTimeout(resolve, ms))

// ------------------------------------------------------------ validation

test("patch validation: malformed patches and impossible changes are invalid_context, nothing is stored", async () => {
  for (const bad of ["x", [], { other: 1 }, { skills: ["/a"] }, { skills: { add: ["rel"] } }, { skills: { put: [] } }, { mcpServers: { add: [{ name: "bad name", command: "x" }] } }, { mcpServers: { remove: ["bad name"] } }, { instructions: 3 }]) {
    expect(() => normalizePatch(bad)).toThrow(expect.objectContaining({ code: "invalid_context" }))
  }
  const f = fixtures(), state = scratch(), fake = fakeDriver("fake", MIXED)
  const c = core(state, [fake.driver], { context: { skills: [f.skillsB] } })
  const session = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", context: { skills: [f.skillsA], mcpServers: [{ name: "m", command: "x" }] } })
  const before = await c.sessions.get("s1")
  for (const patch of [
    { skills: { add: [f.skillsA] } },                         // already there
    { skills: { add: [f.skillsB] } },                         // already there (core default)
    { skills: { remove: [f.skillsB] } },                      // the core default cannot be removed per session
    { skills: { remove: [f.pluginA] } },                      // not in the context
    { skills: { add: [f.pluginA], remove: [f.pluginA] } },    // both
    { skills: { add: [join(f.root, "missing")] } },           // does not exist
    { mcpServers: { add: [{ name: "m", command: "y" }] } },   // name taken
    { mcpServers: { remove: ["nope"] } },
  ]) {
    await expect(session.updateContext(patch as never)).rejects.toMatchObject({ code: "invalid_context" })
  }
  expect(await c.sessions.get("s1")).toEqual(before)
  expect(fake.applies).toHaveLength(0)
  await expect(session.updateContext({ skills: { add: [f.pluginA] } }, { reload: "sometimes" } as never)).rejects.toMatchObject({ code: "invalid_input" })
})

// ------------------------------------------------------------ policy

test("policy error: anything unsupported → context_unsupported and NOTHING is applied or stored", async () => {
  const f = fixtures(), state = scratch()
  const support: DriverContextSupport = { ...MIXED, capabilities: { ...MIXED.capabilities, plugins: { support: "unsupported", note: "no plugins here" } } }
  const fake = fakeDriver("fake", support)
  const c = core(state, [fake.driver])
  const session = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", context: { skills: [f.skillsA] } })
  const before = await c.sessions.get("s1")
  await expect(session.updateContext({ skills: { add: [f.skillsB] }, plugins: { add: [f.pluginA] } })).rejects.toMatchObject({ code: "context_unsupported" })
  expect(await c.sessions.get("s1")).toEqual(before)
  expect(fake.applies).toHaveLength(0)
  expect(fake.opened).toHaveLength(1)
})

test("policy warn: applies what it can, reports and does not store the rest", async () => {
  const f = fixtures(), state = scratch()
  const support: DriverContextSupport = { ...MIXED, capabilities: { ...MIXED.capabilities, plugins: { support: "unsupported", note: "no plugins here" } } }
  const fake = fakeDriver("fake", support)
  const c = core(state, [fake.driver])
  const events: CoreEvent[] = []
  c.subscribe(event => { events.push(event) })
  const session = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", contextPolicy: "warn", context: { skills: [f.skillsA] } })
  const result = await session.updateContext({ skills: { add: [f.skillsB] }, plugins: { add: [f.pluginA] } })
  expect(result.applied).toEqual([
    { kind: "skills", op: "add", item: f.skillsB, how: "live" },
    { kind: "plugins", op: "add", item: f.pluginA, how: "unsupported", reason: "no plugins here" },
  ])
  expect(result.effective).toBe("now")
  expect((await c.sessions.get("s1"))!.context).toEqual({ skills: [f.skillsA, f.skillsB] })
  expect(fake.applies[0]!.changes).toEqual([{ kind: "skills", op: "add", item: f.skillsB }])
  expect(fake.applies[0]!.next.skills).toEqual([f.skillsA, f.skillsB])
  expect(events.filter(event => event.type === "context.updated")).toEqual([{ type: "context.updated", sessionId: "s1", applied: result.applied }])
})

// ------------------------------------------------------------ persistence and live

test("the record is persisted before the live change goes out; the keeper fingerprint follows", async () => {
  const f = fixtures(), state = scratch(), fake = fakeDriver("fake", MIXED)
  const c = core(state, [fake.driver])
  fake.readRecord = () => c.sessions.get("s1")
  const session = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", context: { mcpServers: [{ name: "a", command: "x" }] } })
  const result = await session.updateContext({ mcpServers: { add: [{ name: "b", command: "y", args: ["1"] }], remove: ["a"] } })
  expect(result).toEqual({
    applied: [
      { kind: "mcpServers", op: "remove", item: "a", how: "live" },
      { kind: "mcpServers", op: "add", item: "b", how: "live" },
    ],
    effective: "now",
  })
  const atApply = fake.applies[0]!.recordAtApply as { context?: SessionContext }
  expect(atApply.context).toEqual({ mcpServers: [{ name: "b", command: "y", args: ["1"], env: {} }] })
  expect(fake.fingerprints).toEqual([fake.applies[0]!.next.fingerprint])
  expect(fake.applies[0]!.next.fingerprint).not.toBe(fake.opened[0]!.sessionContext!.fingerprint)
  expect(session.snapshot().context).toEqual({ mcpServers: [{ name: "b", command: "y", args: ["1"], env: {} }] })
  expect(fake.opened).toHaveLength(1)
})

test("a change the agent refuses (Claude hold on cache impact) is unsupported and dropped from the record again", async () => {
  const f = fixtures(), state = scratch(), fake = fakeDriver("fake", MIXED)
  fake.refuse = change => change.kind === "skills" ? "held" : undefined
  const c = core(state, [fake.driver])
  const session = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", contextPolicy: "warn", context: {} })
  const result = await session.updateContext({ skills: { add: [f.skillsA] }, mcpServers: { add: [{ name: "b", command: "y" }] } }, { holdOnCacheImpact: true })
  expect(result.applied).toEqual([
    { kind: "skills", op: "add", item: f.skillsA, how: "unsupported", reason: "held" },
    { kind: "mcpServers", op: "add", item: "b", how: "live" },
  ])
  expect(fake.applies[0]!.options).toEqual({ holdOnCacheImpact: true })
  expect((await c.sessions.get("s1"))!.context).toEqual({ mcpServers: [{ name: "b", command: "y", args: [], env: {} }] })
})

// ------------------------------------------------------------ reload

test("reload waits for the running turn (never cancels it); queued input runs after, on the relaunched session", async () => {
  const f = fixtures(), state = scratch(), fake = fakeDriver("fake", MIXED)
  const c = core(state, [fake.driver])
  const session = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", context: {} })
  fake.blockPrompts = true
  const first = await session.send(text("first"))
  await tick()
  expect(session.snapshot().state).toBe("running")
  const update = session.updateContext({ plugins: { add: [f.pluginA] } })
  const queued = await session.send(text("queued"))
  let settled = false
  void update.then(() => { settled = true })
  await tick(80)
  expect(settled).toBe(false)
  expect(fake.opened).toHaveLength(1)
  expect(fake.interrupts).toBe(0)
  // The record already carries the change while the turn runs.
  expect((await c.sessions.get("s1"))!.context).toEqual({ plugins: [f.pluginA] })
  fake.blockPrompts = false
  fake.releasePrompt()
  expect(await first.completed).toEqual({ status: "completed", stopReason: "end_turn" })
  const result = await update
  expect(result).toEqual({ applied: [{ kind: "plugins", op: "add", item: f.pluginA, how: "reload" }], effective: "next_turn" })
  // The relaunch resumed the same conversation with the new context, before the queued input ran.
  expect(fake.opened).toHaveLength(2)
  expect(fake.opened[1]!.resumeId).toBe("native-1")
  expect(fake.opened[1]!.sessionContext!.plugins).toEqual([f.pluginA])
  expect(await queued.completed).toEqual({ status: "completed", stopReason: "end_turn" })
  expect(fake.prompts).toEqual(["first", "queued"])
  const relaunched = c.sessions.live("s1")!
  expect(relaunched).not.toBe(session)
  expect(session.snapshot().state).toBe("closed")
  expect(relaunched.snapshot().context).toEqual({ plugins: [f.pluginA] })
})

test("a live change that would need waiting goes out at the idle point: effective next_turn", async () => {
  const f = fixtures(), state = scratch(), fake = fakeDriver("fake", MIXED)
  const c = core(state, [fake.driver])
  const session = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1" })
  fake.blockPrompts = true
  await session.send(text("first"))
  await tick()
  const update = session.updateContext({ skills: { add: [f.skillsA] } })
  await tick(50)
  expect(fake.applies).toHaveLength(0)
  fake.blockPrompts = false
  fake.releasePrompt()
  expect(await update).toEqual({ applied: [{ kind: "skills", op: "add", item: f.skillsA, how: "live" }], effective: "next_turn" })
  expect(fake.opened).toHaveLength(1)
})

test("a change mixing live and reload items relaunches once and reports all as reload", async () => {
  const f = fixtures(), state = scratch(), fake = fakeDriver("fake", MIXED)
  const c = core(state, [fake.driver])
  const session = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1" })
  const result = await session.updateContext({ skills: { add: [f.skillsA] }, plugins: { add: [f.pluginA] } })
  expect(result.applied.map(entry => entry.how)).toEqual(["reload", "reload"])
  expect(result.applied.find(entry => entry.kind === "skills")!.reason).toContain("relaunch")
  expect(fake.applies).toHaveLength(0)
  expect(fake.opened).toHaveLength(2)
})

test("a failing live change falls back to a reload (unless reload is never)", async () => {
  const f = fixtures(), state = scratch(), fake = fakeDriver("fake", MIXED)
  fake.failApply = new Error("boom")
  const c = core(state, [fake.driver])
  const session = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1" })
  const result = await session.updateContext({ skills: { add: [f.skillsA] } })
  expect(result.applied).toEqual([{ kind: "skills", op: "add", item: f.skillsA, how: "reload", reason: expect.stringContaining("boom") }])
  expect(fake.opened).toHaveLength(2)
  await expect(c.sessions.live("s1")!.updateContext({ skills: { add: [f.skillsB] } }, { reload: "never" })).rejects.toThrow("boom")
})

test("reload: never → reload items are unsupported (error policy refuses, warn reports)", async () => {
  const f = fixtures(), state = scratch(), fake = fakeDriver("fake", MIXED)
  const c = core(state, [fake.driver])
  const strict = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1" })
  await expect(strict.updateContext({ plugins: { add: [f.pluginA] } }, { reload: "never" })).rejects.toMatchObject({ code: "context_unsupported" })
  const warn = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s2", contextPolicy: "warn" })
  const result = await warn.updateContext({ plugins: { add: [f.pluginA] }, skills: { add: [f.skillsA] } }, { reload: "never" })
  expect(result.applied).toEqual([
    { kind: "skills", op: "add", item: f.skillsA, how: "live" },
    { kind: "plugins", op: "add", item: f.pluginA, how: "unsupported", reason: expect.stringContaining("reload is \"never\"") },
  ])
  expect(fake.opened).toHaveLength(2)
  expect((await c.sessions.get("s2"))!.context).toEqual({ skills: [f.skillsA] })
})

// ------------------------------------------------------------ instructions

const APPENDS: DriverContextSupport = { ...MIXED, instructionsFixedAtCreation: true, update: { ...MIXED.update!, instructions: { how: "append", note: "fixed at thread start" } } }

test("Codex-style instructions: unsupported without the opt-in; codexInstructions: append adds them and the record follows", async () => {
  const f = fixtures(), state = scratch(), fake = fakeDriver("codexish", APPENDS, { append: true })
  const c = core(state, [fake.driver], { context: { instructions: "Core." } })
  const session = await c.sessions.create({ agent: "codexish", cwd: f.cwd, id: "s1", context: { instructions: "Old." } })
  await expect(session.updateContext({ instructions: "New." })).rejects.toMatchObject({ code: "context_unsupported" })
  expect(fake.appended).toEqual([])
  const result = await session.updateContext({ instructions: "New." }, { codexInstructions: "append" })
  expect(result).toEqual({ applied: [{ kind: "instructions", op: "replace", item: "instructions", how: "append" }], effective: "next_turn" })
  expect(fake.appended).toEqual(["Core.\n\nNew."])
  const record = (await c.sessions.get("s1"))!
  expect(record.context).toEqual({ instructions: "New." })
  // The conversation now carries these instructions: a later resume does not flag them.
  expect(record.createdInstructions).toBe("Core.\n\nNew.")
  await c.sessions.close("s1", { mode: "shutdown" })
  await c.sessions.resume("s1")
  expect(fake.opened.at(-1)!.sessionContext!.dropped).toEqual([])
  // Removing instructions cannot be appended.
  await expect(c.sessions.live("s1")!.updateContext({ instructions: [] }, { codexInstructions: "append" })).rejects.toMatchObject({ code: "context_unsupported" })
})

test("instructions reload on a reload-instructions agent; unsupported where they are fixed", async () => {
  const f = fixtures(), state = scratch(), fake = fakeDriver("fake", MIXED)
  const grokish = fakeDriver("grokish", { ...MIXED, update: { ...MIXED.update!, instructions: { how: "unsupported", note: "fixed at session/new" } } })
  const c = core(state, [fake.driver, grokish.driver])
  const session = await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", context: { instructions: "Old." } })
  expect((await session.updateContext({ instructions: ["A", "B"] })).applied).toEqual([{ kind: "instructions", op: "replace", item: "instructions", how: "reload" }])
  expect(fake.opened[1]!.sessionContext!.instructions).toBe("A\n\nB")
  // Same text → no change at all.
  expect(await c.sessions.live("s1")!.updateContext({ instructions: "A\n\nB" })).toEqual({ applied: [], effective: "now" })
  const other = await c.sessions.create({ agent: "grokish", cwd: f.cwd, id: "s2", context: { instructions: "Old." } })
  await expect(other.updateContext({ instructions: "New." })).rejects.toMatchObject({ code: "context_unsupported", message: expect.stringContaining("fixed at session/new") })
})

// ------------------------------------------------------------ closed sessions

test("core.sessions.updateContext on a closed session only updates the record; the next launch applies it", async () => {
  const f = fixtures(), state = scratch(), fake = fakeDriver("fake", MIXED)
  const c = core(state, [fake.driver])
  await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1", context: { skills: [f.skillsA] } })
  await c.sessions.close("s1", { mode: "shutdown" })
  const result = await c.sessions.updateContext("s1", { skills: { remove: [f.skillsA] }, plugins: { add: [f.pluginA] }, mcpServers: { add: [{ name: "m", command: "x" }] } })
  expect(result.effective).toBe("next_turn")
  expect(result.applied.map(entry => entry.how)).toEqual(["reload", "reload", "reload"])
  expect(fake.opened).toHaveLength(1)
  expect((await c.sessions.get("s1"))!.context).toEqual({ plugins: [f.pluginA], mcpServers: [{ name: "m", command: "x", args: [], env: {} }] })
  await c.sessions.resume("s1")
  expect(fake.opened[1]!.sessionContext!.skills).toEqual([])
  expect(fake.opened[1]!.sessionContext!.plugins).toEqual([f.pluginA])
  await expect(c.sessions.updateContext("nope", { skills: { add: [f.skillsA] } })).rejects.toMatchObject({ code: "session_not_found" })
})

test("core.sessions.updateContext on an open session takes the live path", async () => {
  const f = fixtures(), state = scratch(), fake = fakeDriver("fake", MIXED)
  const c = core(state, [fake.driver])
  await c.sessions.create({ agent: "fake", cwd: f.cwd, id: "s1" })
  expect(await c.sessions.updateContext("s1", { skills: { add: [f.skillsA] } })).toEqual({ applied: [{ kind: "skills", op: "add", item: f.skillsA, how: "live" }], effective: "now" })
  expect(fake.applies).toHaveLength(1)
})

// ------------------------------------------------------------ the per-driver tables

const control = (live: (change: ContextChange) => boolean, append = false): RuntimeContextControl => ({ live, apply: async () => [], ...(append ? { appendInstructions: () => {} } : {}) })
const NEXT = { instructions: "x", skills: [], plugins: [], mcpServers: [], directory: "/d", launch: "resume", dropped: [], fingerprint: "f" } as LaunchContext
const CHANGES: ContextChange[] = [
  { kind: "instructions", op: "replace", item: "instructions" },
  { kind: "skills", op: "add", item: "/s" }, { kind: "skills", op: "remove", item: "/s0" },
  { kind: "plugins", op: "add", item: "/p" }, { kind: "plugins", op: "remove", item: "/p0" },
  { kind: "mcpServers", op: "add", item: "m" }, { kind: "mcpServers", op: "remove", item: "m0" },
]
const hows = (support: DriverContextSupport, runtime: RuntimeContextControl | undefined, options: UpdateContextOptions = {}) =>
  planChanges({ agent: "a", changes: CHANGES, support, capabilities: support.capabilities, runtime, next: NEXT, options, open: true }).map(entry => entry.how)

test("planChanges per driver table: live only where declared AND confirmed by the running process", () => {
  // Claude: the runtime confirms (launched with the plugin folder; m0 was added live).
  // Claude instructions: fixed at creation (--resume keeps the stored appended prompt).
  expect(hows(CLAUDE_CONTEXT, control(() => true))).toEqual(["unsupported", "live", "live", "live", "live", "live", "live"])
  expect(hows(CLAUDE_CONTEXT, control(change => change.kind !== "mcpServers" || change.op === "add"))).toEqual(["unsupported", "live", "live", "live", "live", "live", "reload"])
  expect(hows(CLAUDE_CONTEXT, control(() => false), { reload: "never" })).toEqual(["unsupported", "unsupported", "unsupported", "unsupported", "unsupported", "unsupported", "unsupported"])
  // Codex: skills live, plugins live only when skills-only, MCP always reload, instructions append (opt-in).
  expect(hows(CODEX_CONTEXT, control(change => change.kind === "skills"), {})).toEqual(["unsupported", "live", "live", "reload", "reload", "reload", "reload"])
  expect(hows(CODEX_CONTEXT, control(() => true, true), { codexInstructions: "append" })).toEqual(["append", "live", "live", "live", "live", "reload", "reload"])
  // Grok and OpenCode: reload (Grok instructions unsupported).
  expect(hows(grokContext([]).support, control(() => true))).toEqual(["unsupported", "reload", "reload", "reload", "reload", "reload", "reload"])
  expect(hows(opencodeContext([]).support, control(() => true))).toEqual(["reload", "reload", "reload", "reload", "reload", "reload", "reload"])
  // No runtime (closed session): everything applicable is reload.
  expect(planChanges({ agent: "a", changes: CHANGES, support: CLAUDE_CONTEXT, capabilities: CLAUDE_CONTEXT.capabilities, runtime: undefined, next: NEXT, options: { reload: "never" }, open: false }).map(entry => entry.how))
    .toEqual(["unsupported", "reload", "reload", "reload", "reload", "reload", "reload"])
})

test("core.capabilities exposes the in-flight table", async () => {
  const state = scratch(), fake = fakeDriver("fake", MIXED), bare = fakeDriver("bare", undefined)
  const c = core(state, [fake.driver, bare.driver])
  expect(c.capabilities("fake").contextUpdate).toEqual(MIXED.update!)
  expect(c.capabilities("bare").contextUpdate.instructions.how).toBe("unsupported")
})

// ------------------------------------------------------------ keeper: a changed launch is never re-attached

const echo = fileURLToPath(new URL("./fixtures/echo-agent.mjs", import.meta.url))
const KEEPER_LIMITS = { maxFrameBytes: 4096, shutdownTimeoutMs: 200, parkedDeadlineMs: 5000, journalMaxBytes: 64_000, connectTimeoutMs: 4000 }
const alive = (pid: number) => { try { process.kill(pid, 0); return true } catch { return false } }

test("keeper: a detached agent launched for another fingerprint is replaced by a new process; the same one re-attaches", async () => {
  const stateDirectory = scratch()
  const connect = (fingerprint?: string) => connectKeeper({
    stateDirectory, sessionId: "fp",
    spec: { command: process.execPath, args: [echo], cwd: process.cwd(), env: { PATH: process.env.PATH ?? "" }, frameShape: "jsonrpc", captureStderr: false, ...(fingerprint !== undefined ? { fingerprint } : {}) },
    limits: KEEPER_LIMITS, cursor: "acked",
  })
  const status = async () => JSON.parse(await readFile(join(stateDirectory, "keepers", "fp", "status.json"), "utf8"))
  let conn: KeeperConnection = await connect("a")
  const first = await status()
  await conn.detach()
  conn = await connect("a")
  expect(conn.welcome.agentRunning).toBe(true)
  expect((await status()).agentPid).toBe(first.agentPid)
  await conn.detach()
  conn = await connect("b")
  const second = await status()
  expect(second.agentPid).not.toBe(first.agentPid)
  expect(alive(first.agentPid)).toBe(false)
  expect(alive(first.keeperPid)).toBe(false)
  // A live change recorded on the keeper makes the next same-fingerprint attach a re-attach.
  await conn.setFingerprint("c")
  await conn.detach()
  conn = await connect("c")
  expect((await status()).agentPid).toBe(second.agentPid)
  await conn.detach()
  // No fingerprint asked for: always re-attach (callers that do not use session context).
  conn = await connect()
  expect((await status()).agentPid).toBe(second.agentPid)
  await conn.shutdown()
})
