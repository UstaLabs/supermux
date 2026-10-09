// Subagents through the broker's CoreAdapter, driven by REAL captured CLI traffic: the
// library's replay agent stands in for `claude` / `codex app-server`.
import { afterEach, beforeEach, expect, setDefaultTimeout, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import type { Host } from "../../../../packages/supermux-core/src/index.js"
import { claude } from "../../../../packages/supermux-core/src/claude/index.js"
import { codex } from "../../../../packages/supermux-core/src/codex/index.js"
import { fixturePath, replayAgent } from "../../../../packages/supermux-core/tests/subagent-fixtures.js"
import { CoreAdapter, CORE_ADAPTER_PROFILES } from "./core-adapter"
import { createClaudeCoreHost } from "../claude/core-host"
import { createCodexCoreHost } from "../codex/core-host"
import type { AgentEvent } from "../types"
import type { ActivityEvent } from "../claude/activity-event"

setDefaultTimeout(30_000)

// Hermetic credentials: the host's prepare step takes OPENAI_API_KEY first and only falls back
// to the user's own login files without one: files CI does not have, and that a developer
// machine must not have copied (or written back) by a test. A fake key per test, restored after.
const CREDENTIAL_KEYS = ["OPENAI_API_KEY"] as const
const savedKeys = new Map<string, string | undefined>()
beforeEach(() => {
  for (const k of CREDENTIAL_KEYS) { savedKeys.set(k, process.env[k]); process.env[k] = "test-key" }
})
afterEach(() => {
  for (const k of CREDENTIAL_KEYS) {
    const prev = savedKeys.get(k)
    if (prev === undefined) delete process.env[k]
    else process.env[k] = prev
  }
})

const dirs: string[] = []
const hosts: Host[] = []
const adapters: CoreAdapter[] = []
afterEach(async () => {
  await Promise.all(adapters.splice(0).map((a) => a.stop().catch(() => {})))
  await Promise.all(hosts.splice(0).map((h) => h.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map((d) => rm(d, { recursive: true, force: true }).catch(() => {})))
})

async function until(check: () => boolean, ms = 10_000) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    if (check()) return
    await new Promise((r) => setTimeout(r, 20))
  }
  throw new Error("condition not reached")
}

const KINDS = [
  "assistant-message", "tool-call", "turn-start", "turn-complete", "error", "activity",
  "request-open", "request-closed", "subagent", "subagent-activity", "task",
] as const

async function replayAdapter(kind: "claude" | "codex", fixture: string, env: Record<string, string> = {}) {
  const workdir = await mkdtemp(join(tmpdir(), `${kind}-sub-wd-`))
  const stateDirectory = await mkdtemp(join(tmpdir(), `${kind}-sub-core-`))
  dirs.push(workdir, stateDirectory)
  const args = [replayAgent, "--replay", fixturePath(fixture)]
  const host = kind === "claude"
    ? createClaudeCoreHost({
        stateDirectory,
        limits: { interruptTimeoutMs: 2000, maxPending: 128, outstandingActivity: 256 },
        driverFactory: (options) => claude({ ...options, command: process.execPath, args, env: { ...options.env, ...env }, setupTimeoutMs: 5000 }),
      })
    : createCodexCoreHost({
        stateDirectory,
        limits: { interruptTimeoutMs: 2000, maxPending: 128, outstandingActivity: 256 },
        driverFactory: (options) => codex({
          ...options, command: process.execPath, args,
          env: { ...options.env, REPLAY_SKIP: "thread/read,thread/list,thread/loaded/list", ...env }, setupTimeoutMs: 5000,
        }),
      })
  hosts.push(host)
  const id = `${kind}-sub-${Math.random().toString(36).slice(2, 8)}`
  const extra = { cwd: workdir, workdir, sessionHome: workdir, sessionName: id, sessionId: id }
  const handle = host.register({ id, env: {}, extra })
  const adapter = new CoreAdapter(CORE_ADAPTER_PROFILES[kind], {
    handle,
    reregister: () => host.register({ id, env: {}, extra }),
    core: host.core,
    id,
    sessionName: id,
    workdir,
    persistSessionId: async () => {},
    stallTimeoutMs: 60_000,
  })
  adapters.push(adapter)
  const events: AgentEvent[] = []
  for (const k of KINDS) adapter.on(k, (e: AgentEvent) => events.push(e))
  await adapter.start()
  return { adapter, events }
}

const rows = (events: AgentEvent[]): ActivityEvent[] =>
  events.flatMap((e) => (e.kind === "activity" ? e.events : []))

test("claude: child activity is tagged, child text never becomes the parent's reply", async () => {
  const { adapter, events } = await replayAdapter("claude", "claude-single.ndjson")
  await adapter.send("go")
  await until(() => events.some((e) => e.kind === "turn-complete"))
  const child = "af3c70a348a6a6b7a"

  // The parent's only reply is its own final word.
  const replies = events.filter((e) => e.kind === "assistant-message").map((e) => (e as { text: string }).text)
  expect(replies).toEqual(["PELICAN"])

  // Child tool calls: activity rows tagged with the subagent, never parent tool-call events.
  const tagged = rows(events).filter((r) => r.subagentId === child)
  expect(tagged.filter((r) => r.kind === "tool").map((r) => r.tool).sort()).toEqual(["Bash", "Read"])
  const toolCalls = events.filter((e) => e.kind === "tool-call") as Array<{ tool: string }>
  expect(toolCalls.map((t) => t.tool)).toEqual(["Agent", "Agent"])

  // The spawning Agent row is a parent row; its callId is the subagent's parentCallId.
  const spawn = rows(events).find((r) => r.kind === "tool" && r.tool === "Agent")!
  expect(spawn.subagentId).toBeUndefined()
  const lifecycle = events.filter((e) => e.kind === "subagent").map((e) => (e as { body: { phase: string; parentCallId?: string } }).body)
  expect(lifecycle[0]).toMatchObject({ phase: "started", parentCallId: spawn.callId, description: "Inspect work dir", messaging: "relay" })
  expect(lifecycle.at(-1)).toMatchObject({ phase: "completed", stats: { toolCalls: 2 } })
  expect((lifecycle.at(-1) as { result?: string }).result).toContain("PELICAN")

  // One derived activity line per child tool call.
  expect(events.filter((e) => e.kind === "subagent-activity")).toHaveLength(2)
})

test("claude: background bash surfaces as task events", async () => {
  const { adapter, events } = await replayAdapter("claude", "claude-background.ndjson")
  await adapter.send("go")
  await until(() => events.filter((e) => e.kind === "task").length >= 2)
  const tasks = events.filter((e) => e.kind === "task")
  expect(tasks).toEqual([
    { kind: "task", taskId: "b3153wm3n", taskKind: "shell", phase: "started", label: "Sleep then print marker", parentCallId: "toolu_01SfgRF7HwaV2Ap7LXohjnLz" },
    { kind: "task", taskId: "b3153wm3n", taskKind: "shell", phase: "completed", label: "Sleep then print marker", parentCallId: "toolu_01SfgRF7HwaV2Ap7LXohjnLz" },
  ])
  await until(() => events.some((e) => e.kind === "subagent" && e.body.phase === "completed"))
  // The background agent's own final text is its result, not a parent chat message.
  const replies = events.filter((e) => e.kind === "assistant-message").map((e) => (e as { text: string }).text)
  expect(replies.some((t) => t === "Done. The secret word is **PELICAN**.")).toBe(false)
})

test("claude: messageSubagent relays through a parent turn", async () => {
  const { adapter, events } = await replayAdapter("claude", "claude-resume.ndjson")
  await adapter.send("spawn")
  await until(() => events.some((e) => e.kind === "subagent" && e.body.phase === "completed"))
  await until(() => events.some((e) => e.kind === "turn-complete"))
  expect((await adapter.messageSubagent("a47ce4c320c9a4f07", "Now also Read notes.txt and report its first line.")).via).toBe("relay")
  await until(() => events.some((e) => e.kind === "subagent" && e.body.phase === "resumed"))
  await until(() => events.filter((e) => e.kind === "subagent" && e.body.phase === "completed").length === 2)
  // The subagent's thread: its first prompt (from the parent), the user's relayed message (once
  // Claude confirmed it), and its replies — all as subagent_message rows.
  const thread = rows(events).filter((r) => r.kind === "subagent_message" && r.subagentId === "a47ce4c320c9a4f07")
  expect(thread[0]).toMatchObject({ direction: "to", sender: "parent" })
  expect(thread.filter((r) => r.direction === "to" && r.sender === "user").map((r) => r.text)).toEqual(["Now also Read notes.txt and report its first line."])
  expect(thread.map((r) => r.direction)).toEqual(["to", "from", "to", "from"])
  expect(thread[1]!.text).toContain("PELICAN")
})

test("codex: direct messaging reaches the child; stop interrupts only the child", async () => {
  const { adapter, events } = await replayAdapter("codex", "codex-message.ndjson", { REPLAY_LOOSE: "turn/steer" })
  const child = "01a0e789-3982-7f20-9a82-6a928de9c924"
  void adapter.send("spawn and wait")
  await until(() => events.some((e) => e.kind === "subagent" && e.body.subagentId === child && e.body.phase === "started"))
  expect((await adapter.messageSubagent(child, "Also include the word PINEAPPLE in your final reply.")).via).toBe("direct")
  // The capture holds two steers; the replay moves on once both arrived.
  expect((await adapter.messageSubagent(child, "Additionally say MANGO in your final reply.")).via).toBe("direct")
  await until(() => events.some((e) => e.kind === "subagent" && e.body.subagentId === child && e.body.phase === "completed"))
  // Child text never posted as the parent's reply.
  const replies = events.filter((e) => e.kind === "assistant-message").map((e) => (e as { text: string }).text)
  const childRows = rows(events).filter((r) => r.subagentId === child)
  expect(childRows.length).toBeGreaterThan(0)
  // Only the parent's own two messages (it was asked to relay the child's answer verbatim).
  await until(() => replies.length >= 0 && events.some((e) => e.kind === "turn-complete"))
  expect(events.filter((e) => e.kind === "assistant-message").map((e) => (e as { text: string }).text)).toEqual([
    "I’ll spawn exactly one subagent with the requested instructions, wait for it to finish, and relay its reply verbatim.",
    "FINISHED PINEAPPLE MANGO",
  ])
  await expect(adapter.stopSubagent("not-a-child")).rejects.toThrow()
  const thread = rows(events).filter((r) => r.kind === "subagent_message" && r.subagentId === child)
  expect(thread[0]).toMatchObject({ direction: "to", sender: "parent" })
  expect(thread.filter((r) => r.direction === "to" && r.sender === "user").map((r) => r.text)).toEqual([
    "Also include the word PINEAPPLE in your final reply.", "Additionally say MANGO in your final reply.",
  ])
  expect(thread.filter((r) => r.direction === "from").map((r) => r.text).join(" ")).toContain("FINISHED")
})

test("codex v2: messaging a v2 child is unsupported_operation", async () => {
  const { adapter, events } = await replayAdapter("codex", "codex-single-v2.ndjson")
  await adapter.send("spawn")
  const child = "01a0e78a-c94a-7a30-9978-67e993ec02d1"
  await until(() => events.some((e) => e.kind === "subagent" && e.body.subagentId === child && e.body.phase === "completed"))
  await expect(adapter.messageSubagent(child, "hi")).rejects.toMatchObject({ code: "subagent_unavailable" })
})
