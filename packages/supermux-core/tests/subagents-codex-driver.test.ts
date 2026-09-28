import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { existsSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { codex } from "../src/codex/index.js"
import { createCore } from "../src/index.js"
import type { CoreEvent } from "../src/types.js"
import type { EventEnvelope, NormalizedBody } from "../src/events/normalized.js"
import { TEST_LIMITS, nextId } from "./helpers.js"
import { fixturePath, replayAgent, rows } from "./subagent-fixtures.js"

setDefaultTimeout(20_000)
const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
afterEach(async () => {
  for (const core of cores.splice(0)) await core.close({ agents: "shutdown" }).catch(() => {})
  for (const dir of dirs.splice(0)) await rm(dir, { recursive: true, force: true }).catch(() => {})
})

type Event = EventEnvelope & NormalizedBody
const SKIP = "thread/read,thread/list,thread/loaded/list"

async function setup(fixture: string, env: Record<string, string> = {}) {
  const dir = await mkdtemp(join(tmpdir(), "codex-subagents-"))
  dirs.push(dir)
  const trace = join(dir, "trace.ndjson")
  const path = fixture.startsWith("/") ? fixture : fixturePath(fixture)
  const driver = codex({
    id: "codex", command: process.execPath, args: [replayAgent, "--replay", path],
    env: { REPLAY_TRACE: trace, REPLAY_SKIP: SKIP, ...env }, inheritEnv: true,
    sandbox: "workspace-write", approvalPolicy: "never", permissionPrompts: "host",
    permissions: { kind: "codex", approvalPolicy: "never", sandbox: "workspace-write" },
    setupTimeoutMs: 5000, requestTimeoutMs: 5000, shutdownTimeoutMs: 200, maxFrameBytes: 16 * 1024 * 1024,
    keeper: { stateDirectory: join(dir, "keeper"), limits: { parkedDeadlineMs: 5000, journalMaxBytes: 5_000_000, connectTimeoutMs: 4000 } },
  })
  const core = createCore({ stateDirectory: join(dir, "core"), agents: [driver], limits: { ...TEST_LIMITS, interruptTimeoutMs: 2000 } })
  cores.push(core)
  const events: Event[] = []
  const updates: any[] = []
  core.subscribe((e: CoreEvent) => {
    if (e.type === "session.event") events.push(e.event)
    if (e.type === "session.update") updates.push(e.update.value)
  })
  const session = await core.sessions.create({ id: nextId("codex-sub-"), agent: "codex", cwd: dir })
  const lines = async () => existsSync(trace) ? (await readFile(trace, "utf8")).trim().split("\n").filter(Boolean).map(l => JSON.parse(l)) : []
  const childTurnStarted = (child: string) => updates.some(u => u.method === "turn/started" && u.params?.threadId === child)
  return { session, events, lines, dir, childTurnStarted }
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

test("child thread frames are forwarded with subagentId and never drive the parent turn", async () => {
  const { session, events, childTurnStarted } = await setup("codex-single.ndjson")
  const receipt = await session.send({ content: text("spawn"), whenBusy: "queue" })
  expect((await receipt.completed).status).toBe("completed")
  const child = "01a0e787-c710-7bb0-bad2-9add4711b713"
  await until(() => events.some(e => e.kind === "turn-complete"))
  expect(events.filter(e => e.kind === "turn-start")).toHaveLength(1)
  expect(events.filter(e => e.kind === "turn-complete")).toHaveLength(1)
  const sub = events.filter(e => e.kind === "subagent" && e.subagentId === child)
  expect(sub[0]).toMatchObject({ phase: "started", messaging: "direct" })
  expect(sub.filter(e => e.kind === "subagent" && e.phase === "completed")).toHaveLength(1)
  expect(events.filter(e => e.kind === "tool-call" && e.tool === "commandExecution").every(e => e.subagentId === child)).toBe(true)
  expect(events.some(e => e.kind === "command-output" && e.subagentId === child && e.delta.includes("SUBAGENT_OK"))).toBe(true)
  // The child's first frame (thread/status/changed) arrived before spawnAgent named it: held, not lost.
  expect(childTurnStarted(child)).toBe(true)
})

test("direct messaging: steer while the child runs, a new child turn when it is idle", async () => {
  const { session, events, lines, childTurnStarted } = await setup("codex-message.ndjson", { REPLAY_LOOSE: "turn/steer" })
  const child = "01a0e789-3982-7f20-9a82-6a928de9c924"
  const childTurn = "01a0e789-3b18-7f11-8d3f-7ea6b3fe8041"
  const receipt = await session.send({ content: text("spawn and wait"), whenBusy: "queue" })
  await until(() => childTurnStarted(child))
  expect(await session.messageSubagent(child, text("Also include the word PINEAPPLE in your final reply."))).toEqual({ via: "direct" })
  expect(await session.messageSubagent(child, text("Additionally say MANGO in your final reply."))).toEqual({ via: "direct" })
  expect((await receipt.completed).status).toBe("completed")
  await until(() => events.some(e => e.kind === "subagent" && e.subagentId === child && e.phase === "completed"))
  expect(await session.messageSubagent(child, text("Reply with exactly: CHILD_DIRECT_OK"))).toEqual({ via: "direct" })
  await until(() => events.some(e => e.kind === "subagent" && e.phase === "completed" && e.result === "CHILD_DIRECT_OK"))
  expect(events.some(e => e.kind === "subagent" && e.subagentId === child && e.phase === "resumed")).toBe(true)

  const sent = await lines()
  const steers = sent.filter(l => l.method === "turn/steer")
  expect(steers).toHaveLength(2)
  for (const steer of steers) expect(steer.params).toMatchObject({ threadId: child, expectedTurnId: childTurn })
  const starts = sent.filter(l => l.method === "turn/start" && l.params.threadId === child)
  expect(starts).toHaveLength(1)
  expect(starts[0].params.model).toBeUndefined()
  expect(starts[0].params.input[0].text).toBe("Reply with exactly: CHILD_DIRECT_OK")
})

test("stopSubagent interrupts the child's running turn only", async () => {
  const { session, lines, childTurnStarted } = await setup("codex-message.ndjson")
  const child = "01a0e789-3982-7f20-9a82-6a928de9c924"
  void session.send({ content: text("spawn"), whenBusy: "queue" })
  await until(() => childTurnStarted(child))
  await session.stopSubagent(child)
  const interrupts = (await lines()).filter(l => l.method === "turn/interrupt")
  expect(interrupts).toEqual([expect.objectContaining({ params: { threadId: child, turnId: "01a0e789-3b18-7f11-8d3f-7ea6b3fe8041" } })])
  await expect(session.stopSubagent("not-a-child")).rejects.toThrow("Unknown Codex subagent")
})

test("a child's approval request is routed to the host with subagentId and answered on its thread", async () => {
  const child = "01a0e789-3982-7f20-9a82-6a928de9c924"
  const childTurn = "01a0e789-3b18-7f11-8d3f-7ea6b3fe8041"
  const source = rows("codex-message.ndjson")
  const at = source.findIndex(r => r.d === "a" && r.m.method === "turn/started" && r.m.params.threadId === child)
  const approval = { d: "a", m: { id: "child-approval-1", method: "item/commandExecution/requestApproval", params: { threadId: child, turnId: childTurn, itemId: "exec-x", command: "rm -rf build", cwd: "/work", approvalId: "ap-1" } } }
  const synthetic = [...source.slice(0, at + 1), approval, ...source.slice(at + 1)]
  const dir = await mkdtemp(join(tmpdir(), "codex-fixture-"))
  dirs.push(dir)
  const file = join(dir, "child-approval.ndjson")
  await writeFile(file, synthetic.map(r => JSON.stringify(r)).join("\n") + "\n")
  const { session, events, lines } = await setup(file)
  void session.send({ content: text("spawn"), whenBusy: "queue" })
  await until(() => session.requests.list().length > 0)
  const [request] = session.requests.list()
  expect(request!.body).toMatchObject({ kind: "permission-request", subagentId: child })
  await session.requests.respond(request!.requestId, { optionId: "allow_once" })
  await until(async () => (await lines()).some(l => l.id === "child-approval-1"))
  expect((await lines()).find(l => l.id === "child-approval-1")).toEqual({ id: "child-approval-1", result: { decision: "accept" } })
  expect(events.some(e => e.kind === "permission-request" && e.subagentId === child)).toBe(true)
})

test("multi_agent_v2 children refuse direct input: typed unsupported", async () => {
  const { session, events } = await setup("codex-single-v2.ndjson")
  const receipt = await session.send({ content: text("spawn"), whenBusy: "queue" })
  expect((await receipt.completed).status).toBe("completed")
  const child = "01a0e78a-c94a-7a30-9978-67e993ec02d1"
  expect(events.filter(e => e.kind === "subagent" && e.subagentId === child && e.phase === "completed")).toHaveLength(1)
  await expect(session.messageSubagent(child, text("hi"))).rejects.toMatchObject({ code: "unsupported_operation" })
})
