import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { mkdtemp, readFile, rm } from "node:fs/promises"
import { existsSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { cursor, grok, opencode } from "../src/agents/index.js"
import { cursorRelayPrompt, GROK_STOP_PROBE_ID } from "../src/acp/index.js"
import { REASON } from "../src/subagent-actions.js"
import { createCore } from "../src/index.js"
import type { AgentDriver, CoreEvent } from "../src/types.js"
import type { EventEnvelope, NormalizedBody } from "../src/events/normalized.js"
import { TEST_LIMITS, nextId } from "./helpers.js"
import { fixturePath, replayAgent } from "./subagent-fixtures.js"

setDefaultTimeout(20_000)
const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
afterEach(async () => {
  for (const core of cores.splice(0)) await core.close({ agents: "shutdown" }).catch(() => {})
  for (const dir of dirs.splice(0)) await rm(dir, { recursive: true, force: true }).catch(() => {})
})

type Event = EventEnvelope & NormalizedBody
type Vendor = "cursor" | "grok" | "opencode"

function common(dir: string, env: Record<string, string>) {
  return {
    id: "agent", inheritEnv: true, mcpServers: [], env,
    permissions: { kind: "acp" as const, policy: "ask" as const, nativeMode: null },
    setupTimeoutMs: 5000, shutdownTimeoutMs: 200, maxFrameBytes: 16 * 1024 * 1024, maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 2000,
    keeper: { stateDirectory: join(dir, "keeper"), limits: { parkedDeadlineMs: 5000, journalMaxBytes: 5_000_000, connectTimeoutMs: 4000 } },
  }
}

async function setup(vendor: Vendor, fixture: string, env: Record<string, string> = {}) {
  const dir = await mkdtemp(join(tmpdir(), `${vendor}-subagents-`))
  dirs.push(dir)
  const trace = join(dir, "trace.ndjson")
  const replayArgs = [replayAgent, "--replay", fixturePath(fixture)]
  const base = common(dir, { REPLAY_TRACE: trace, ...env })
  const driver: AgentDriver = vendor === "cursor"
    ? cursor({ ...base, command: process.execPath, commandArgs: replayArgs })
    : vendor === "grok"
      ? grok({ ...base, command: process.execPath, commandArgs: replayArgs, noLeader: true })
      : opencodeDriver(dir, base, replayArgs)
  const core = createCore({ stateDirectory: join(dir, "core"), agents: [driver], limits: { ...TEST_LIMITS, interruptTimeoutMs: 2000 } })
  cores.push(core)
  const events: Event[] = []
  core.subscribe((e: CoreEvent) => { if (e.type === "session.event") events.push(e.event) })
  const session = await core.sessions.create({ id: nextId(`${vendor}-sub-`), agent: "agent", cwd: dir })
  const lines = async () => existsSync(trace) ? (await readFile(trace, "utf8")).trim().split("\n").filter(Boolean).map(l => JSON.parse(l)) : []
  return { session, events, lines }
}

/** OpenCode's factory fixes argv to `acp …`; the replayer ignores it, so run it as `node replay-agent.mjs acp …`. */
function opencodeDriver(dir: string, base: ReturnType<typeof common>, replayArgs: string[]): AgentDriver {
  const shim = join(dir, "opencode-shim.sh")
  writeFileSync(shim, `#!/bin/sh\nexec "${process.execPath}" ${replayArgs.map(a => JSON.stringify(a)).join(" ")} "$@"\n`, { mode: 0o755 })
  return opencode({ ...base, command: shim })
}

async function until(check: () => boolean | Promise<boolean>, ms = 8000) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    if (await check()) return
    await new Promise(r => setTimeout(r, 20))
  }
  throw new Error("condition not reached")
}

const text = (t: string) => [{ type: "text" as const, text: t }]

test("cursor: advertises the subagents extension, answers cursor/task, attributes child streams", async () => {
  const { session, events, lines } = await setup("cursor", "cursor-single.ndjson")
  const receipt = await session.send({ content: text("spawn"), whenBusy: "queue" })
  expect((await receipt.completed).status).toBe("completed")
  const sent = await lines()
  expect(sent.find(l => l.method === "initialize").params.clientCapabilities).toEqual({ _meta: { subagents: true } })
  const answer = await until(async () => (await lines()).some(l => l.id !== undefined && !l.method && l.result && Object.keys(l.result).length === 0)).then(lines)
  expect(answer.some(l => l.error)).toBe(false)
  const id = "462f4e89-c491-4083-a70f-74cf3fb5cc6d"
  const sub = events.filter(e => e.kind === "subagent" && e.subagentId === id)
  expect(sub[0]).toMatchObject({ phase: "started", messaging: "relay" })
  expect(sub.filter(e => e.kind === "subagent" && e.phase === "completed")).toHaveLength(1)
  expect(events.filter(e => e.kind === "tool-call" && e.subagentId === id).length).toBeGreaterThan(0)
  expect(events.filter(e => e.kind === "turn-start")).toHaveLength(1)
})

test("cursor: messageSubagent relays through a Task resume prompt on the main session", async () => {
  const a = "f061c95f-03ac-4e52-82c3-50d89900860d"
  const { session, events, lines } = await setup("cursor", "cursor-parallel.ndjson", { REPLAY_SKIP_SESSION: a })
  const first = await session.send({ content: text("spawn two"), whenBusy: "queue" })
  expect((await first.completed).status).toBe("completed")
  const result = await session.messageSubagent(a, text("What was the second line of alpha.txt?"))
  expect(result.via).toBe("relay")
  if (result.via !== "relay") throw new Error("relay expected")
  expect((await result.receipt.completed).status).toBe("completed")
  const prompts = (await lines()).filter(l => l.method === "session/prompt")
  expect(prompts).toHaveLength(2)
  expect(prompts[1].params.prompt[0].text).toContain(`resume="${a}"`)
  expect(prompts[1].params.prompt[0].text).toContain("<relay>\nWhat was the second line of alpha.txt?\n</relay>")
  await until(() => events.some(e => e.kind === "subagent" && e.subagentId === a && e.phase === "resumed"))
  await expect(session.stopSubagent(a)).rejects.toMatchObject({ code: "subagent_unavailable", message: "Cursor can't stop subagents" })
})

test("cursor relay prompt is text-only", () => {
  expect(() => cursorRelayPrompt("x", [{ type: "image", data: "", mimeType: "image/png" }])).toThrow("only text")
})

test("grok: child permission is attributed; direct message loads the child once and streams it as the subagent", async () => {
  const { session, events, lines } = await setup("grok", "grok-direct.ndjson")
  const child = "01a0e79c-0dc6-7c11-be7e-6f610217410c"
  const first = await session.send({ content: text("spawn"), whenBusy: "queue" })
  expect((await first.completed).status).toBe("completed")
  expect(events.filter(e => e.kind === "permission-request").map(e => e.subagentId)).toEqual([child])
  expect(events.filter(e => e.kind === "subagent" && e.subagentId === child && e.phase === "completed")).toHaveLength(1)
  const turnsBefore = events.filter(e => e.kind === "turn-start").length
  const toolCallsBefore = events.filter(e => e.kind === "tool-call").length

  expect((await session.messageSubagent(child, text("Direct client message: what was the content of the file you read?"))).via).toBe("direct")
  await until(() => events.filter(e => e.kind === "subagent" && e.subagentId === child && e.phase === "completed").length === 2)
  const sub = events.filter(e => e.kind === "subagent" && e.subagentId === child).map(e => (e as { phase: string }).phase).filter(p => p !== "progress")
  expect(sub).toEqual(["started", "completed", "resumed", "completed"])
  const last = events.filter(e => e.kind === "subagent" && e.subagentId === child).at(-1) as { result?: string }
  expect(last.result).toContain("gamma")
  // The load replay is history: no replayed tool calls; the child prompt is not a parent turn.
  expect(events.filter(e => e.kind === "tool-call").length).toBe(toolCallsBefore)
  expect(events.filter(e => e.kind === "turn-start").length).toBe(turnsBefore)
  const sent = await lines()
  expect(sent.filter(l => l.method === "session/load").map(l => l.params.sessionId)).toEqual([child])
  expect(sent.filter(l => l.method === "session/prompt").map(l => l.params.sessionId).at(-1)).toBe(child)
  expect(sent.find(l => l.method === "initialize").params.clientCapabilities).toEqual({})
})

test("opencode: the task's child session is the direct target", async () => {
  const { session, events, lines } = await setup("opencode", "opencode-parallel.ndjson", { REPLAY_SKIP: "session/list" })
  const first = "toolu_8d7699c34b0c40d5adbdd7dc"
  const receipt = await session.send({ content: text("spawn two"), whenBusy: "queue" })
  expect((await receipt.completed).status).toBe("completed")
  expect(events.find(e => e.kind === "subagent" && e.subagentId === first && e.phase === "completed")).toMatchObject({ nativeId: "ses_f186da878ffeN5toowCa9sxjjj" })
  expect((await session.messageSubagent(first, text("Direct client message #2"))).via).toBe("direct")
  await until(() => events.filter(e => e.kind === "subagent" && e.subagentId === first && e.phase === "completed").length === 2)
  expect(events.some(e => e.kind === "assistant-delta" && e.subagentId === first)).toBe(true)
  const sent = await lines()
  expect(sent.filter(l => l.method === "session/load").map(l => l.params.sessionId)).toEqual(["ses_f186da878ffeN5toowCa9sxjjj"])
  expect(sent.find(l => l.method === "initialize").params.clientCapabilities).toEqual({})
})

test("grok BUG 2/3: a running background child is stopped with Grok's own x.ai/subagent/cancel and is not messageable meanwhile", async () => {
  // Wire captured live from grok 1.0.46 (probe 2026-10-03): spawn_subagent background, then cancel.
  const { session, events, lines } = await setup("grok", "grok-cancel.ndjson")
  const parent = "01a10368-6b79-73a2-8de2-c3b711ede1ce"
  const child = "01a10368-7812-7ec0-9cdf-b6c26fa9b719"
  const receipt = await session.send({ content: text("spawn a background sleeper"), whenBusy: "queue" })
  await until(() => session.requests.list().length > 0 || events.some(e => e.kind === "subagent" && e.subagentId === child))
  for (const request of session.requests.list()) await session.requests.respond(request.requestId, { optionId: "allow-once" }).catch(() => {})
  expect((await receipt.completed).status).toBe("completed")
  await until(() => events.some(e => e.kind === "subagent" && e.subagentId === child))
  expect(events.find(e => e.kind === "subagent" && e.subagentId === child)).toMatchObject({
    phase: "started", canMessage: false, cannotMessageReason: "Grok can message it once it finishes", canStop: true,
  })
  await expect(session.messageSubagent(child, text("hi"))).rejects.toMatchObject({ code: "subagent_unavailable" })
  await session.stopSubagent(child)
  const cancel = (await lines()).filter(l => l.method === "_x.ai/subagent/cancel")
  // The first is the startup feature probe (an id no subagent has).
  expect(cancel.map(l => l.params)).toEqual([{ sessionId: parent, subagentId: GROK_STOP_PROBE_ID }, { sessionId: parent, subagentId: child }])
  expect((await lines()).some(l => l.method === "session/cancel" && l.params.sessionId === child)).toBe(false)
  await until(() => events.some(e => e.kind === "subagent" && e.subagentId === child && e.phase === "cancelled"))
  expect(events.find(e => e.kind === "subagent" && e.subagentId === child && e.phase === "cancelled")).toMatchObject({ endedBy: "client", canMessage: true, canStop: false })
})

// ── Startup feature detection: an undocumented control that disappears degrades cleanly ──

async function spawnGrokBackground(env: Record<string, string>) {
  const ctx = await setup("grok", "grok-cancel.ndjson", env)
  const child = "01a10368-7812-7ec0-9cdf-b6c26fa9b719"
  const receipt = await ctx.session.send({ content: text("spawn a background sleeper"), whenBusy: "queue" })
  await until(() => ctx.session.requests.list().length > 0 || ctx.events.some(e => e.kind === "subagent" && e.subagentId === child))
  for (const request of ctx.session.requests.list()) await ctx.session.requests.respond(request.requestId, { optionId: "allow-once" }).catch(() => {})
  expect((await receipt.completed).status).toBe("completed")
  await until(() => ctx.events.some(e => e.kind === "subagent" && e.subagentId === child))
  return { ...ctx, child }
}

test("grok without _x.ai/subagent/cancel (probe answers -32601): a running background child has no Stop, with the reason", async () => {
  const { session, events, lines, child } = await spawnGrokBackground({ REPLAY_GROK_NO_CANCEL: "1" })
  expect((await lines()).filter(l => l.method === "_x.ai/subagent/cancel").map(l => l.params.subagentId)).toEqual([GROK_STOP_PROBE_ID])
  expect(events.find(e => e.kind === "subagent" && e.subagentId === child)).toMatchObject({
    phase: "started", canMessage: false, cannotMessageReason: REASON.grokRunning, canStop: false, cannotStopReason: REASON.grokNoStop,
  })
  await expect(session.stopSubagent(child)).rejects.toMatchObject({ code: "subagent_unavailable", message: REASON.grokNoStop })
  // Refused by the flags: nothing more is sent to Grok.
  expect((await lines()).filter(l => l.method === "_x.ai/subagent/cancel")).toHaveLength(1)
})

test("grok whose cancel disappears after the probe: Stop is refused with the reason and the flags flip off", async () => {
  const { session, events, child } = await spawnGrokBackground({ REPLAY_GROK_NO_CANCEL: "late" })
  expect(events.find(e => e.kind === "subagent" && e.subagentId === child)).toMatchObject({ phase: "started", canStop: true })
  await expect(session.stopSubagent(child)).rejects.toMatchObject({ code: "subagent_unavailable", message: REASON.grokNoStop })
  await until(() => events.filter(e => e.kind === "subagent" && e.subagentId === child).at(-1)?.canStop === false)
  expect(events.filter(e => e.kind === "subagent" && e.subagentId === child).at(-1)).toMatchObject({ phase: "progress", canStop: false, cannotStopReason: REASON.grokNoStop })
})

test("cursor that does not echo subagent support after the _meta opt-in: no live child stream, no Message (no agent id to relay to), no Stop", async () => {
  const { session, events, lines } = await setup("cursor", "cursor-baseline.ndjson")
  const receipt = await session.send({ content: text("spawn"), whenBusy: "queue" })
  expect((await receipt.completed).status).toBe("completed")
  expect((await lines()).find(l => l.method === "initialize").params.clientCapabilities).toEqual({ _meta: { subagents: true } })
  const id = "call-12a9bd3f-f585-488d-a37d-851c15980e55-0\nfc_1de06a53-13f1-96b0-8433-f783c17490bc_0"
  const sub = events.filter(e => e.kind === "subagent" && e.subagentId === id)
  expect(sub[0]).toMatchObject({ phase: "started", messaging: "none", canMessage: false, cannotMessageReason: REASON.cursorNoIds, canStop: false, cannotStopReason: REASON.cursorNoStop })
  expect(sub.at(-1)).toMatchObject({ phase: "completed", canMessage: false, canStop: false })
  expect(events.some(e => e.kind !== "subagent" && e.subagentId === id)).toBe(false)
  await expect(session.messageSubagent(id, text("hi"))).rejects.toMatchObject({ code: "subagent_unavailable", message: REASON.cursorNoIds })
  await expect(session.stopSubagent(id)).rejects.toMatchObject({ code: "subagent_unavailable", message: REASON.cursorNoStop })
  // Nothing was relayed through the parent.
  expect((await lines()).filter(l => l.method === "session/prompt")).toHaveLength(1)
})
