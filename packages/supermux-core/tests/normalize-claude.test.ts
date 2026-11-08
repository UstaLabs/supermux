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

  test("commands_changed and a control reply with commands become commands-update", () => {
    // Shapes from Claude Code 2.1.308: the initialize reply and the commands_changed push.
    const n = createClaudeNormalizer()
    const commands = [
      { name: "code-review", description: "Review", argumentHint: "[pr]", aliases: ["review"], builtin: true },
      { name: "proj-cmd", description: "A project command (project)", argumentHint: "" },
      { description: "nameless rows are skipped" },
    ]
    const expected = [
      { name: "code-review", description: "Review", argumentHint: "[pr]", aliases: ["review"], builtin: true },
      { name: "proj-cmd", description: "A project command (project)" },
    ]
    expect(n(native({ type: "system", subtype: "commands_changed", commands }))).toEqual([{ kind: "commands-update", commands: expected }])
    expect(n(native({ type: "control_response", response: { subtype: "success", request_id: "r", response: { commands, models: [] } } })))
      .toEqual([{ kind: "commands-update", commands: expected }])
    expect(n(native({ type: "control_response", response: { subtype: "success", request_id: "r", response: { mode: "plan" } } }))).toEqual([])
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

  test("an Edit of several lines is a unified diff: the lines it keeps are context, each other line has its - or +", () => {
    const n = createClaudeNormalizer()
    const diffOf = (old_string: string, new_string: string) => (n(native({
      type: "assistant",
      message: { id: `m-${old_string}`, content: [{ type: "tool_use", id: `e-${old_string}`, name: "Edit",
        input: { file_path: "src/users.js", old_string, new_string } }] },
    })).find(e => e.kind === "file-diff") as { diff: string; changeKind?: string }).diff.split("\n")
    // What Claude Code sent for "Fix src/users.js so the test passes": one line added between two it kept.
    expect(diffOf("email }) {\n  return {", 'email }) {\n  if (!email) return { error: "email is required" }\n  return {')).toEqual([
      "--- a/src/users.js", "+++ b/src/users.js", "@@",
      " email }) {",
      '+  if (!email) return { error: "email is required" }',
      "   return {",
    ])
    // The newline both end in ends the last line: no empty line of context after it.
    expect(diffOf("a\nb\nc\n", "a\nB1\nB2\nc\n").slice(3)).toEqual([" a", "-b", "+B1", "+B2", " c"])
    expect(diffOf("one", "two").slice(3)).toEqual(["-one", "+two"])
  })

  test("TodoWrite plan", () => {
    const n = createClaudeNormalizer()
    const out = n(native({
      type: "assistant",
      message: { id: "m", content: [{ type: "tool_use", id: "p", name: "TodoWrite", input: { todos: [{ content: "do", status: "in_progress" }] } }] },
    }))
    expect(out.some(e => e.kind === "plan" && e.entries[0]?.status === "in_progress")).toBe(true)
  })

  test("Task/Agent tool calls are plain tool calls; the subagent comes from task_* frames", () => {
    const n = createClaudeNormalizer()
    const start = n(native({
      type: "assistant",
      message: { id: "m", content: [{ type: "tool_use", id: "t1", name: "Task", input: { description: "go" } }] },
    }))
    expect(start.some(e => e.kind === "tool-call" && e.phase === "started" && e.callId === "t1")).toBe(true)
    expect(start.some(e => e.kind === "task")).toBe(false)
    const done = n(native({
      type: "user",
      message: { content: [{ type: "tool_result", tool_use_id: "t1", content: "done" }] },
    }))
    expect(done.some(e => e.kind === "tool-call" && e.phase === "completed" && e.callId === "t1")).toBe(true)
    expect(done.some(e => e.kind === "task" || e.kind === "subagent")).toBe(false)
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
    expect(err[1]).not.toHaveProperty("errorType")
  })

  test("result: a turn Claude ended because it was interrupted is not an error (real 2.1.308 frames)", () => {
    // After an interrupt control request: error_during_execution with Claude's own diagnostic, and terminal_reason
    // aborted_streaming (stopped while the model answered) or aborted_tools (stopped while a tool ran).
    const n = createClaudeNormalizer()
    for (const [terminal_reason, stop_reason] of [["aborted_streaming", "tool_use"], ["aborted_tools", "tool_use"], ["aborted_streaming", null]] as const) {
      const out = n(native({
        type: "result", subtype: "error_during_execution", is_error: true, terminal_reason, stop_reason,
        errors: [`[ede_diagnostic] result_type=user last_content_type=n/a stop_reason=${stop_reason}`],
        usage: { input_tokens: 0, output_tokens: 0 },
      }))
      expect(out.map(e => e.kind)).toEqual(["usage"])
    }
  })

  test("result: a 401/403 API error marks the account unavailable (real cancelled-subscription frame)", () => {
    const n = createClaudeNormalizer()
    const text = "Your organization has disabled Claude subscription access for Claude Code · Use an Anthropic API key instead, or ask your admin to enable access"
    const refused = n(native({
      type: "result", subtype: "success", is_error: true, terminal_reason: "api_error",
      api_error_status: 403, api_error_code: "oauth_not_allowed_for_organization", result: text,
      usage: { input_tokens: 0, output_tokens: 0 },
    }))
    expect(refused[1]).toEqual({ kind: "error", message: text, errorType: "account_unavailable" })
    const unauthorized = n(native({ type: "result", is_error: true, api_error_status: 401, result: "Invalid API key", usage: {} }))
    expect(unauthorized[1]).toMatchObject({ errorType: "account_unavailable" })
    const overloaded = n(native({ type: "result", is_error: true, api_error_status: 529, result: "Overloaded", usage: {} }))
    expect(overloaded[1]).not.toHaveProperty("errorType")
  })

  // Real claude 2.1.308 frames with no sign-in (#110): a synthetic assistant message, then an error result, same text.
  const notLoggedIn = "Not logged in · Please run /login"
  const syntheticError = (error: string, text: string) => native({
    type: "assistant", parent_tool_use_id: null, error, is_api_error_message: true,
    message: { id: "58134c7e", model: "<synthetic>", role: "assistant", type: "message", content: [{ type: "text", text }] },
  })
  const apiErrorResult = (text: string, api_error_status: number | null = null) => native({
    type: "result", subtype: "success", is_error: true, terminal_reason: "api_error", api_error_status, result: text, usage: {},
  })

  test("result: a turn Claude refused for no sign-in is one not_signed_in error, never also a reply", () => {
    const n = createClaudeNormalizer()
    const out = [...n(syntheticError("authentication_failed", notLoggedIn)), ...n(apiErrorResult(notLoggedIn))]
    expect(out.filter(e => e.kind === "assistant-message")).toEqual([])
    expect(out.filter(e => e.kind === "error")).toEqual([{ kind: "error", message: notLoggedIn, errorType: "not_signed_in" }])
  })

  for (const [error, status, text] of [
    ["rate_limit", 429, "You've hit your limit · resets 5pm"],
    ["billing_error", 402, "Credit balance is too low"],
    ["server_error", 529, "API Error: 529 Overloaded"],
    ["server_error", 500, "API Error: 500 Internal server error"],
    ["invalid_request", 400, "API Error: 400 prompt is too long"],
    ["unknown", null, "API Error: Connection error."],
  ] as const) {
    test(`result: a synthetic ${error} (${status}) message is the turn's error only, not a reply too`, () => {
      const n = createClaudeNormalizer()
      const out = [...n(syntheticError(error, text)), ...n(apiErrorResult(text, status))]
      expect(out.filter(e => e.kind === "assistant-message")).toEqual([])
      expect(out.filter(e => e.kind === "error")).toEqual([{ kind: "error", message: text }])
    })
  }

  test("result: a refused login (401) stays account_unavailable, so Core can switch the account", () => {
    const n = createClaudeNormalizer()
    const out = [...n(syntheticError("authentication_failed", "Invalid API key")), ...n(apiErrorResult("Invalid API key", 401))]
    expect(out.filter(e => e.kind === "error")).toEqual([{ kind: "error", message: "Invalid API key", errorType: "account_unavailable" }])
  })

  test("a synthetic API error message whose turn did not fail is still shown", () => {
    const n = createClaudeNormalizer()
    const synthetic = n(syntheticError("server_error", "API Error: retrying"))
    const ok = n(native({ type: "result", subtype: "success", is_error: false, result: "ok", usage: {} }))
    expect([...synthetic, ...ok].filter(e => e.kind === "assistant-message").map(e => (e as { text: string }).text)).toEqual(["API Error: retrying"])
    const lost = createClaudeNormalizer()
    lost(syntheticError("server_error", "API Error: gone"))
    expect(lost.flush().map(e => (e as { text?: string }).text)).toEqual(["API Error: gone"])
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
