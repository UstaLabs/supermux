import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { mkdtemp, readFile, rm } from "node:fs/promises"
import { existsSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { claude, claudeRelayPrompt } from "../src/claude/index.js"
import { createCore } from "../src/index.js"
import type { CoreEvent } from "../src/types.js"
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

async function setup(fixture: string, extra: Record<string, unknown> = {}) {
  const dir = await mkdtemp(join(tmpdir(), "claude-subagents-"))
  dirs.push(dir)
  const trace = join(dir, "trace.ndjson")
  const driver = claude({
    id: "claude", command: process.execPath, args: [replayAgent, "--replay", fixturePath(fixture)],
    env: { REPLAY_TRACE: trace }, inheritEnv: true, tools: "default", permissionPrompts: "none",
    permissions: { kind: "claude", permissionMode: "bypassPermissions" }, partialMessages: true,
    setupTimeoutMs: 5000, requestTimeoutMs: 5000, shutdownTimeoutMs: 200, maxFrameBytes: 16 * 1024 * 1024,
    keeper: { stateDirectory: join(dir, "keeper"), limits: { parkedDeadlineMs: 5000, journalMaxBytes: 5_000_000, connectTimeoutMs: 4000 } },
    ...extra,
  })
  const core = createCore({ stateDirectory: join(dir, "core"), agents: [driver], limits: { ...TEST_LIMITS, interruptTimeoutMs: 2000 } })
  cores.push(core)
  const events: Event[] = []
  const states: string[] = []
  core.subscribe((e: CoreEvent) => {
    if (e.type === "session.event") events.push(e.event)
    if (e.type === "session.stateChanged") states.push(e.state)
  })
  const session = await core.sessions.create({ id: nextId("claude-sub-"), agent: "claude", cwd: dir })
  const lines = async () => existsSync(trace) ? (await readFile(trace, "utf8")).trim().split("\n").filter(Boolean).map(l => JSON.parse(l)) : []
  return { session, events, states, lines }
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

test("background completion turns (no user frame) become real Core turns", async () => {
  const { session, events } = await setup("claude-background.ndjson")
  const receipt = await session.send({ content: text("go"), whenBusy: "queue" })
  expect((await receipt.completed).status).toBe("completed")
  await until(() => events.filter(e => e.kind === "turn-complete").length >= 3 && session.snapshot().state === "idle")
  const starts = events.filter(e => e.kind === "turn-start")
  const completes = events.filter(e => e.kind === "turn-complete")
  expect(starts).toHaveLength(3)
  expect(completes).toHaveLength(3)
  const final = events.find(e => e.kind === "assistant-message" && e.text.includes("Agent completed"))
  expect(final?.turnId).toBe(starts[2]!.turnId)
  const done = events.find(e => e.kind === "subagent" && e.phase === "completed")
  expect(done?.subagentId).toBe("a671a6d5bcca52ff1")
  expect(session.snapshot().state).toBe("idle")
})

test("relay: messageSubagent queues a SendMessage instruction and the subagent resumes", async () => {
  const { session, events, lines } = await setup("claude-resume.ndjson")
  const first = await session.send({ content: text("spawn"), whenBusy: "queue" })
  expect((await first.completed).status).toBe("completed")
  const result = await session.messageSubagent("a47ce4c320c9a4f07", text("Now also Read notes.txt and report its first line."))
  expect(result.via).toBe("relay")
  if (result.via !== "relay") throw new Error("expected relay")
  expect((await result.receipt.completed).status).toBe("completed")
  const users = (await lines()).filter(l => l.type === "user")
  expect(users).toHaveLength(2)
  const relayed = users[1].message.content[0].text as string
  expect(relayed).toContain("SendMessage")
  expect(relayed).toContain('"a47ce4c320c9a4f07"')
  expect(relayed).toContain("<relay>\nNow also Read notes.txt and report its first line.\n</relay>")
  await until(() => events.some(e => e.kind === "subagent" && e.phase === "resumed"))
  await until(() => events.filter(e => e.kind === "subagent" && e.phase === "completed").length === 2)
})

test("relay prompt is text-only and exact", () => {
  expect(() => claudeRelayPrompt("a1", [{ type: "image", data: "x", mimeType: "image/png" }])).toThrow("only text")
  const [block] = claudeRelayPrompt("a1", text("hi\nthere"))
  expect(block).toMatchObject({ type: "text" })
  expect((block as { text: string }).text).toContain('to: "a1"')
  expect((block as { text: string }).text.endsWith("<relay>\nhi\nthere\n</relay>")).toBe(true)
})

test("stopSubagent sends stop_task for the task id and the subagent is cancelled", async () => {
  const { session, events, lines } = await setup("claude-stop.ndjson")
  const receipt = await session.send({ content: text("slow"), whenBusy: "queue" })
  await until(() => events.some(e => e.kind === "subagent" && e.phase === "progress" && !!e.activity))
  await session.stopSubagent("a8cf04e1a7620a931")
  const stop = (await lines()).find(l => l.type === "control_request" && l.request?.subtype === "stop_task")
  expect(stop?.request).toEqual({ subtype: "stop_task", task_id: "a8cf04e1a7620a931" })
  expect((await receipt.completed).status).toBe("completed")
  await until(() => events.some(e => e.kind === "subagent" && e.phase === "cancelled"))
  expect(events.filter(e => e.kind === "subagent" && (e.phase === "completed" || e.phase === "failed"))).toHaveLength(0)
})

test("a subagent's permission request carries its subagentId", async () => {
  const { session, events } = await setup("claude-permissions.ndjson", {
    permissionPrompts: "host", permissions: { kind: "claude", permissionMode: "default" },
  })
  // The replay does not wait for answers: the turn ends and the requests resolve as cancelled.
  const receipt = await session.send({ content: text("write"), whenBusy: "queue" })
  expect((await receipt.completed).status).toBe("completed")
  const asked = events.filter(e => e.kind === "permission-request")
  expect(asked.length).toBe(2)
  expect(asked.every(e => e.subagentId === "a8cc44fde3a4f1b0a")).toBe(true)
})

test("messageSubagent / stopSubagent are typed unsupported without runtime support", async () => {
  const dir = await mkdtemp(join(tmpdir(), "claude-subagents-"))
  dirs.push(dir)
  const core = createCore({
    stateDirectory: dir,
    limits: TEST_LIMITS,
    agents: [{ id: "bare", async open(ctx) { return { agentSessionId: "n", capabilities: { resume: false, steer: false, fork: false, detach: false }, async prompt() { return { stopReason: "end_turn" } }, async interrupt() {}, async close() {} } } }],
  })
  cores.push(core)
  const session = await core.sessions.create({ id: nextId("bare-"), agent: "bare", cwd: dir })
  await expect(session.messageSubagent("x", text("hi"))).rejects.toMatchObject({ code: "unsupported_operation" })
  await expect(session.stopSubagent("x")).rejects.toMatchObject({ code: "unsupported_operation" })
})
