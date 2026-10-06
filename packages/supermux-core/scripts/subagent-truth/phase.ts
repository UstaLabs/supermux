/**
 * One phase of the live truth table for one agent, in its own process (so "after resume" really is
 * a new process). Spawned by scripts/subagent-truth.ts; not meant to be run by hand.
 *
 *   bun scripts/subagent-truth/phase.ts <main|resume> <config.json>
 *
 * Drives `createCore` with the real driver through every subagent state, attempts every action
 * the matrix lists, and writes what it observed to `<dir>/observations-<phase>.json` (judged by
 * the orchestrator). Every agent frame and action goes to `<dir>/{events,actions}-<phase>.ndjson`.
 */
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"
import { createCore } from "../../src/index.js"
import { claude } from "../../src/claude/index.js"
import { codex } from "../../src/codex/index.js"
import { cursor, grok, opencode } from "../../src/agents/index.js"
import { prepareCodexEnvironment } from "../../src/environment/index.js"
import type { AgentDriver, CoreEvent, SubagentSnapshot } from "../../src/types.js"
import { liveCell, rowFor, terminalStateOf, type ActionExpect, type AgentId, type LiveCell, type StateId, type TerminalStateId } from "./matrix.js"
import type { ActionOutcome, Observation, Snapshot } from "./judge.js"

export type PhaseConfig = {
  agent: AgentId
  /** This agent's scratch directory (state, keeper, workdir, logs). */
  dir: string
  command: string
  model: string
  effort?: string
}

const [phase, configPath] = process.argv.slice(2)
if ((phase !== "main" && phase !== "resume") || !configPath) {
  console.error("usage: bun scripts/subagent-truth/phase.ts <main|resume> <config.json>")
  process.exit(2)
}
const config = JSON.parse(readFileSync(configPath, "utf8")) as PhaseConfig
const { agent, dir } = config
const work = join(dir, "work"), stateDir = join(dir, "state"), keeperDir = join(dir, "keeper"), codexHome = join(dir, "codex-home")
for (const d of [work, stateDir, keeperDir]) mkdirSync(d, { recursive: true })
const eventsFile = join(dir, `events-${phase}.ndjson`)
const actionsFile = join(dir, `actions-${phase}.ndjson`)
const t0 = Date.now()
const ts = () => ((Date.now() - t0) / 1000).toFixed(1)
function act(kind: string, data: Record<string, unknown> = {}) {
  const line = JSON.stringify({ t: ts(), kind, ...data })
  appendFileSync(actionsFile, line + "\n")
  console.log(`[${agent}/${phase}] ${line.slice(0, 300)}`)
}

const keeper = { stateDirectory: keeperDir, limits: { parkedDeadlineMs: 600_000, journalMaxBytes: 64_000_000, connectTimeoutMs: 10_000 } }
const acpShared = {
  inheritEnv: true, mcpServers: [], setupTimeoutMs: 120_000, shutdownTimeoutMs: 5_000, maxFrameBytes: 16 << 20, maxOutstandingActivity: 256,
  cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 10_000, keeper, permissions: { kind: "acp" as const, policy: "ask" as const, nativeMode: null },
}

async function driver(): Promise<AgentDriver> {
  const { command, model } = config
  if (agent === "claude") return claude({ id: agent, command, args: [], inheritEnv: true, tools: "default", permissionPrompts: "none",
    permissions: { kind: "claude", permissionMode: "bypassPermissions" }, permissionMode: "bypassPermissions", partialMessages: true, model,
    setupTimeoutMs: 60_000, requestTimeoutMs: 30_000, shutdownTimeoutMs: 2_000, maxFrameBytes: 16 << 20, keeper })
  if (agent === "codex") {
    // A session-private CODEX_HOME like supermux gives every session, so the multi_agent v1 catalog
    // override (and its feature detection) runs exactly as in production. Prepared once; the
    // resume phase reuses it.
    if (phase === "main") {
      await prepareCodexEnvironment({ home: codexHome, workdir: work, mcpServers: [], skillsPaths: [], instructions: null, nativeMemory: false,
        credentials: { apiKey: process.env.OPENAI_API_KEY || null, canonicalHome: process.env.CODEX_HOME || join(homedir(), ".codex") } })
    }
    const effort = (config.effort ?? "low") as "low"
    return codex({ id: agent, command, args: ["app-server"], env: { CODEX_HOME: codexHome }, inheritEnv: true, sandbox: "danger-full-access", approvalPolicy: "never",
      permissionPrompts: "host", permissions: { kind: "codex", approvalPolicy: "never", sandbox: "danger-full-access" }, model, reasoningEffort: effort,
      setupTimeoutMs: 60_000, requestTimeoutMs: 30_000, shutdownTimeoutMs: 2_000, maxFrameBytes: 16 << 20, keeper })
  }
  if (agent === "grok") {
    const effort = config.effort as "low" | "medium" | "high" | undefined
    return grok({ id: agent, command, commandArgs: [], noLeader: true, model, ...(effort ? { reasoningEffort: effort } : {}), ...acpShared })
  }
  if (agent === "opencode") return opencode({ id: agent, command, model, ...acpShared })
  return cursor({ id: agent, command, commandArgs: [], model, mode: "agent", ...acpShared })
}

// ── Prompts (the parent model is asked to spawn / end subagents; slow sleeps give us a window) ──

const SLEEPS = (n: number) => `Run the shell command \`sleep 5\` ${n} times, each as its own separate tool call, one after another (not in one command).`
function spawnPrompt(label: string, sleeps: number, word: string): string {
  const task = `${SLEEPS(sleeps)} Then reply with ${word}.`
  switch (agent) {
    case "claude": return `Use the Agent tool with run_in_background: true and model "haiku" to launch ONE background subagent (description "${label}") with this prompt: "${task}" After launching it, end your turn immediately with one short line. Do not wait for it and do not check on it.`
    case "codex": return `Use spawn_agent to spawn ONE subagent with this task: "${task}" After spawning, end your turn immediately with one short line. Do not call wait and do not check on it.`
    case "grok": return `Use spawn_subagent with background: true to start ONE subagent (description "${label}") with this prompt: "${task}" After starting it, end your turn immediately with one short line. Do not wait for it.`
    case "opencode": return `Use the task tool (subagent_type "general", description "${label}") with this prompt: "${task}" Then reply with what it returned.`
    case "cursor": return `Use the Task tool to start ONE subagent (description "${label}") with this prompt: "${task}" Then reply with what it returned.`
  }
}
const PARENT_ENDS: Partial<Record<AgentId, string>> = {
  claude: `Use the Agent tool with run_in_background: true and model "haiku" to launch ONE background subagent (description "sleeper three") with the prompt "${SLEEPS(8)} Then reply with DONE-THREE." Wait about 5 seconds (use one Bash call: sleep 5), then stop that subagent with your TaskStop tool. Report what TaskStop returned.`,
  codex: `Use spawn_agent to spawn ONE subagent with the task "${SLEEPS(8)} Then reply with DONE-THREE." Then run \`sleep 5\` yourself, then call close_agent on that subagent. Report exactly what close_agent returned. Do not call wait.`,
  grok: `Use spawn_subagent with background: true to start ONE subagent (description "sleeper three") with the prompt "${SLEEPS(8)} Then reply with DONE-THREE." Then run \`sleep 5\` yourself, then kill that subagent with your kill_command_or_subagent tool. Report what it returned.`,
}
const askWord = (word: string) => `Reply with exactly the word ${word} and nothing else. Do not use tools.`

// ── Core + event tracking ──

const core = createCore({ stateDirectory: stateDir, agents: [await driver()], limits: { interruptTimeoutMs: 15_000, maxPending: 128, outstandingActivity: 256 } })
type Sub = { phases: string[]; texts: string[] }
const subs = new Map<string, Sub>()
const order: string[] = []
let session: Awaited<ReturnType<typeof core.sessions.create>>
core.subscribe((e: CoreEvent) => {
  appendFileSync(eventsFile, JSON.stringify({ t: ts(), ...e }, (_k, v) => v instanceof Error ? String(v.stack ?? v) : v) + "\n")
  if (e.type === "session.stateChanged") act("state", { state: e.state })
  if (e.type !== "session.event") return
  const b = e.event as any
  if (b.kind === "subagent") {
    let s = subs.get(b.subagentId)
    if (!s) { s = { phases: [], texts: [] }; subs.set(b.subagentId, s); order.push(b.subagentId) }
    s.phases.push(b.phase)
    act("subagent", { id: b.subagentId, phase: b.phase, endedBy: b.endedBy, canMessage: b.canMessage, canStop: b.canStop, src: b.actionsSource, why: b.cannotMessageReason ?? b.cannotStopReason, delivery: b.delivery })
  } else if (b.subagentId && (b.kind === "assistant-message" || b.kind === "assistant-delta")) {
    const s = subs.get(b.subagentId)
    if (s && b.kind === "assistant-message") s.texts.push(String(b.text))
  } else if (!b.subagentId && b.kind === "assistant-message") {
    act("parent-message", { text: String(b.text).slice(0, 200) })
    // A plan / usage limit is the account, not the library: say so in the report.
    if (QUOTA.test(String(b.text))) act("quota", { text: String(b.text).slice(0, 200) })
  } else if (b.kind === "warning" || b.kind === "error") {
    act(b.kind, { subagentId: b.subagentId, message: b.message })
  } else if (b.kind === "permission-request") {
    // Allow ONCE only (never "always"): the run must not change the user's agent settings.
    const option = b.options.find((o: any) => o.kind === "allow_once")
    act("permission", { subagentId: b.subagentId, tool: b.toolCall?.title, chosen: option?.optionId ?? option?.id })
    if (option) setTimeout(() => { session.requests.respond(b.requestId, { optionId: option.optionId ?? option.id }).catch((err: unknown) => act("permission-error", { err: String(err) })) }, 50)
  }
})

const QUOTA = /usage limit|upgrade your plan|rate.?limit|quota|insufficient credits/i
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))
async function waitFor(pred: () => boolean, ms: number, label: string): Promise<boolean> {
  const end = Date.now() + ms
  while (Date.now() < end) { if (pred()) return true; await sleep(250) }
  act("timeout", { label }); return false
}
const TERMINAL = new Set(["completed", "failed", "cancelled"])
const lastPhase = (id: string) => subs.get(id)?.phases.at(-1)
const isDone = (id: string) => TERMINAL.has(lastPhase(id) ?? "")
const idle = () => session.snapshot().state === "idle"
async function settleIdle(extraMs = 8000) {
  await waitFor(idle, 180_000, "idle")
  await sleep(extraMs) // a background child's completion notice can start a parent turn of its own
  await waitFor(idle, 180_000, "idle (after notices)")
}
const registry = (id: string): SubagentSnapshot | undefined => session.subagents().find(s => s.subagentId === id)
function pick(s: SubagentSnapshot | undefined): Snapshot | undefined {
  if (!s) return
  const { status, endedBy, canMessage, canStop, cannotMessageReason, cannotStopReason, actionsSource, messaging } = s
  return { status, ...(endedBy ? { endedBy } : {}), ...(canMessage !== undefined ? { canMessage } : {}), ...(canStop !== undefined ? { canStop } : {}),
    ...(cannotMessageReason ? { cannotMessageReason } : {}), ...(cannotStopReason ? { cannotStopReason } : {}), ...(actionsSource ? { actionsSource } : {}), ...(messaging ? { messaging } : {}) }
}
const source = rowFor(agent).actionsSource
const statusMatches = (cell: LiveCell) => (s: Snapshot) => s.status === cell.status && s.endedBy === cell.endedBy && (!source || s.actionsSource === source)
const flagsMatch = (action: "message" | "stop", expect: ActionExpect) => (s: Snapshot) => action === "message"
  ? s.canMessage === expect.ok && (expect.ok || s.cannotMessageReason === expect.reason)
  : s.canStop === expect.ok && (expect.ok || s.cannotStopReason === expect.reason)
/** Wait (bounded) until the registry agrees with the expectation, then return what it says. */
async function settle(id: string, want: (s: Snapshot) => boolean, ms = 45_000): Promise<Snapshot | undefined> {
  await waitFor(() => { const s = pick(registry(id)); return !!s && want(s) }, ms, `settle ${id}`)
  return pick(registry(id))
}

const observations: Observation[] = []
const where = (state: StateId, resumeOf?: TerminalStateId) => ({ state, ...(resumeOf ? { resumeOf } : {}) })
const cellOf = (state: StateId, resumeOf?: TerminalStateId) => liveCell(agent, resumeOf ?? state)!

async function observeStatus(state: StateId, id: string, resumeOf?: TerminalStateId) {
  const snapshot = await settle(id, statusMatches(cellOf(state, resumeOf)))
  observations.push({ kind: "status", ...where(state, resumeOf), subagentId: id, ...(snapshot ? { snapshot } : {}) })
  act("observe-status", { state, resumeOf, id, snapshot })
}

const err = (error: any): ActionOutcome => ({ ok: false, ...(error?.code ? { code: String(error.code) } : {}), message: String(error?.message ?? error) })

async function attemptMessage(state: StateId, id: string, word: string, resumeOf?: TerminalStateId) {
  const cell = cellOf(state, resumeOf)
  const before = await settle(id, s => flagsMatch("message", cell.message)(s) && (state === "running" || statusMatches(cell)(s)))
  const from = subs.get(id)?.phases.length ?? 0
  const textFrom = subs.get(id)?.texts.length ?? 0
  const text = state === "running" ? `When you finish, end your final answer with the word ${word}.` : askWord(word)
  act("message.call", { state, resumeOf, id, before })
  let outcome: ActionOutcome
  try {
    const r = await session.messageSubagent(id, [{ type: "text", text }])
    const delivery = await Promise.race([r.delivery, sleep(300_000).then(() => ({ status: "timeout" as const, reason: "no delivery within 300 s" }))])
    if (r.via === "relay") await Promise.race([r.receipt.completed, sleep(300_000)])
    let reran: boolean | undefined
    if (state !== "running") {
      reran = await waitFor(() => { const p = subs.get(id)?.phases.slice(from) ?? []; return p.includes("resumed") && TERMINAL.has(p.at(-1) ?? "") }, 300_000, `rerun ${id}`)
    }
    await settleIdle()
    const answered = (subs.get(id)?.texts.slice(textFrom) ?? []).some(t => t.toUpperCase().includes(word))
    outcome = { ok: true, via: r.via, delivery: delivery.status, ...("reason" in delivery && delivery.reason ? { deliveryReason: delivery.reason } : {}), ...(reran !== undefined ? { reran } : {}), answered }
  } catch (error) { outcome = err(error) }
  observations.push({ kind: "message", ...where(state, resumeOf), subagentId: id, ...(before ? { before } : {}), outcome })
  act("message.result", { state, resumeOf, id, outcome })
}

async function attemptStop(state: StateId, id: string, resumeOf?: TerminalStateId) {
  const cell = cellOf(state, resumeOf)
  const before = await settle(id, s => flagsMatch("stop", cell.stop)(s) && (state === "running" || statusMatches(cell)(s)))
  act("stop.call", { state, resumeOf, id, before })
  let outcome: ActionOutcome
  try {
    await session.stopSubagent(id)
    await waitFor(() => isDone(id), 120_000, `terminal after stop ${id}`)
    const after = pick(registry(id))
    outcome = { ok: true, ...(after?.status && after.status !== "running" ? { terminal: after.status } : {}), ...(after?.endedBy ? { endedBy: after.endedBy } : {}) }
  } catch (error) { outcome = err(error) }
  observations.push({ kind: "stop", ...where(state, resumeOf), subagentId: id, ...(before ? { before } : {}), outcome })
  act("stop.result", { state, resumeOf, id, outcome })
}

async function send(text: string, label: string) {
  act("send", { label, text: text.slice(0, 160) })
  const r = await session.send({ content: [{ type: "text", text }], whenBusy: "queue" })
  r.completed.then(c => act("send.completed", { label, status: c.status }), () => {})
  return r
}
const unreached = (state: StateId, reason: string, resumeOf?: TerminalStateId) => {
  observations.push({ kind: "unreached", ...where(state, resumeOf), reason })
  act("unreached", { state, resumeOf, reason })
}
const newSub = (before: number) => () => order.length > before
const idsFile = join(dir, "ids.json")
const sessionId = `truth-${agent}`

async function mainPhase() {
  session = await core.sessions.create({ id: sessionId, agent, cwd: work })
  act("created", { agentSessionId: session.snapshot().agentSessionId })

  // ── running (Message) → finished ──
  const r1 = await send(spawnPrompt("sleeper one", 4, "DONE-ONE"), "spawn-1")
  if (!(await waitFor(newSub(0), 240_000, "sub1 start"))) {
    for (const state of ["running", "finished", "stoppedByClient", "endedByParent", "afterResume"] as const) if (liveCell(agent, state) || state === "afterResume") unreached(state, "the parent did not spawn the first subagent")
    return
  }
  const sub1 = order[0]!
  await observeStatus("running", sub1)
  await attemptMessage("running", sub1, "PINEAPPLE")
  await waitFor(() => isDone(sub1), 300_000, "sub1 done")
  await Promise.race([r1.completed, sleep(300_000)])
  await settleIdle()
  await observeStatus("finished", sub1)
  await attemptStop("finished", sub1)
  await attemptMessage("finished", sub1, "MANGO")

  // ── running (Stop) → stopped by client ──
  const stopWorks = liveCell(agent, "running")!.stop.ok
  const before2 = order.length
  const r2 = await send(spawnPrompt("sleeper two", stopWorks ? 8 : 2, "DONE-TWO"), "spawn-2")
  if (await waitFor(newSub(before2), 240_000, "sub2 start")) {
    const sub2 = order[before2]!
    await observeStatus("running", sub2)
    await attemptStop("running", sub2)
    if (!isDone(sub2)) await waitFor(() => isDone(sub2), 300_000, "sub2 done")
    await Promise.race([r2.completed, sleep(300_000)])
    await settleIdle()
    if (liveCell(agent, "stoppedByClient")) {
      if (lastPhase(sub2) === "cancelled") {
        await observeStatus("stoppedByClient", sub2)
        await attemptStop("stoppedByClient", sub2)
        await attemptMessage("stoppedByClient", sub2, "KIWI")
      } else unreached("stoppedByClient", `the stopped subagent ended ${lastPhase(sub2) ?? "never"}`)
    }
  } else {
    unreached("running", "the parent did not spawn the second subagent (Stop untested)")
    if (liveCell(agent, "stoppedByClient")) unreached("stoppedByClient", "the parent did not spawn the second subagent")
  }

  // ── ended by parent ──
  const prompt = PARENT_ENDS[agent]
  if (liveCell(agent, "endedByParent") && prompt) {
    const before3 = order.length
    const r3 = await send(prompt, "spawn-3-parent-ends")
    await Promise.race([r3.completed, sleep(360_000)])
    await settleIdle()
    const sub3 = order[before3]
    if (!sub3) unreached("endedByParent", "the parent did not spawn the third subagent")
    else {
      await waitFor(() => isDone(sub3), 120_000, "sub3 done")
      const s = pick(registry(sub3))
      if (s?.status !== "cancelled" || s.endedBy !== "parent") {
        await observeStatus("endedByParent", sub3) // records the mismatch (e.g. it finished before the parent ended it)
        unreached("endedByParent", `the parent did not end it (status ${s?.status}, endedBy ${s?.endedBy ?? "-"})`)
      } else {
        await observeStatus("endedByParent", sub3)
        await attemptStop("endedByParent", sub3)
        await attemptMessage("endedByParent", sub3, "KIWITWO")
      }
    }
  }

  writeFileSync(idsFile, JSON.stringify({ order, registry: session.subagents() }, null, 2))
  act("registry", { subagents: session.subagents().map(pick) })
  await session.close({ mode: "shutdown" })
}

async function resumePhase() {
  const saved = JSON.parse(readFileSync(idsFile, "utf8")) as { order: string[]; registry: SubagentSnapshot[] }
  session = await core.sessions.resume(sessionId)
  act("resumed", { agentSessionId: session.snapshot().agentSessionId })
  if (!saved.registry.length) { unreached("afterResume", "the first process had no subagents"); return }
  await sleep(3000)
  for (const before of saved.registry) {
    const resumeOf = terminalStateOf(before)
    if (!resumeOf || !liveCell(agent, resumeOf)) { act("skip-after-resume", { id: before.subagentId, status: before.status, endedBy: before.endedBy }); continue }
    await observeStatus("afterResume", before.subagentId, resumeOf)
    await attemptStop("afterResume", before.subagentId, resumeOf)
    await attemptMessage("afterResume", before.subagentId, "LEMON", resumeOf)
  }
  act("registry", { subagents: session.subagents().map(pick) })
  await session.close({ mode: "shutdown" })
}

let exitCode = 0
try {
  if (phase === "main") await mainPhase()
  else await resumePhase()
} catch (error) {
  exitCode = 1
  act("fatal", { error: String((error as Error)?.stack ?? error) })
  unreached(phase === "main" ? "running" : "afterResume", `fatal: ${String((error as Error)?.message ?? error)}`)
} finally {
  writeFileSync(join(dir, `observations-${phase}.json`), JSON.stringify(observations, null, 2))
  await core.close({ agents: "shutdown" }).catch(error => act("core-close-error", { error: String(error) }))
  act("exit", { exitCode })
  process.exit(exitCode)
}
