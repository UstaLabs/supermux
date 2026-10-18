import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readlinkSync, rmSync, unlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { defaults, supermux } from "../src/index.js"
import { prepareAntigravityEnvironment } from "../src/environment/index.js"

setDefaultTimeout(30_000)

const fixture = (name: string) => fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url))
const dirs: string[] = []
const temp = (prefix: string) => { const dir = mkdtempSync(join(tmpdir(), prefix)); dirs.push(dir); return dir }
afterEach(() => { for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true }) })
const text = (value: string) => [{ type: "text" as const, text: value }]
const jsonLines = (path: string): any[] => readFileSync(path, "utf8").trim().split("\n").filter(Boolean).map(line => JSON.parse(line))

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

test("claude with {} sends prompts to the host, streams partial messages and keeps its keeper in the state directory", async () => {
  const dir = temp("supermux-claude-")
  const trace = join(dir, "env.json")
  const core = supermux({
    stateDirectory: join(dir, "state"),
    agents: { claude: { command: process.execPath, args: [fixture("claude-agent.mjs")], env: { ENV_TRACE: trace, EXPECT_DEFAULT_DENY: "0" } } },
  })
  try {
    const session = await core.sessions.create({ id: "c1", agent: "claude", cwd: dir })
    const { argv } = JSON.parse(readFileSync(trace, "utf8")) as { argv: string[] }
    expect(argv[argv.indexOf("--permission-prompts") + 1]).toBe("host")
    expect(argv).toContain("--include-partial-messages")
    expect(argv).not.toContain("--permission-mode")
    const receipt = await session.send({ content: text("hello"), whenBusy: "queue" })
    expect((await receipt.completed).status).toBe("completed")
    expect(existsSync(join(dir, "state", "keepers", "c1"))).toBe(true)
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

test("cursor with {} opens in agent mode, its permissions carry the same mode, and finishes a turn", async () => {
  const dir = temp("supermux-cursor-")
  const trace = join(dir, "trace.jsonl")
  const core = supermux({
    stateDirectory: join(dir, "state"),
    agents: { cursor: { command: process.execPath, commandArgs: [fixture("cursor-agent.mjs")], env: { TRACE: trace } } },
  })
  const modeWrites = () => jsonLines(trace).filter(line => line?.setConfig?.configId === "mode").map(line => line.setConfig.value)
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

test("antigravity launches under the given shadow home, mirrors the given userHome and keeps agy's own default mode", async () => {
  const dir = temp("supermux-agy-run-")
  const user = join(dir, "user"), shadow = join(dir, "shadow"), log = join(dir, "agy.log")
  mkdirSync(join(user, ".gemini", "antigravity-cli"), { recursive: true })
  await prepareAntigravityEnvironment({ home: shadow, userHome: user, platform: process.platform })
  const cliLink = join(shadow, ".gemini", "antigravity-cli")
  unlinkSync(cliLink) // the driver links it again, to the userHome it was given
  // The fake agy refuses at once and writes nothing (a turn would write a conversation through
  // that link), so not even a wrong userHome could reach a real home.
  const core = supermux({
    stateDirectory: join(dir, "state"),
    agents: { antigravity: { command: process.execPath, args: [fixture("antigravity-agent.mjs")], env: { FAKE_AGY_LOG: log, FAKE_AGY_MODE: "ineligible" }, home: shadow, userHome: user } },
  })
  try {
    await expect(core.sessions.create({ id: "a1", agent: "antigravity", cwd: dir })).rejects.toThrow("Eligibility check failed")
    const spawn = jsonLines(log).find(entry => entry.type === "spawn")
    expect(spawn.home).toBe(shadow)
    expect(spawn.argv).not.toContain("--mode")
    expect(spawn.argv).not.toContain("--dangerously-skip-permissions")
    expect(readlinkSync(cliLink)).toBe(join(user, ".gemini", "antigravity-cli"))
  } finally {
    await core.close({ agents: "shutdown" })
  }
})

test("limits merge over defaults.core.limits and are still validated", async () => {
  const core = supermux({ stateDirectory: temp("supermux-limits-"), agents: {}, limits: { interruptTimeoutMs: 1_000 } })
  await core.close({ agents: "shutdown" })
  expect(() => supermux({ stateDirectory: temp("supermux-limits-"), agents: {}, limits: { maxPending: 0 } })).toThrow("maxPending")
})

test("keeper limits merge one level over the agent's defaults", () => {
  const stateDirectory = temp("supermux-keeper-")
  // Merged: parkedDeadlineMs still comes from defaults, so the strict factory names journalMaxBytes.
  expect(() => supermux({ stateDirectory, agents: { claude: { keeper: { journalMaxBytes: 0 } } } })).toThrow("journalMaxBytes")
  expect(() => supermux({ stateDirectory, agents: { grok: { keeper: { journalMaxBytes: 0 } } } })).toThrow("journalMaxBytes")
})

test("undefined means not given; null is a value the strict factory judges", () => {
  const stateDirectory = temp("supermux-values-")
  expect(() => supermux({ stateDirectory, agents: { codex: { sandbox: undefined, command: undefined } } })).not.toThrow()
  expect(() => supermux({ stateDirectory, agents: { codex: { model: null as never } } })).toThrow("Codex model")
  expect(() => supermux({ stateDirectory, agents: { claude: { permissionMode: null as never } } })).toThrow("permissionMode")
})

test("unknown agent names are refused", () => {
  expect(() => supermux({ stateDirectory: temp("supermux-unknown-"), agents: { gemini: {} } as never })).toThrow("unknown agent gemini")
  expect(() => supermux({ stateDirectory: temp("supermux-unknown-"), agents: { toString: {} } as never })).toThrow("unknown agent toString")
})

test("an agent's options must be an object", () => {
  expect(() => supermux({ stateDirectory: temp("supermux-shape-"), agents: { claude: null as never } })).toThrow("agents.claude must be an object")
  expect(() => supermux({ stateDirectory: temp("supermux-shape-"), agents: { codex: { keeper: "fast" as never } } })).toThrow("agents.codex.keeper must be an object")
})

test("antigravity needs a home", () => {
  expect(() => supermux({ stateDirectory: temp("supermux-agy-"), agents: { antigravity: {} as never } })).toThrow("antigravity home is required")
})
