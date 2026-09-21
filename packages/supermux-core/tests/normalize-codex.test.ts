import { afterEach, describe, expect, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCodexNormalizer } from "../src/codex/normalize.js"
import { createCore } from "../src/index.js"
import type { AgentDriver, AgentRuntime, AgentUpdate, CoreEvent, DriverContext } from "../src/types.js"
import { TEST_LIMITS, nextId } from "./helpers.js"

const native = (method: string, params: unknown, extra?: { id?: unknown; replay?: boolean }): AgentUpdate => ({
  protocol: "native",
  value: extra?.id != null ? { method, params, id: extra.id } : { method, params },
  ...(extra?.replay ? { replay: true } : {}),
})

const kinds = (bodies: { kind: string }[]) => bodies.map(b => b.kind)

describe("codex normalizer", () => {
  test("agentMessage item completed", () => {
    const n = createCodexNormalizer()
    const out = n(native("item/completed", { item: { type: "agentMessage", id: "m1", text: "hello" } }))
    expect(out).toEqual([{ kind: "assistant-message", messageId: "m1", text: "hello" }])
  })

  test("agentMessage delta then flush coalesces", () => {
    const n = createCodexNormalizer()
    expect(n(native("item/agentMessage/delta", { itemId: "m1", delta: "Hel" }))[0]).toMatchObject({ kind: "assistant-delta", messageId: "m1", text: "Hel" })
    n(native("item/agentMessage/delta", { itemId: "m1", delta: "lo" }))
    expect(n.flush()).toEqual([{ kind: "assistant-message", messageId: "m1", text: "Hello" }])
  })

  test("reasoning item with empty content is redacted", () => {
    const n = createCodexNormalizer()
    const out = n(native("item/completed", { item: { type: "reasoning", id: "r1", summary: ["s"], content: [] } }))
    expect(out[0]).toMatchObject({ kind: "reasoning", reasoningId: "r1", redacted: true, summary: ["s"] })
    expect("text" in out[0]!).toBe(false)
  })

  test("reasoning deltas", () => {
    const n = createCodexNormalizer()
    expect(n(native("item/reasoning/textDelta", { itemId: "r1", delta: "think", contentIndex: 0 }))[0]).toMatchObject({ kind: "reasoning-delta", text: "think" })
  })

  test("plan item and turn/plan/updated", () => {
    const n = createCodexNormalizer()
    expect(n(native("item/started", { item: { type: "plan", id: "p1", text: "do it" } }))[0]).toMatchObject({ kind: "plan" })
    const plan = n(native("turn/plan/updated", { explanation: "why", plan: [{ step: "a", status: "inProgress" }] }))[0]
    expect(plan).toMatchObject({ kind: "plan", explanation: "why", entries: [{ content: "a", status: "in_progress" }] })
  })

  test("commandExecution tool-call and output delta", () => {
    const n = createCodexNormalizer()
    const started = n(native("item/started", { item: { type: "commandExecution", id: "c1", command: "ls", cwd: "/", status: "inProgress" } }))
    expect(started[0]).toMatchObject({ kind: "tool-call", callId: "c1", tool: "commandExecution", phase: "started", category: "execute" })
    expect(n(native("item/commandExecution/outputDelta", { itemId: "c1", delta: "out\n" }))[0]).toMatchObject({ kind: "command-output", callId: "c1", stream: "merged", delta: "out\n" })
  })

  test("fileChange diffs", () => {
    const n = createCodexNormalizer()
    const out = n(native("item/completed", { item: { type: "fileChange", id: "f1", status: "completed", changes: [{ path: "a.ts", kind: "update", diff: "@@" }] } }))
    expect(kinds(out)).toEqual(["tool-call", "file-diff"])
    expect(out[1]).toMatchObject({ kind: "file-diff", path: "a.ts", diff: "@@", changeKind: "update" })
  })

  test("mcpToolCall and progress", () => {
    const n = createCodexNormalizer()
    const out = n(native("item/started", { item: { type: "mcpToolCall", id: "m", server: "s", tool: "t", status: "inProgress", arguments: { a: 1 } } }))
    expect(kinds(out)).toEqual(["tool-call", "mcp-tool"])
    expect(n(native("item/mcpToolCall/progress", { itemId: "m", message: "p" }))[0]).toMatchObject({ kind: "mcp-tool", phase: "progress" })
  })

  test("dynamicToolCall", () => {
    const n = createCodexNormalizer()
    expect(n(native("item/completed", { item: { type: "dynamicToolCall", id: "d", tool: "foo", status: "completed", arguments: {} } }))[0]).toMatchObject({ kind: "tool-call", tool: "foo", phase: "completed" })
  })

  test("webSearch", () => {
    const n = createCodexNormalizer()
    const out = n(native("item/completed", { item: { type: "webSearch", id: "w", query: "q", results: [] } }))
    expect(kinds(out)).toEqual(["tool-call", "web-search"])
  })

  test("subAgentActivity and collabAgentToolCall as task", () => {
    const n = createCodexNormalizer()
    expect(n(native("item/started", { item: { type: "subAgentActivity", id: "s", kind: "started", agentThreadId: "t", agentPath: "p" } }))[0]).toMatchObject({ kind: "task", taskKind: "subagent", phase: "started" })
    expect(n(native("item/completed", { item: { type: "collabAgentToolCall", id: "c", tool: "spawn", status: "completed", senderThreadId: "a", receiverThreadIds: [], agentsStates: {} } }))[0]).toMatchObject({ kind: "task", taskKind: "collab", phase: "completed" })
  })

  test("contextCompaction and thread/compacted", () => {
    const n = createCodexNormalizer()
    expect(n(native("item/started", { item: { type: "contextCompaction", id: "cc" } }))[0]).toMatchObject({ kind: "compaction", status: "in_progress" })
    expect(n(native("thread/compacted", { threadId: "th", turnId: "tu" }))[0]).toMatchObject({ kind: "compaction", status: "completed" })
  })

  test("turn/diff/updated", () => {
    const n = createCodexNormalizer()
    expect(n(native("turn/diff/updated", { diff: "diff" }))[0]).toMatchObject({ kind: "file-diff", path: ".", diff: "diff" })
  })

  test("token usage and rate limits", () => {
    const n = createCodexNormalizer()
    const usage = n(native("thread/tokenUsage/updated", { tokenUsage: { total: { totalTokens: 10, inputTokens: 4, outputTokens: 6, cachedInputTokens: 0, cacheWriteInputTokens: 0, reasoningOutputTokens: 0 }, last: { totalTokens: 10, inputTokens: 4, outputTokens: 6, cachedInputTokens: 0, cacheWriteInputTokens: 0, reasoningOutputTokens: 0 }, modelContextWindow: 100 } }))[0]
    expect(usage).toMatchObject({ kind: "usage", tokens: { input: 4, output: 6, total: 10 }, context: { used: 10, size: 100 } })
    expect(n(native("account/rateLimits/updated", { rateLimits: { primary: 1 } }))[0]).toMatchObject({ kind: "usage", rateLimits: { primary: 1 } })
  })

  test("thread/name/updated and model/rerouted", () => {
    const n = createCodexNormalizer()
    expect(n(native("thread/name/updated", { threadName: "T" }))[0]).toMatchObject({ kind: "session-info", title: "T" })
    expect(n(native("model/rerouted", { toModel: "gpt" }))[0]).toMatchObject({ kind: "mode-update", model: "gpt" })
  })

  test("warning kinds and error", () => {
    const n = createCodexNormalizer()
    expect(n(native("warning", { message: "w" }))[0]).toMatchObject({ kind: "warning", source: "warning" })
    expect(n(native("guardianWarning", { message: "g", threadId: "t" }))[0]).toMatchObject({ kind: "warning", source: "guardianWarning" })
    expect(n(native("deprecationNotice", { summary: "d", details: null }))[0]).toMatchObject({ kind: "warning", source: "deprecationNotice" })
    expect(n(native("configWarning", { summary: "c", details: "x" }))[0]).toMatchObject({ kind: "warning", source: "configWarning" })
    expect(n(native("error", { error: { message: "boom" }, willRetry: true }))[0]).toMatchObject({ kind: "error", message: "boom", recoverable: true })
  })

  test("permission and user-question server requests", () => {
    const n = createCodexNormalizer()
    expect(n(native("item/commandExecution/requestApproval", { itemId: "c", command: "ls" }, { id: 7 }))[0]).toMatchObject({ kind: "permission-request", requestId: "7" })
    expect(n(native("item/tool/requestUserInput", { itemId: "q", isBlocking: true, questions: [{ id: "1", header: "h", question: "Q?", isOther: false, isSecret: false, options: null }] }, { id: "req" }))[0]).toMatchObject({ kind: "user-question", requestId: "req", blocking: true })
  })

  test("non-tool thread items never become tool-call; tool-like ones do", () => {
    const n = createCodexNormalizer()
    for (const type of ["userMessage", "hookPrompt", "enteredReviewMode", "exitedReviewMode"]) {
      const bodies = n(native("item/started", { item: { type, id: type } }))
      expect(bodies.some(b => b.kind === "tool-call")).toBe(false)
    }
    for (const type of ["commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall"]) {
      const bodies = n(native("item/started", { item: { type, id: type + "-1", tool: "t", server: "s", command: "ls", changes: [] } }))
      expect(bodies.some(b => b.kind === "tool-call" || b.kind === "mcp-tool")).toBe(true)
    }
  })

  test("unknown frames produce no body; replay is caller concern", () => {
    const n = createCodexNormalizer()
    expect(n(native("thread/realtime/started", { x: 1 }))).toEqual([])
    expect(n(native("skills/changed", {}))).toEqual([])
    expect(n({ protocol: "acp", value: { sessionUpdate: "plan", entries: [] } })).toEqual([])
  })
})

describe("session.event wiring", () => {
  const dirs: string[] = []
  const cores: ReturnType<typeof createCore>[] = []
  afterEach(async () => {
    await Promise.all(cores.splice(0).map(c => c.close({ agents: "shutdown" }).catch(() => {})))
    await Promise.all(dirs.splice(0).map(d => rm(d, { recursive: true, force: true })))
  })

  test("monotonic seq and turn-start/complete from state", async () => {
    let ctx!: DriverContext
    let resolvePrompt!: (v: { stopReason: string }) => void
    const normalizer = createCodexNormalizer()
    const driver: AgentDriver = {
      id: "codex",
      async open(c) {
        ctx = c
        const runtime: AgentRuntime = {
          agentSessionId: "n1",
          capabilities: { resume: false, steer: false, fork: false, detach: false },
          normalize: normalizer,
          flush: () => normalizer.flush(),
          prompt: () => new Promise(r => { resolvePrompt = r }),
          interrupt: async () => { resolvePrompt({ stopReason: "cancelled" }) },
          close: async () => { resolvePrompt?.({ stopReason: "cancelled" }) },
        }
        return runtime
      },
    }
    const stateDirectory = await mkdtemp(join(tmpdir(), "e1-"))
    dirs.push(stateDirectory)
    const core = createCore({ stateDirectory, agents: [driver], limits: TEST_LIMITS })
    cores.push(core)
    const events: CoreEvent[] = []
    core.subscribe(e => { events.push(e) })
    const session = await core.sessions.create({ id: nextId(), agent: "codex", cwd: tmpdir() })
    const receipt = await session.send({ content: [{ type: "text", text: "hi" }], whenBusy: "queue" })
    await Promise.resolve()
    ctx.onUpdate(native("item/agentMessage/delta", { itemId: "m", delta: "A" }))
    ctx.onUpdate(native("thread/name/updated", { threadName: "replayed" }, { replay: true }))
    resolvePrompt({ stopReason: "end_turn" })
    await receipt.completed
    await new Promise(r => setTimeout(r, 10))
    const normalized = events.filter(e => e.type === "session.event") as Extract<CoreEvent, { type: "session.event" }>[]
    expect(normalized[0]?.event.kind).toBe("turn-start")
    expect(normalized.some(e => e.event.kind === "assistant-delta")).toBe(true)
    expect(normalized.some(e => e.event.kind === "assistant-message")).toBe(true)
    expect(normalized.at(-1)?.event.kind).toBe("turn-complete")
    const seqs = normalized.map(e => e.event.seq)
    expect(seqs).toEqual([...seqs].sort((a, b) => a - b))
    expect(new Set(seqs).size).toBe(seqs.length)
    expect(events.filter(e => e.type === "session.update").length).toBeGreaterThan(0)
    const replayed = normalized.find(e => e.event.kind === "session-info")
    expect(replayed?.event.replay).toBe(true)
    expect(replayed?.event.origin).toBe("replay")
  })
})
