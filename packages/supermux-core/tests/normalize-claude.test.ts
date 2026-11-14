import { describe, expect, test } from "bun:test"
import { createClaudeNormalizer, initializeReplyForHost } from "../src/claude/normalize.js"
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

  // #129: the initialize reply also lists the account's models (what the Agent SDK's supportedModels() reads), from
  // Claude's own login: the broker learns the real list with no credential of its own. Shapes from 2.1.308.
  test("the initialize reply's models become models-update, with each model's effort levels and fast mode", () => {
    const n = createClaudeNormalizer()
    const models = [
      { value: "default", resolvedModel: "claude-opus-5-5", displayName: "Default (recommended)", description: "Opus 5.5 · Best for everyday, complex tasks", supportsEffort: true, supportedEffortLevels: ["low", "high", "max"], supportsFastMode: true },
      { value: "sonnet", resolvedModel: "claude-sonnet-5-5", displayName: "Sonnet 5.5", description: "Fast", supportsEffort: true, supportedEffortLevels: ["low", "medium", "high"] },
      { value: "claude-haiku-4-5-20251001", resolvedModel: "claude-haiku-4-5-20251001", displayName: "Haiku 4.5", description: "Small", supportsThinkingOff: true, supportedEffortLevels: ["low"] },
      { displayName: "no value: skipped" },
    ]
    // The account rides along in the same reply: nothing of it may reach the event (#129).
    const account = { email: "someone@example.com", organization: "Example Org", subscriptionType: "Claude Team" }
    expect(n(native({ type: "control_response", response: { subtype: "success", request_id: "r", response: { models, account } } }))).toEqual([{
      kind: "models-update",
      models: [
        { value: "default", name: "Default (recommended)", description: "Opus 5.5 · Best for everyday, complex tasks", resolved: "claude-opus-5-5", efforts: ["low", "high", "max"], fast: true },
        { value: "sonnet", name: "Sonnet 5.5", description: "Fast", resolved: "claude-sonnet-5-5", efforts: ["low", "medium", "high"] },
        { value: "claude-haiku-4-5-20251001", name: "Haiku 4.5", description: "Small", resolved: "claude-haiku-4-5-20251001" },
      ],
    }])
    // An empty list (the commands test above) says nothing about the models.
    expect(n(native({ type: "control_response", response: { subtype: "success", request_id: "r", response: { models: [] } } }))).toEqual([])
  })

  // #129 (Muxie): the initialize reply carries the signed-in account (e-mail, organization). The driver forwards the
  // reply to the host as a native frame, which travels in every event envelope built from it: the account never does.
  test("the initialize reply goes to the host without its account", () => {
    const reply = { subtype: "success", request_id: "r", response: { commands: [], models: [{ value: "opus" }], account: { email: "someone@example.com", organization: "Example Org" }, pid: 7 } }
    const forwarded = initializeReplyForHost(reply)
    expect(JSON.stringify(forwarded)).not.toContain("someone@example.com")
    expect(JSON.stringify(forwarded)).not.toContain("Example Org")
    expect(forwarded).toEqual({ subtype: "success", request_id: "r", response: { commands: [], models: [{ value: "opus" }], pid: 7 } })
    expect(reply.response.account.email).toBe("someone@example.com") // the driver's own copy is untouched
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

  test("context: the main thread's last request is how full the window is; the result gives the window (#144)", () => {
    // Real 2.1.308 shapes: per-request usage on each assistant frame, the window in result.modelUsage.
    const n = createClaudeNormalizer()
    const assistant = (usage: Record<string, number>, extra: Record<string, unknown> = {}) => native({
      type: "assistant", ...extra,
      message: { id: "m1", model: "claude-haiku-5-5", role: "assistant", content: [{ type: "text", text: "hi" }], usage },
    })
    // Before the window is known, a request's size is held, not reported.
    expect(n(assistant({ input_tokens: 2, cache_read_input_tokens: 23472, cache_creation_input_tokens: 3481, output_tokens: 4 }))
      .filter(b => b.kind === "usage")).toEqual([])
    const result = n(native({
      type: "result", subtype: "success", is_error: false, result: "hi",
      usage: { input_tokens: 4, cache_read_input_tokens: 36879, cache_creation_input_tokens: 3481, output_tokens: 9 },
      modelUsage: { "claude-haiku-5-5": { contextWindow: 1000000, maxOutputTokens: 128000 } },
    }))
    expect(result.find(b => b.kind === "usage")).toMatchObject({ context: { used: 26955, size: 1000000 } })
    // Once the window is known, each new request reports at once (the ring moves during a long turn) …
    expect(n(assistant({ input_tokens: 1, cache_read_input_tokens: 26955, cache_creation_input_tokens: 900, output_tokens: 3 }))
      .filter(b => b.kind === "usage")).toEqual([{ kind: "usage", context: { used: 27856, size: 1000000 } }])
    // … but the same request's next block (same usage) says nothing new,
    expect(n(assistant({ input_tokens: 1, cache_read_input_tokens: 26955, cache_creation_input_tokens: 900, output_tokens: 3 }))
      .filter(b => b.kind === "usage")).toEqual([])
    // and a subagent's or a synthetic message's usage is never the main conversation's.
    expect(n(assistant({ input_tokens: 5, cache_read_input_tokens: 90000, output_tokens: 1 }, { parent_tool_use_id: "toolu_1" }))
      .filter(b => b.kind === "usage")).toEqual([])
    expect(n(native({ type: "assistant", message: { id: "s", model: "<synthetic>", role: "assistant", content: [{ type: "text", text: "x" }], usage: { input_tokens: 0, output_tokens: 0 } } }))
      .filter(b => b.kind === "usage")).toEqual([])
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

describe("claudeContextBreakdown (#144)", () => {
  test("maps get_context_usage's reply (real 2.1.308, trimmed) to the breakdown", async () => {
    const { claudeContextBreakdown } = await import("../src/claude/normalize.js")
    const reply = {
      categories: [
        { id: "system_prompt", name: "System prompt", tokens: 2517, color: "promptBorder", kind: "used" },
        { id: "system_tools", name: "System tools", tokens: 9196, color: "inactive", kind: "used" },
        { id: "mcp_tools_deferred", name: "MCP tools (deferred)", tokens: 19819, isDeferred: true, kind: "deferred" },
        { id: "memory_files", name: "Memory files", tokens: 76, kind: "used" },
        { id: "skills", name: "Skills", tokens: 6366, kind: "used" },
        { id: "messages", name: "Messages", tokens: 1160, kind: "used" },
        { id: "autocompact_buffer", name: "Autocompact buffer", tokens: 33000, kind: "buffer" },
        { id: "free_space", name: "Free space", tokens: 947685, kind: "free" },
      ],
      totalTokens: 19315, maxTokens: 1000000, autoCompactThreshold: 967000, isAutoCompactEnabled: true,
      memoryFiles: [{ path: "/h/CLAUDE.md", type: "User", tokens: 76 }],
      mcpTools: [
        { name: "mcp__claude_ai_Claude_Data__certify_query", serverName: "claude_ai_Claude_Data", tokens: 1415, isLoaded: false },
        { name: "mcp__mux-shim__message_session", serverName: "mux-shim", tokens: 1150, isLoaded: true },
      ],
      mcpInstructions: [{ serverName: "claude.ai Claude Data", tokens: 351 }],
      skills: { totalSkills: 47, tokens: 6366, skillFrontmatter: [{ name: "anatomy", source: "userSettings", path: "/s/anatomy/SKILL.md", tokens: 327 }] },
      gridRows: [[]],
    }
    expect(claudeContextBreakdown(reply)).toEqual({
      used: 19315, size: 1000000, threshold: 967000,
      categories: [
        { id: "system_prompt", name: "System prompt", tokens: 2517, kind: "used" },
        { id: "system_tools", name: "System tools", tokens: 9196, kind: "used" },
        { id: "mcp_tools_deferred", name: "MCP tools (deferred)", tokens: 19819, kind: "deferred" },
        { id: "memory_files", name: "Memory files", tokens: 76, kind: "used" },
        { id: "skills", name: "Skills", tokens: 6366, kind: "used" },
        { id: "messages", name: "Messages", tokens: 1160, kind: "used" },
        { id: "autocompact_buffer", name: "Autocompact buffer", tokens: 33000, kind: "buffer" },
        { id: "free_space", name: "Free space", tokens: 947685, kind: "free" },
      ],
      mcpTools: [
        { server: "claude_ai_Claude_Data", name: "certify_query", tokens: 1415, loaded: false },
        { server: "mux-shim", name: "message_session", tokens: 1150, loaded: true },
      ],
      mcpInstructions: [{ server: "claude.ai Claude Data", tokens: 351 }],
      skills: [{ name: "anatomy", tokens: 327, source: "userSettings" }],
      memoryFiles: [{ path: "/h/CLAUDE.md", type: "User", tokens: 76 }],
    })
  })

  test("auto-compact off: no threshold; a reply without totals is no breakdown", async () => {
    const { claudeContextBreakdown } = await import("../src/claude/normalize.js")
    expect(claudeContextBreakdown({ categories: [], totalTokens: 5, maxTokens: 10, autoCompactThreshold: 8, isAutoCompactEnabled: false }))
      .toEqual({ used: 5, size: 10, categories: [] })
    expect(claudeContextBreakdown({ categories: [] })).toBeUndefined()
    expect(claudeContextBreakdown(null)).toBeUndefined()
  })
})

describe("compaction (#144)", () => {
  // Real 2.1.308 frames of a manual /compact (trimmed): status compacting → status success → init → compact_boundary
  // → the summary as a synthetic replayed user frame → "Compacted" → result.
  const frames = [
    { type: "system", subtype: "status", status: "compacting", uuid: "36523f3e" },
    { type: "system", subtype: "status", status: null, compact_result: "success", uuid: "810461f3" },
    { type: "system", subtype: "init", cwd: "/tmp", model: "claude-haiku-5-5" },
    { type: "system", subtype: "compact_boundary", uuid: "f70e6172", compact_metadata: { trigger: "manual", pre_tokens: 26905, post_tokens: 8621, cumulative_dropped_tokens: 18284, duration_ms: 7497 } },
    { type: "user", isReplay: true, isSynthetic: true, message: { role: "user", content: "This session is being continued from a previous conversation that ran out of context. The summary below covers the earlier portion of the conversation.\n\nSummary:\n1. Primary Request: remember PELICAN-7731." } },
    { type: "user", isReplay: true, message: { role: "user", content: "<local-command-stdout>Compacted </local-command-stdout>" } },
  ]

  test("a compaction is in progress, then completed with its before → after and Claude's summary", () => {
    const n = createClaudeNormalizer()
    // A turn before it told the window and its size.
    n(native({ type: "assistant", message: { id: "m0", model: "claude-haiku-5-5", role: "assistant", content: [{ type: "text", text: "OK" }], usage: { input_tokens: 2, cache_read_input_tokens: 26903, output_tokens: 1 } } }))
    n(native({ type: "result", subtype: "success", is_error: false, result: "OK", usage: {}, modelUsage: { "claude-haiku-5-5": { contextWindow: 1000000 } } }))
    const out = frames.flatMap(f => n(native(f)))
    expect(out.filter(b => b.kind === "compaction")).toEqual([
      { kind: "compaction", compactionId: "36523f3e", status: "in_progress" },
      {
        kind: "compaction", compactionId: "36523f3e", status: "completed", trigger: "manual", before: 26905, after: 8621,
        summary: "Summary:\n1. Primary Request: remember PELICAN-7731.",
      },
    ])
    // The window now holds what the compaction left, at once and on the turn's result.
    expect(out.filter(b => b.kind === "usage")).toEqual([{ kind: "usage", context: { used: 8621, size: 1000000 } }])
    const result = n(native({ type: "result", subtype: "success", is_error: false, result: "", num_turns: 0, usage: { input_tokens: 0, output_tokens: 0 } }))
    expect(result.find(b => b.kind === "usage")).toMatchObject({ context: { used: 8621, size: 1000000 } })
    // Nothing of it is a chat message.
    expect(out.some(b => b.kind === "assistant-message" || b.kind === "user-message")).toBe(false)
  })

  test("a failed compaction says so", () => {
    const n = createClaudeNormalizer()
    n(native(frames[0]))
    expect(n(native({ type: "system", subtype: "status", status: null, compact_result: "failed", compact_error: "Not enough messages to compact." })))
      .toEqual([{ kind: "compaction", compactionId: "36523f3e", status: "failed", error: "Not enough messages to compact." }])
  })

  test("an automatic compaction (no status frames) still completes at its boundary", () => {
    const n = createClaudeNormalizer()
    const out = n(native({ type: "system", subtype: "compact_boundary", uuid: "b1", compact_metadata: { trigger: "auto", pre_tokens: 967000, post_tokens: 20113 } }))
    expect(out.filter(b => b.kind === "compaction")).toEqual([])
    const summary = n(native(frames[4]))
    expect(summary).toEqual([{ kind: "compaction", compactionId: "b1", status: "completed", trigger: "auto", before: 967000, after: 20113, summary: "Summary:\n1. Primary Request: remember PELICAN-7731." }])
  })
})
