// #129: Claude's model list comes from Claude Code itself: the `models` its initialize reply lists, read with its own
// login (on a Mac, its own Keychain item), so the broker needs no credential and can never raise a prompt.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { EventEmitter } from "events"
import { claudeModelsFromAgent, discoverClaude, discoverClaudeModelsViaCli, CLAUDE_INIT_ARGS, type ClaudeControlProcess } from "./claude-models"
import { followAdapterModels, LearnedModels } from "./learned"
import { AgentKind } from "../../shared/agents"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })
const learnedDir = () => { const d = mkdtempSync(join(tmpdir(), "claude-models-")); dirs.push(d); return join(d, "learned") }

const ENTRIES = [
  { value: "default", name: "Default (recommended)", resolved: "claude-opus-5-5", efforts: ["low", "max"], fast: true as const },
  { value: "opus", name: "Opus 5.5", description: "For complex work", resolved: "claude-opus-5-5", efforts: ["low", "medium", "high", "max"], fast: true as const },
  { value: "claude-haiku-4-5-20251001", name: "Haiku 4.5" },
]
const MODELS = claudeModelsFromAgent(ENTRIES)

test("Claude's own entries become the picker's models: its Default is the picker's own, efforts and fast mode kept", () => {
  expect(MODELS).toEqual([
    { id: "opus", displayName: "Opus 5.5", agent: AgentKind.Claude, reasoningLevels: [{ id: "low" }, { id: "medium" }, { id: "high" }, { id: "max" }], supportsFast: true },
    { id: "claude-haiku-4-5-20251001", displayName: "Haiku 4.5", agent: AgentKind.Claude },
  ])
})

test("a live Claude session's initialize models are learned for Claude", () => {
  const learned = new LearnedModels(learnedDir())
  const adapter = Object.assign(new EventEmitter(), { offeredModels: undefined as typeof ENTRIES | undefined })
  const seen: unknown[] = []
  followAdapterModels({ agent: AgentKind.Claude, adapter, learned, onLearned: (agent, models) => seen.push([agent, models]) })
  adapter.offeredModels = ENTRIES
  adapter.emit("models-update")
  expect(learned.get(AgentKind.Claude)).toEqual(MODELS)
  expect(seen).toEqual([[AgentKind.Claude, MODELS]])
})

/** A fake `claude` that answers the one initialize request with [models] (or never, or exits). */
function fakeClaude(answer: "models" | "silent" | "exit", models: unknown[] = []) {
  const calls = { args: [] as string[], env: {} as Record<string, string | undefined>, written: [] as string[], killed: 0 }
  const spawn = (args: string[], env: Record<string, string | undefined>): ClaudeControlProcess => {
    calls.args = args
    calls.env = env
    let onLine: (line: string) => void = () => {}
    let onExit: () => void = () => {}
    return {
      write(line) {
        calls.written.push(line)
        const req = JSON.parse(line)
        if (answer === "models") queueMicrotask(() => onLine(JSON.stringify({ type: "control_response", response: { subtype: "success", request_id: req.request_id, response: { models } } })))
        if (answer === "exit") queueMicrotask(() => onExit())
      },
      onLine(fn) { onLine = fn },
      onExit(fn) { onExit = fn },
      kill() { calls.killed++ },
    }
  }
  return { spawn, calls }
}

const RAW = [
  { value: "opus", displayName: "Opus 5.5", resolvedModel: "claude-opus-5-5", supportedEffortLevels: ["low", "high"], supportsFastMode: true },
  { value: "sonnet", displayName: "Sonnet 5.5", resolvedModel: "claude-sonnet-5-5" },
]

test("a control-only claude: hooks off, one initialize and nothing else (no turn), its models, then it is stopped", async () => {
  const fake = fakeClaude("models", RAW)
  const models = await discoverClaudeModelsViaCli({ spawn: fake.spawn, timeoutMs: 1000 })
  expect(models.map((m) => [m.id, m.displayName])).toEqual([["opus", "Opus 5.5"], ["sonnet", "Sonnet 5.5"]])
  expect(models[0]!.reasoningLevels).toEqual([{ id: "low" }, { id: "high" }])
  expect(models[0]!.supportsFast).toBe(true)
  expect(fake.calls.args).toEqual(CLAUDE_INIT_ARGS)
  expect(CLAUDE_INIT_ARGS.join(" ")).toContain(`--settings {"disableAllHooks":true}`)
  expect(fake.calls.written.map((l) => JSON.parse(l).request.subtype)).toEqual(["initialize"])
  expect(fake.calls.killed).toBe(1)
})

test("an account's discovery runs claude with that account's env", async () => {
  const fake = fakeClaude("models", RAW)
  await discoverClaudeModelsViaCli({ spawn: fake.spawn, timeoutMs: 1000, launch: { env: { CLAUDE_CONFIG_DIR: "/acct" } } })
  expect(fake.calls.env.CLAUDE_CONFIG_DIR).toBe("/acct")
})

test("a claude that never answers, or exits, gives an empty list and is stopped", async () => {
  for (const answer of ["silent", "exit"] as const) {
    const fake = fakeClaude(answer)
    expect(await discoverClaudeModelsViaCli({ spawn: fake.spawn, timeoutMs: 50 })).toEqual([])
    expect(fake.calls.killed).toBe(1)
  }
})

test("discovery order: a fresh list from a live session; else the control-only claude (learned); else the API", async () => {
  const learned = new LearnedModels(learnedDir())
  const order: string[] = []
  let now = 1_000_000
  const deps = {
    learned,
    now: () => now,
    cli: async () => { order.push("cli"); return MODELS },
    api: async () => { order.push("api"); return [] },
    accounts: async () => [],
  }
  // No list yet: the control-only claude runs, and what it found is learned.
  expect(await discoverClaude(deps)).toEqual(MODELS)
  expect(order).toEqual(["cli"])
  expect(learned.get(AgentKind.Claude)).toEqual(MODELS)
  // Fresh: no process at all.
  now += 60_000
  expect(await discoverClaude(deps)).toEqual(MODELS)
  expect(order).toEqual(["cli"])
  // Stale (over 6 hours): asked again.
  now += 7 * 3_600_000
  await discoverClaude(deps)
  expect(order).toEqual(["cli", "cli"])
})

test("when claude lists nothing, the API is the fallback (a readable token), then the added accounts", async () => {
  const learned = new LearnedModels(learnedDir())
  const order: string[] = []
  const api = [{ id: "claude-opus-5-5", displayName: "Claude Opus 5.5", agent: AgentKind.Claude }]
  const models = await discoverClaude({
    learned, now: () => 0,
    cli: async (launch) => { order.push(`cli:${launch ? "acct" : "system"}`); return [] },
    api: async (launch) => { order.push(`api:${launch ? "acct" : "system"}`); return launch ? api : [] },
    accounts: async () => [async () => ({ env: { CLAUDE_CONFIG_DIR: "/acct" } })],
  })
  expect(order).toEqual(["cli:system", "api:system", "cli:acct", "api:acct"])
  expect(models).toEqual(api)
})
