import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readlinkSync, rmSync, unlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join, relative } from "node:path"
import { fileURLToPath } from "node:url"
import { defaults, supermux } from "../src/index.js"
import { prepareAntigravityEnvironment } from "../src/environment/index.js"
import { KEEPER_ENV } from "../src/keeper/protocol.js"

setDefaultTimeout(30_000)

const fixture = (name: string) => fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url))
const dirs: string[] = []
const temp = (prefix: string) => { const dir = mkdtempSync(join(tmpdir(), prefix)); dirs.push(dir); return dir }
afterEach(() => { for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true }) })
const text = (value: string) => [{ type: "text" as const, text: value }]
const jsonLines = (path: string): any[] => readFileSync(path, "utf8").trim().split("\n").filter(Boolean).map(line => JSON.parse(line))
const claudeFixture = () => ({ command: process.execPath, args: [fixture("claude-agent.mjs")], env: { EXPECT_DEFAULT_DENY: "0" } })
function thrown(fn: () => unknown): Error {
  try { fn() } catch (error) { return error as Error }
  throw new Error("expected a throw")
}

test("defaults are frozen all the way down", () => {
  expect(Object.isFrozen(defaults)).toBe(true)
  expect(Object.isFrozen(defaults.codex)).toBe(true)
  expect(Object.isFrozen(defaults.codex.keeper)).toBe(true)
  expect(Object.isFrozen(defaults.codex.args)).toBe(true)
  expect(() => { (defaults.codex as { sandbox: string }).sandbox = "danger-full-access" }).toThrow()
})

test("permission defaults are each CLI's asking mode", () => {
  expect(defaults.claude.permissionMode).toBe("default")
  expect(defaults.claude.permissionPrompts).toBe("host")
  expect(defaults.codex.sandbox).toBe("workspace-write")
  expect(defaults.codex.approvalPolicy).toBe("on-request")
  expect(defaults.grok.policy).toBe("ask")
  expect(defaults.opencode.policy).toBe("ask")
  expect(defaults.cursor.policy).toBe("ask")
  expect(defaults.cursor.mode).toBe("agent")
  expect(defaults.antigravity.mode).toBe("default")
})

test("the options the strict factories now require have defaults too", () => {
  expect(defaults.opencode.openCodePollIntervalMs).toBe(500)
  expect(defaults.antigravity.interruptGraceMs).toBe(5_000)
})

test("every agent builds from its defaults alone", async () => {
  const dir = temp("supermux-all-")
  const core = supermux({
    stateDirectory: join(dir, "state"),
    agents: { claude: {}, codex: {}, grok: {}, opencode: {}, cursor: {}, antigravity: { home: join(dir, "shadow"), userHome: join(dir, "user") } },
  })
  try {
    for (const agent of ["claude", "codex", "grok", "opencode", "cursor", "antigravity"]) expect(() => core.capabilities(agent)).not.toThrow()
  } finally {
    await core.close({ agents: "shutdown" })
  }
})

test("array options take readonly arrays, such as the ones in defaults", async () => {
  const dir = temp("supermux-readonly-")
  const core = supermux({
    stateDirectory: join(dir, "state"),
    agents: {
      claude: { args: defaults.claude.args, tools: ["Read", "Grep"] as const, allowedTools: ["Read"] as const, disallowedTools: ["Bash"] as const },
      codex: { args: defaults.codex.args },
      grok: { commandArgs: defaults.grok.commandArgs, mcpServers: defaults.grok.mcpServers, askKinds: ["edit", "execute"] as const },
      opencode: { mcpServers: defaults.opencode.mcpServers },
      cursor: { commandArgs: defaults.cursor.commandArgs, askKinds: ["execute"] as const },
      antigravity: { args: defaults.antigravity.args, home: join(dir, "shadow"), userHome: join(dir, "user") },
    },
  })
  await core.close({ agents: "shutdown" })
})

test("claude with {} sends prompts to the host, streams partial messages and keeps its keeper in the state directory", async () => {
  const dir = temp("supermux-claude-")
  const trace = join(dir, "env.json")
  const args = [fixture("claude-agent.mjs")]
  const core = supermux({
    stateDirectory: join(dir, "state"),
    agents: { claude: { command: process.execPath, args, env: { ENV_TRACE: trace, EXPECT_DEFAULT_DENY: "0" } } },
  })
  args.push("--pushed-after-supermux") // the driver holds a copy
  try {
    const session = await core.sessions.create({ id: "c1", agent: "claude", cwd: dir })
    const { argv } = JSON.parse(readFileSync(trace, "utf8")) as { argv: string[] }
    expect(argv[argv.indexOf("--permission-prompts") + 1]).toBe("host")
    expect(argv).toContain("--include-partial-messages")
    expect(argv).not.toContain("--permission-mode")
    expect(argv).not.toContain("--pushed-after-supermux")
    const receipt = await session.send({ content: text("hello"), whenBusy: "queue" })
    expect((await receipt.completed).status).toBe("completed")
    expect(existsSync(join(dir, "state", "keepers", "c1"))).toBe(true)
  } finally {
    await core.close({ agents: "shutdown" })
  }
})

test("a relative stateDirectory resolves against the process cwd, keepers included", async () => {
  const dir = temp("supermux-relative-")
  // A keeper runs in its module's directory. From this deep cwd the relative path climbs to the
  // filesystem root when resolved from anywhere shallower, so a keeper handed it unresolved fails
  // at once instead of creating directories (and an agent) outside the temp dir.
  const deep = join(dir, ...Array.from({ length: 32 }, (_, i) => `d${i}`))
  mkdirSync(deep, { recursive: true })
  const previous = process.cwd()
  process.chdir(deep)
  try {
    const core = supermux({ stateDirectory: relative(deep, join(dir, "supermux-test-state")), agents: { claude: claudeFixture() } })
    try {
      await core.sessions.create({ id: "r1", agent: "claude", cwd: dir })
      expect(existsSync(join(dir, "supermux-test-state", "keepers", "r1", "status.json"))).toBe(true)
      expect(existsSync(join(dir, "supermux-test-state", "sessions", "r1.json"))).toBe(true)
    } finally {
      await core.close({ agents: "shutdown" })
    }
  } finally {
    process.chdir(previous)
  }
})

test.skipIf(process.platform !== "linux")("keeper limits reach the keeper merged one level over the agent's defaults", async () => {
  const dir = temp("supermux-keeper-run-")
  const core = supermux({ stateDirectory: join(dir, "state"), agents: { claude: { ...claudeFixture(), keeper: { parkedDeadlineMs: 123_456 } } } })
  try {
    await core.sessions.create({ id: "k1", agent: "claude", cwd: dir })
    const { keeperPid } = JSON.parse(readFileSync(join(dir, "state", "keepers", "k1", "status.json"), "utf8")) as { keeperPid: number }
    // The keeper reads its limits from its environment, which Linux shows in /proc.
    const variable = readFileSync(`/proc/${keeperPid}/environ`, "utf8").split("\0").find(entry => entry.startsWith(`${KEEPER_ENV.limits}=`))
    expect(JSON.parse(variable!.slice(KEEPER_ENV.limits.length + 1))).toMatchObject({
      parkedDeadlineMs: 123_456,
      journalMaxBytes: defaults.claude.keeper.journalMaxBytes,
      connectTimeoutMs: defaults.claude.keeper.connectTimeoutMs,
    })
  } finally {
    await core.close({ agents: "shutdown" })
  }
})

test("codex: one sandbox input reaches the launch and thread/start", async () => {
  const dir = temp("supermux-codex-")
  const trace = join(dir, "env.json")
  const core = supermux({
    stateDirectory: join(dir, "state"),
    agents: { codex: { command: process.execPath, args: [fixture("codex-agent.mjs")], sandbox: "read-only", env: { ENV_TRACE: trace, EXPECT_POLICY: "on-request", EXPECT_SANDBOX: "read-only" } } },
  })
  try {
    const session = await core.sessions.create({ id: "x1", agent: "codex", cwd: dir })
    const { argv } = JSON.parse(readFileSync(trace, "utf8")) as { argv: string[] }
    expect(argv).toContain('sandbox_mode="read-only"')
    expect(argv).toContain('approval_policy="on-request"')
    const receipt = await session.send({ content: text("hello"), whenBusy: "queue" })
    expect((await receipt.completed).status).toBe("completed")
  } finally {
    await core.close({ agents: "shutdown" })
  }
})

test("grok with {} launches without --no-leader and finishes a turn", async () => {
  const dir = temp("supermux-grok-")
  const trace = join(dir, "env.json")
  const core = supermux({
    stateDirectory: join(dir, "state"),
    agents: { grok: { command: process.execPath, commandArgs: [fixture("grok-agent.mjs")], env: { ENV_TRACE: trace } } },
  })
  try {
    const session = await core.sessions.create({ id: "g1", agent: "grok", cwd: dir })
    const { argv } = JSON.parse(readFileSync(trace, "utf8")) as { argv: string[] }
    expect(argv[0]).toBe("agent")
    expect(argv).not.toContain("--no-leader")
    const receipt = await session.send({ content: text("hello"), whenBusy: "queue" })
    expect((await receipt.completed).status).toBe("completed")
  } finally {
    await core.close({ agents: "shutdown" })
  }
})

test("opencode with {} finishes a turn", async () => {
  const dir = temp("supermux-opencode-")
  // opencode() has no argv prefix: a shim stands in for the opencode executable.
  const shim = join(dir, "opencode")
  writeFileSync(shim, `#!/bin/sh\nexec "${process.execPath}" "${fixture("acp-agent.mjs")}" "$@"\n`)
  chmodSync(shim, 0o755)
  const core = supermux({ stateDirectory: join(dir, "state"), agents: { opencode: { command: shim } } })
  try {
    const session = await core.sessions.create({ id: "o1", agent: "opencode", cwd: dir })
    const receipt = await session.send({ content: text("hello"), whenBusy: "queue" })
    expect((await receipt.completed).status).toBe("completed")
  } finally {
    await core.close({ agents: "shutdown" })
  }
})

function cursorRig(prefix: string, extra: { mode?: "agent" | "plan" | "ask" } = {}) {
  const dir = temp(prefix)
  const trace = join(dir, "trace.jsonl")
  const core = supermux({
    stateDirectory: join(dir, "state"),
    agents: { cursor: { command: process.execPath, commandArgs: [fixture("cursor-agent.mjs")], env: { TRACE: trace }, ...extra } },
  })
  const modeWrites = () => jsonLines(trace).filter(line => line?.setConfig?.configId === "mode").map(line => line.setConfig.value)
  return { dir, core, modeWrites }
}

test("cursor with {} opens in agent mode, its permissions carry the same mode, and finishes a turn", async () => {
  const { dir, core, modeWrites } = cursorRig("supermux-cursor-")
  try {
    const session = await core.sessions.create({ id: "u1", agent: "cursor", cwd: dir })
    expect(modeWrites()).toEqual(["agent"])
    // The launch spec already has nativeMode "agent": restating it sends nothing new.
    await session.setPermissions({ kind: "acp", policy: "ask", nativeMode: "agent" })
    expect(modeWrites()).toEqual(["agent"])
    const receipt = await session.send({ content: text("hello"), whenBusy: "queue" })
    expect((await receipt.completed).status).toBe("completed")
  } finally {
    await core.close({ agents: "shutdown" })
  }
})

test("cursor: a session's own nativeMode is the mode Cursor runs in, not the agent's mode", async () => {
  const { dir, core, modeWrites } = cursorRig("supermux-cursor-own-", { mode: "ask" })
  try {
    await core.sessions.create({ id: "u2", agent: "cursor", cwd: dir, permissions: { kind: "acp", policy: "ask", nativeMode: "plan" } })
    // The agent's mode goes first; the session's own is applied after it, so it is what runs.
    expect(modeWrites()).toEqual(["ask", "plan"])
  } finally {
    await core.close({ agents: "shutdown" })
  }
})

test("cursor keeps its mode across a resume", async () => {
  const { dir, core, modeWrites } = cursorRig("supermux-cursor-resume-", { mode: "plan" })
  try {
    await core.sessions.create({ id: "u3", agent: "cursor", cwd: dir })
    expect(modeWrites()).toEqual(["plan"])
    await core.sessions.close("u3", { mode: "shutdown" })
    await core.sessions.resume("u3")
    // Cursor's session/load returns no option lists, yet the resumed session gets its mode again.
    expect(modeWrites()).toEqual(["plan", "plan"])
  } finally {
    await core.close({ agents: "shutdown" })
  }
})

async function agyRig(prefix: string) {
  const dir = temp(prefix)
  const user = join(dir, "user"), shadow = join(dir, "shadow"), log = join(dir, "agy.log")
  mkdirSync(join(user, ".gemini", "antigravity-cli"), { recursive: true })
  await prepareAntigravityEnvironment({ home: shadow, userHome: user, platform: process.platform })
  const cliLink = join(shadow, ".gemini", "antigravity-cli")
  unlinkSync(cliLink) // the driver links it again, to the userHome it was given
  return { dir, user, shadow, log, cliLink }
}

/**
 * Opens an Antigravity session whose fake agy refuses at once and writes nothing (a turn would
 * write a conversation through the shadow's link), so not even a wrong userHome could reach a real
 * home. Returns what the fake agy logged about its launch.
 */
async function refusedAgy(rig: Awaited<ReturnType<typeof agyRig>>, homes: { home: string; userHome: string }) {
  const core = supermux({
    stateDirectory: join(rig.dir, "state"),
    agents: { antigravity: { command: process.execPath, args: [fixture("antigravity-agent.mjs")], env: { FAKE_AGY_LOG: rig.log, FAKE_AGY_MODE: "ineligible" }, ...homes } },
  })
  try {
    await expect(core.sessions.create({ id: "a1", agent: "antigravity", cwd: rig.dir })).rejects.toThrow("Eligibility check failed")
    return jsonLines(rig.log).find(entry => entry.type === "spawn")
  } finally {
    await core.close({ agents: "shutdown" })
  }
}

test("antigravity launches under the given shadow home, mirrors the given userHome and keeps agy's own default mode", async () => {
  const rig = await agyRig("supermux-agy-run-")
  const spawn = await refusedAgy(rig, { home: rig.shadow, userHome: rig.user })
  expect(spawn.home).toBe(rig.shadow)
  expect(spawn.argv).not.toContain("--mode")
  expect(spawn.argv).not.toContain("--dangerously-skip-permissions")
  expect(readlinkSync(rig.cliLink)).toBe(join(rig.user, ".gemini", "antigravity-cli"))
})

test("antigravity resolves a relative home and userHome against the process cwd, not the session's", async () => {
  const rig = await agyRig("supermux-agy-rel-")
  const spawn = await refusedAgy(rig, { home: relative(process.cwd(), rig.shadow), userHome: relative(process.cwd(), rig.user) })
  expect(spawn.home).toBe(rig.shadow)
  expect(readlinkSync(rig.cliLink)).toBe(join(rig.user, ".gemini", "antigravity-cli"))
})

test("limits merge over defaults.core.limits and are still validated", async () => {
  const core = supermux({ stateDirectory: temp("supermux-limits-"), agents: {}, limits: { interruptTimeoutMs: 1_000 } })
  await core.close({ agents: "shutdown" })
  expect(() => supermux({ stateDirectory: temp("supermux-limits-"), agents: {}, limits: { maxPending: 0 } })).toThrow("maxPending")
})

test("keeper limits merge one level over the agent's defaults", () => {
  const stateDirectory = temp("supermux-keeper-")
  // Merged: parkedDeadlineMs still comes from defaults, so the strict factory names journalMaxBytes.
  expect(() => supermux({ stateDirectory, agents: { claude: { keeper: { journalMaxBytes: 0 } } } })).toThrow("supermux: agents.claude: Claude keeper.limits.journalMaxBytes")
  expect(() => supermux({ stateDirectory, agents: { grok: { keeper: { journalMaxBytes: 0 } } } })).toThrow("supermux: agents.grok: ACP keeper.limits.journalMaxBytes")
})

test("undefined means not given; null is a value the strict factory judges", () => {
  const stateDirectory = temp("supermux-values-")
  expect(() => supermux({ stateDirectory, agents: { codex: { sandbox: undefined, command: undefined } } })).not.toThrow()
  expect(() => supermux({ stateDirectory, agents: { codex: { model: null as never } } })).toThrow("supermux: agents.codex: Codex model")
  expect(() => supermux({ stateDirectory, agents: { claude: { permissionMode: null as never } } })).toThrow("supermux: agents.claude: Claude permissionMode")
})

test("every supermux error starts with supermux: and names what was typed", () => {
  const stateDirectory = temp("supermux-errors-")
  expect(() => supermux(undefined as never)).toThrow("supermux: options are required")
  expect(() => supermux({ agents: {} } as never)).toThrow("supermux: stateDirectory is required")
  expect(() => supermux({ stateDirectory } as never)).toThrow("supermux: agents is required")
  expect(() => supermux({ stateDirectory, agents: [] as never })).toThrow("supermux: agents must be an object keyed by agent name; pass driver instances to createCore() instead")
  expect(() => supermux({ stateDirectory, agents: {}, limits: null as never })).toThrow("supermux: limits must be an object")
  expect(() => supermux({ stateDirectory, agents: { gemini: {} } as never })).toThrow("supermux: unknown agent gemini")
  expect(() => supermux({ stateDirectory, agents: { toString: {} } as never })).toThrow("supermux: unknown agent toString")
  expect(() => supermux({ stateDirectory, agents: { claude: null as never } })).toThrow("supermux: agents.claude must be an object")
  expect(() => supermux({ stateDirectory, agents: { codex: { keeper: "fast" as never } } })).toThrow("supermux: agents.codex: keeper must be an object")
  expect(() => supermux({ stateDirectory, agents: { cursor: { mode: "yolo" as never } } })).toThrow("supermux: agents.cursor: Cursor mode must be agent, plan, or ask")
  expect(() => supermux({ stateDirectory, agents: { antigravity: {} as never } })).toThrow("supermux: agents.antigravity: home is required")
  // A factory's own error is wrapped with where it came from, and kept as the cause.
  const wrapped = thrown(() => supermux({ stateDirectory, agents: { codex: { model: "" } } }))
  expect(wrapped).toBeInstanceOf(TypeError)
  expect(wrapped.message).toBe("supermux: agents.codex: Codex model must be a nonempty string")
  expect((wrapped.cause as Error).message).toBe("Codex model must be a nonempty string")
})

test("keys the builder sets itself are refused, naming what to use instead", () => {
  const stateDirectory = temp("supermux-refused-")
  const build = (agents: unknown) => () => supermux({ stateDirectory, agents: agents as never })
  expect(build({ claude: { id: "mine" } })).toThrow("supermux: agents.claude: id is not an option here")
  expect(build({ claude: { permissions: { kind: "claude", permissionMode: "plan" } } })).toThrow("supermux: agents.claude: permissions is not an option here; use permissionMode")
  expect(build({ codex: { permissions: { kind: "codex", sandbox: "read-only", approvalPolicy: "never" } } })).toThrow("supermux: agents.codex: permissions is not an option here; use sandbox and approvalPolicy")
  expect(build({ grok: { permissions: { kind: "acp", policy: "ask", nativeMode: null } } })).toThrow("supermux: agents.grok: permissions is not an option here; use policy, nativeMode and askKinds")
  expect(build({ opencode: { permissions: { kind: "acp", policy: "ask", nativeMode: null } } })).toThrow("supermux: agents.opencode: permissions is not an option here; use policy, nativeMode and askKinds")
  expect(build({ cursor: { permissions: { kind: "acp", policy: "ask", nativeMode: "plan" } } })).toThrow("supermux: agents.cursor: permissions is not an option here; use policy, mode and askKinds")
  expect(build({ cursor: { nativeMode: "plan" } })).toThrow("supermux: agents.cursor: nativeMode is not an option here; use mode")
  expect(build({ antigravity: { home: "/shadow", permissions: { kind: "antigravity", mode: "plan" } } })).toThrow("supermux: agents.antigravity: permissions is not an option here; use mode")
  expect(build({ claude: { keeper: { stateDirectory: "/elsewhere" } } })).toThrow("supermux: agents.claude: keeper.stateDirectory is not an option here")
})

test("unknown options are refused, typos included", () => {
  const stateDirectory = temp("supermux-unknown-option-")
  const build = (agents: unknown, limits?: unknown) => () => supermux({ stateDirectory, agents: agents as never, ...(limits ? { limits: limits as never } : {}) })
  expect(build({ claude: { permisionMode: "plan" } })).toThrow("supermux: agents.claude: unknown option permisionMode")
  expect(build({ codex: { onRuntimeRequest: () => {} } })).toThrow("supermux: agents.codex: unknown option onRuntimeRequest")
  expect(build({ grok: { vendor: "grok" } })).toThrow("supermux: agents.grok: unknown option vendor")
  expect(build({ opencode: { normalizer: {} } })).toThrow("supermux: agents.opencode: unknown option normalizer")
  expect(build({ cursor: { openCodePollIntervalMs: 500 } })).toThrow("supermux: agents.cursor: unknown option openCodePollIntervalMs")
  expect(build({ antigravity: { home: "/shadow", shadow: "/shadow" } })).toThrow("supermux: agents.antigravity: unknown option shadow")
  expect(build({}, { maxPendng: 1 })).toThrow("supermux: limits: unknown option maxPendng")
})
