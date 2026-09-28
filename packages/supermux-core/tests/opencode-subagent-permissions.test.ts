import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { existsSync, writeFileSync } from "node:fs"
import { mkdtemp, readFile, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { opencode } from "../src/agents/index.js"
import { openCodeAskToAcp, openCodeToolKind } from "../src/acp/opencode-server.js"
import { createCore } from "../src/index.js"
import type { CoreEvent, PermissionsSpec } from "../src/types.js"
import type { EventEnvelope, NormalizedBody } from "../src/events/normalized.js"
import { TEST_LIMITS, nextId } from "./helpers.js"

setDefaultTimeout(20_000)
const agentScript = join(dirname(fileURLToPath(import.meta.url)), "fixtures/opencode-server-agent.mjs")
const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
afterEach(async () => {
  for (const core of cores.splice(0)) await core.close({ agents: "shutdown" }).catch(() => {})
  for (const dir of dirs.splice(0)) await rm(dir, { recursive: true, force: true }).catch(() => {})
})

type Event = EventEnvelope & NormalizedBody
type AcpPolicy = Extract<PermissionsSpec, { kind: "acp" }>

async function setup(permissions: AcpPolicy) {
  const dir = await mkdtemp(join(tmpdir(), "opencode-sidechannel-"))
  dirs.push(dir)
  const trace = join(dir, "trace.ndjson")
  const shim = join(dir, "opencode-shim.sh")
  writeFileSync(shim, `#!/bin/sh\nexec "${process.execPath}" ${JSON.stringify(agentScript)} "$@"\n`, { mode: 0o755 })
  const driver = opencode({
    id: "agent", command: shim, inheritEnv: true, mcpServers: [], env: { FAKE_TRACE: trace },
    permissions, setupTimeoutMs: 5000, shutdownTimeoutMs: 200, maxFrameBytes: 16 * 1024 * 1024, maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 2000, openCodePollIntervalMs: 40,
    keeper: { stateDirectory: join(dir, "keeper"), limits: { parkedDeadlineMs: 5000, journalMaxBytes: 5_000_000, connectTimeoutMs: 4000 } },
  })
  const core = createCore({ stateDirectory: join(dir, "core"), agents: [driver], limits: { ...TEST_LIMITS, interruptTimeoutMs: 2000 } })
  cores.push(core)
  const events: Event[] = []
  core.subscribe((e: CoreEvent) => { if (e.type === "session.event") events.push(e.event) })
  const session = await core.sessions.create({ id: nextId("oc-side-"), agent: "agent", cwd: dir })
  const replies = async () => existsSync(trace) ? (await readFile(trace, "utf8")).trim().split("\n").filter(Boolean).map(l => JSON.parse(l)) : []
  return { session, events, replies }
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
const ALLOW: AcpPolicy = { kind: "acp", policy: "auto-approve", nativeMode: null }
const ASK: AcpPolicy = { kind: "acp", policy: "ask", nativeMode: null }

test("child ask maps to an ACP-shaped request with OpenCode's own kinds and options", () => {
  expect(openCodeToolKind("bash")).toBe("execute")
  expect(openCodeToolKind("edit")).toBe("edit")
  expect(openCodeToolKind("webfetch")).toBe("fetch")
  const req = openCodeAskToAcp({ id: "per_1", sessionID: "ses_c", permission: "bash", metadata: { command: "ls" }, tool: { callID: "call_b" } })
  expect(req).toMatchObject({ sessionId: "ses_c", toolCall: { toolCallId: "call_b", kind: "execute", title: "bash", rawInput: { command: "ls" } } })
  expect(req.options.map(o => o.kind)).toEqual(["allow_once", "allow_always", "reject_once"])
})

test("allow mode: a subagent's ask that OpenCode never relays over ACP is auto-approved through the side channel", async () => {
  const { session, events, replies } = await setup(ALLOW)
  const receipt = await session.send({ content: text("spawn"), whenBusy: "queue" })
  expect((await receipt.completed).status).toBe("completed")
  expect(await replies()).toEqual([{ reply: "once", id: "per_1" }])
  const auto = events.find(e => e.kind === "permission-auto")
  expect(auto).toMatchObject({ subagentId: "call_task", optionId: "once" })
  // The child session is learned while the task runs, before its completion frame.
  const sub = events.filter(e => e.kind === "subagent" && e.subagentId === "call_task")
  const bound = sub.findIndex(e => (e as { nativeId?: string }).nativeId === "ses_child")
  const done = sub.findIndex(e => (e as { phase: string }).phase === "completed")
  expect(bound).toBeGreaterThan(-1)
  expect(bound).toBeLessThan(done)
  expect(events.some(e => e.kind === "permission-request")).toBe(false)
})

test("ask mode: the subagent's ask reaches the user labelled with the subagent, and the answer unblocks the child", async () => {
  const { session, events, replies } = await setup(ASK)
  const receipt = await session.send({ content: text("spawn"), whenBusy: "queue" })
  await until(() => session.requests.list().length === 1)
  const request = events.find(e => e.kind === "permission-request")
  expect(request).toMatchObject({ subagentId: "call_task" })
  const pending = session.requests.list()[0]!
  await session.requests.respond(pending.requestId, { optionId: "once" })
  expect((await receipt.completed).status).toBe("completed")
  expect(await replies()).toEqual([{ reply: "once", id: "per_1" }])
})

test("ask mode: a rejection is sent back as reject, so the child never hangs", async () => {
  const { session, replies } = await setup(ASK)
  const receipt = await session.send({ content: text("spawn"), whenBusy: "queue" })
  await until(() => session.requests.list().length === 1)
  await session.requests.respond(session.requests.list()[0]!.requestId, { optionId: "reject" })
  expect((await receipt.completed).status).toBe("completed")
  expect(await replies()).toEqual([{ reply: "reject", id: "per_1" }])
})

test("live mode switch: a session started in ask mode and switched to allow auto-approves the child", async () => {
  const { session, events, replies } = await setup(ASK)
  await session.setPermissions(ALLOW)
  const receipt = await session.send({ content: text("spawn"), whenBusy: "queue" })
  expect((await receipt.completed).status).toBe("completed")
  expect(await replies()).toEqual([{ reply: "once", id: "per_1" }])
  expect(events.some(e => e.kind === "permission-request")).toBe(false)
})

test("interrupting the parent withdraws the subagent's pending request; nothing but a reject is ever sent", async () => {
  const { session, replies } = await setup(ASK)
  const receipt = await session.send({ content: text("spawn"), whenBusy: "queue" })
  await until(() => session.requests.list().length === 1)
  await session.interrupt({ pending: "discard" })
  await until(() => session.requests.list().length === 0)
  await receipt.completed
  // The child was cancelled with its parent; the side channel never approves it afterwards.
  const sent = await replies()
  expect(sent.length).toBeGreaterThan(0)
  expect(sent.every(r => r.cancelled === true || r.reply === "reject")).toBe(true)
})
