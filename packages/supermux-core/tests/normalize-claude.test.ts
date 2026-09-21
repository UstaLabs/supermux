import { describe, expect, test } from "bun:test"
import { createClaudeNormalizer } from "../src/claude/normalize.js"
import type { AgentUpdate } from "../src/types.js"

const native = (value: unknown, extra?: { replay?: boolean }): AgentUpdate => ({
  protocol: "native",
  value,
  ...(extra?.replay ? { replay: true } : {}),
})

describe("claude normalizer", () => {
  test("system/init session-info", () => {
    const n = createClaudeNormalizer()
    expect(n(native({ type: "system", subtype: "init", cwd: "/tmp", model: "sonnet" }))).toEqual([
      { kind: "session-info", cwd: "/tmp", model: "sonnet" },
    ])
  })

  test("hooks and command_lifecycle produce no event", () => {
    const n = createClaudeNormalizer()
    expect(n(native({ type: "system", subtype: "hook_started" }))).toEqual([])
    expect(n(native({ type: "system", subtype: "hook_response" }))).toEqual([])
    expect(n(native({ type: "command_lifecycle", state: "started" }))).toEqual([])
  })

  test("thinking tokens and empty thinking block are redacted reasoning", () => {
    const n = createClaudeNormalizer()
    expect(n(native({ type: "system", subtype: "thinking_tokens", uuid: "t1" }))[0]).toMatchObject({
      kind: "reasoning",
      reasoningId: "t1",
      redacted: true,
    })
    const out = n(native({
      type: "assistant",
      message: { id: "m", content: [{ type: "thinking", thinking: "" }] },
    }))
    expect(out[0]).toMatchObject({ kind: "reasoning", reasoningId: "m:0", redacted: true })
    expect("text" in out[0]!).toBe(false)
  })

  test("stream_event text and thinking deltas share ids with finals", () => {
    const n = createClaudeNormalizer()
    n(native({ type: "stream_event", event: { type: "message_start", message: { id: "msg" } } }))
    expect(n(native({
      type: "stream_event",
      event: { type: "content_block_delta", index: 0, delta: { type: "thinking_delta", thinking: "why" } },
    }))[0]).toMatchObject({ kind: "reasoning-delta", reasoningId: "msg:0", text: "why" })
    expect(n(native({
      type: "stream_event",
      event: { type: "content_block_delta", index: 1, delta: { type: "text_delta", text: "hi" } },
    }))[0]).toMatchObject({ kind: "assistant-delta", messageId: "msg:1", text: "hi" })
    const flushed = n.flush()
    expect(flushed.some(e => e.kind === "reasoning" && e.reasoningId === "msg:0")).toBe(true)
    expect(flushed.some(e => e.kind === "assistant-message" && e.messageId === "msg:1")).toBe(true)
  })

  test("assistant text block is one message per block", () => {
    const n = createClaudeNormalizer()
    const out = n(native({
      type: "assistant",
      message: { id: "m1", content: [{ type: "text", text: "a" }, { type: "text", text: "b" }] },
    }))
    expect(out).toEqual([
      { kind: "assistant-message", messageId: "m1:0", text: "a" },
      { kind: "assistant-message", messageId: "m1:1", text: "b" },
    ])
  })

  test("tool_use started and tool_result completed/failed", () => {
    const n = createClaudeNormalizer()
    const started = n(native({
      type: "assistant",
      message: { id: "m", content: [{ type: "tool_use", id: "c1", name: "Read", input: { path: "x" } }] },
    }))
    expect(started[0]).toMatchObject({ kind: "tool-call", callId: "c1", tool: "Read", phase: "started" })
    const done = n(native({
      type: "user",
      message: { content: [{ type: "tool_result", tool_use_id: "c1", content: "ok" }] },
    }))
    expect(done[0]).toMatchObject({ kind: "tool-call", callId: "c1", phase: "completed", output: "ok" })
    const failed = n(native({
      type: "user",
      message: { content: [{ type: "tool_result", tool_use_id: "c2", is_error: true, content: "no" }] },
    }))
    expect(failed[0]).toMatchObject({ kind: "tool-call", callId: "c2", phase: "failed" })
  })

  test("Bash Write Edit extras", () => {
    const n = createClaudeNormalizer()
    const bash = n(native({
      type: "assistant",
      message: { id: "m", content: [{ type: "tool_use", id: "b", name: "Bash", input: { command: "ls" } }] },
    }))
    expect(bash.some(e => e.kind === "command-output" && e.delta === "ls")).toBe(true)
    const write = n(native({
      type: "assistant",
      message: { id: "m2", content: [{ type: "tool_use", id: "w", name: "Write", input: { file_path: "a.ts", content: "x" } }] },
    }))
    expect(write.some(e => e.kind === "file-diff" && e.path === "a.ts")).toBe(true)
    const edit = n(native({
      type: "assistant",
      message: { id: "m3", content: [{ type: "tool_use", id: "e", name: "Edit", input: { file_path: "a.ts", old_string: "a", new_string: "b" } }] },
    }))
    expect(edit.some(e => e.kind === "file-diff")).toBe(true)
  })

  test("TodoWrite plan", () => {
    const n = createClaudeNormalizer()
    const out = n(native({
      type: "assistant",
      message: { id: "m", content: [{ type: "tool_use", id: "p", name: "TodoWrite", input: { todos: [{ content: "do", status: "in_progress" }] } }] },
    }))
    expect(out.some(e => e.kind === "plan" && e.entries[0]?.status === "in_progress")).toBe(true)
  })

  test("Task and Agent task started then completed", () => {
    const n = createClaudeNormalizer()
    const start = n(native({
      type: "assistant",
      message: { id: "m", content: [{ type: "tool_use", id: "t1", name: "Task", input: { description: "go" } }] },
    }))
    expect(start.some(e => e.kind === "task" && e.phase === "started" && e.taskId === "t1")).toBe(true)
    const done = n(native({
      type: "user",
      message: { content: [{ type: "tool_result", tool_use_id: "t1", content: "done" }] },
    }))
    expect(done.some(e => e.kind === "task" && e.phase === "completed" && e.taskId === "t1")).toBe(true)
  })

  test("rate_limit_event and result usage then error", () => {
    const n = createClaudeNormalizer()
    expect(n(native({ type: "rate_limit_event", rate_limit_info: { primary: 1 } }))[0]).toMatchObject({
      kind: "usage",
      rateLimits: { primary: 1 },
    })
    const ok = n(native({
      type: "result",
      is_error: false,
      total_cost_usd: 0.1,
      usage: { input_tokens: 1, output_tokens: 2 },
    }))
    expect(ok[0]).toMatchObject({ kind: "usage", tokens: { input: 1, output: 2, total: 3 }, cost: { amount: 0.1, currency: "USD" } })
    const err = n(native({ type: "result", is_error: true, errors: ["boom"], usage: { input_tokens: 0, output_tokens: 0 } }))
    expect(err.map(e => e.kind)).toEqual(["usage", "error"])
    expect(err[1]).toMatchObject({ kind: "error", message: "boom" })
  })

  test("control_request can_use_tool permission-request", () => {
    const n = createClaudeNormalizer()
    expect(n(native({
      type: "control_request",
      request_id: "r1",
      request: { subtype: "can_use_tool", tool_name: "Bash", input: { command: "pwd" } },
    }))).toEqual([])
  })

  test("AskUserQuestion", () => {
    const n = createClaudeNormalizer()
    const out = n(native({
      type: "assistant",
      message: {
        id: "m",
        content: [{ type: "tool_use", id: "q", name: "AskUserQuestion", input: { questions: [{ question: "Q?", options: [{ label: "A" }] }] } }],
      },
    }))
    expect(out.some(e => e.kind === "user-question")).toBe(false)
    expect(out.some(e => e.kind === "tool-call" && e.callId === "q")).toBe(true)
  })

  test("unknown ignored; replay is caller concern", () => {
    const n = createClaudeNormalizer()
    expect(n(native({ type: "system", subtype: "status", status: "requesting" }))).toEqual([])
    expect(n(native({ type: "stream_event", event: { type: "message_stop" } }))).toEqual([])
    expect(n({ protocol: "acp", value: { sessionUpdate: "plan", entries: [] } })).toEqual([])
    const replayed = n(native({ type: "system", subtype: "init", cwd: "/", model: "m" }, { replay: true }))
    expect(replayed[0]?.kind).toBe("session-info")
  })
})
