import { test, expect } from "bun:test"
import { toActivityEvents } from "./adapter-activity"

const NOW = 1730000000000
const ISO = new Date(NOW).toISOString()

// --- codex native tools ---

test("codex shell started -> tool card with command summary", () => {
  const ev = { kind: "tool-call", tool: "shell", phase: "started", call_id: "c1", detail: { type: "command_execution", command: "npm test" } } as const
  expect(toActivityEvents("codex", ev, NOW)).toEqual([
    { ts: ISO, kind: "tool", tool: "Bash", title: "Bash: npm test", detail: "npm test", phase: "started", callId: "c1" },
  ])
})

test("codex completed -> tool_result done with aggregated_output as detail", () => {
  const ev = { kind: "tool-call", tool: "shell", phase: "completed", call_id: "c1", detail: { type: "command_execution", aggregated_output: "ok" } } as const
  const [r] = toActivityEvents("codex", ev, NOW)
  expect(r).toEqual({ ts: ISO, kind: "tool_result", title: "done", detail: "ok", phase: "completed", callId: "c1" })
})

test("codex completed with no aggregated_output -> empty detail", () => {
  const ev = { kind: "tool-call", tool: "shell", phase: "completed", call_id: "c1", detail: { type: "command_execution" } } as const
  const [r] = toActivityEvents("codex", ev, NOW)
  expect(r).toEqual({ ts: ISO, kind: "tool_result", title: "done", detail: "", phase: "completed", callId: "c1" })
})

test("failed -> tool_result error", () => {
  const ev = { kind: "tool-call", tool: "shell", phase: "failed", call_id: "c1", detail: {} } as const
  expect(toActivityEvents("codex", ev, NOW)[0]).toMatchObject({ kind: "tool_result", title: "error", callId: "c1" })
})

test("codex file_change started -> Edit with path", () => {
  const ev = { kind: "tool-call", tool: "file_change", phase: "started", call_id: "c2", detail: { type: "fileChange", path: "/a/b.ts" } } as const
  expect(toActivityEvents("codex", ev, NOW)[0]).toMatchObject({ kind: "tool", tool: "Edit", title: "Edit: /a/b.ts", detail: "/a/b.ts" })
})

test("codex web_search started -> WebFetch with query", () => {
  const ev = { kind: "tool-call", tool: "web_search", phase: "started", call_id: "c3", detail: { type: "webSearch", query: "how to npm" } } as const
  expect(toActivityEvents("codex", ev, NOW)[0]).toMatchObject({ kind: "tool", tool: "WebFetch", title: "WebFetch: how to npm" })
})

// --- codex MCP tools ---

test("codex mcpToolCall started -> Tool card with mcp tool name + arg", () => {
  const ev = { kind: "tool-call", tool: "mcp_tool_call", phase: "started", call_id: "m1", detail: { type: "mcpToolCall", toolName: "spawn_session", arguments: { name: "test", workdir: "/tmp" } } } as const
  const [r] = toActivityEvents("codex", ev, NOW)
  expect(r).toEqual({ ts: ISO, kind: "tool", tool: "Tool", title: "Tool: spawn_session /tmp", detail: "spawn_session /tmp", phase: "started", callId: "m1" })
})

test("codex mcpToolCall started with no arg -> just tool name", () => {
  const ev = { kind: "tool-call", tool: "mcp_tool_call", phase: "started", call_id: "m2", detail: { type: "mcpToolCall", toolName: "reply", arguments: {} } } as const
  const [r] = toActivityEvents("codex", ev, NOW)
  expect(r).toMatchObject({ kind: "tool", tool: "Tool", title: "Tool: reply", detail: "reply" })
})

test("codex mcpToolCall completed -> result detail", () => {
  const ev = { kind: "tool-call", tool: "mcp_tool_call", phase: "completed", call_id: "m1", detail: { type: "mcpToolCall", toolName: "spawn_session", arguments: { name: "test" }, result: '{"session_id":"abc"}' } } as const
  const [r] = toActivityEvents("codex", ev, NOW)
  expect(r).toEqual({ ts: ISO, kind: "tool_result", title: "done", detail: '{"session_id":"abc"}', phase: "completed", callId: "m1" })
})

// --- cursor ---

test("cursor read started -> Read card with path summary", () => {
  const ev = { kind: "tool-call", tool: "readToolCall", phase: "started", call_id: "x", detail: { tool_call: { readToolCall: { path: "/a/b.ts" } } } } as const
  expect(toActivityEvents("cursor", ev, NOW)).toEqual([
    { ts: ISO, kind: "tool", tool: "Read", title: "Read: /a/b.ts", detail: "/a/b.ts", phase: "started", callId: "x" },
  ])
})

test("cursor bash started -> Bash with command", () => {
  const ev = { kind: "tool-call", tool: "bashToolCall", phase: "started", call_id: "b1", detail: { tool_call: { bashToolCall: { command: "npm test" } } } } as const
  expect(toActivityEvents("cursor", ev, NOW)[0]).toMatchObject({ kind: "tool", tool: "Bash", title: "Bash: npm test", detail: "npm test" })
})

test("cursor completed -> detail from tool_call_result.content", () => {
  const ev = { kind: "tool-call", tool: "bashToolCall", phase: "completed", call_id: "b1", detail: { tool_call: {}, result: { tool_call_result: { content: "tests passed", is_error: false } } } } as const
  const [r] = toActivityEvents("cursor", ev, NOW)
  expect(r).toEqual({ ts: ISO, kind: "tool_result", title: "done", detail: "tests passed", phase: "completed", callId: "b1" })
})

test("cursor failed -> error title with detail", () => {
  const ev = { kind: "tool-call", tool: "bashToolCall", phase: "failed", call_id: "b2", detail: { tool_call: {}, result: { tool_call_result: { content: "command not found", is_error: true } } } } as const
  const [r] = toActivityEvents("cursor", ev, NOW)
  expect(r).toMatchObject({ kind: "tool_result", title: "error", detail: "command not found", callId: "b2" })
})

// --- opencode ---

test("opencode bash started -> Bash with command from state.input", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "started", call_id: "oc1", detail: { type: "tool", tool: "bash", callID: "oc1", state: { status: "running", input: { command: "npm install" } } } } as const
  expect(toActivityEvents("opencode", ev, NOW)).toEqual([
    { ts: ISO, kind: "tool", tool: "Bash", title: "Bash: npm install", detail: "npm install", phase: "started", callId: "oc1" },
  ])
})

test("opencode read started -> Read with path from state.input", () => {
  const ev = { kind: "tool-call", tool: "read", phase: "started", call_id: "oc2", detail: { type: "tool", tool: "read", callID: "oc2", state: { status: "running", input: { path: "/src/main.ts" } } } } as const
  expect(toActivityEvents("opencode", ev, NOW)[0]).toMatchObject({ kind: "tool", tool: "Read", title: "Read: /src/main.ts", detail: "/src/main.ts" })
})

test("opencode MCP tool started -> short name from mcp__ prefix", () => {
  const ev = { kind: "tool-call", tool: "mcp__mux-shim__reply", phase: "started", call_id: "oc3", detail: { type: "tool", tool: "mcp__mux-shim__reply", callID: "oc3", state: { status: "running", input: { text: "hello world" } } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW)
  expect(r).toMatchObject({ kind: "tool", tool: "Reply", title: "Reply: hello world", detail: "hello world" })
})

test("opencode MCP tool no arg -> just tool name", () => {
  const ev = { kind: "tool-call", tool: "mcp__mux-shim__reply", phase: "started", call_id: "oc4", detail: { type: "tool", tool: "mcp__mux-shim__reply", callID: "oc4", state: { status: "running", input: {} } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW)
  expect(r).toMatchObject({ kind: "tool", tool: "Reply", title: "Reply", detail: "" })
})

test("opencode completed -> detail from state.output", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "completed", call_id: "oc1", detail: { type: "tool", tool: "bash", callID: "oc1", state: { status: "completed", input: { command: "npm install" }, output: "added 42 packages", title: "npm install" } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW)
  expect(r).toEqual({ ts: ISO, kind: "tool_result", title: "done", detail: "npm install", phase: "completed", callId: "oc1" })
})

test("opencode completed with output but no title -> uses output", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "completed", call_id: "oc5", detail: { type: "tool", tool: "bash", callID: "oc5", state: { status: "completed", input: { command: "ls" }, output: "file1\nfile2" } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW)
  expect(r).toMatchObject({ kind: "tool_result", title: "done", detail: "file1" }) // firstLine
})

test("opencode started with state.title but no input -> uses title as summary", () => {
  const ev = { kind: "tool-call", tool: "task", phase: "started", call_id: "oc7", detail: { type: "tool", tool: "task", callID: "oc7", state: { status: "running", title: "Searching codebase for API routes" } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW)
  expect(r).toMatchObject({ kind: "tool", tool: "Task", title: "Task: Searching codebase for API routes", detail: "Searching codebase for API routes" })
})

test("opencode started with no input and no title -> just tool name", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "started", call_id: "oc8", detail: { type: "tool", tool: "bash", callID: "oc8", state: { status: "running" } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW)
  expect(r).toMatchObject({ kind: "tool", tool: "Bash", title: "Bash", detail: "" })
})

test("opencode failed -> detail from state.error", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "failed", call_id: "oc6", detail: { type: "tool", tool: "bash", callID: "oc6", state: { status: "error", input: { command: "bad" }, error: "command not found: bad" } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW)
  expect(r).toMatchObject({ kind: "tool_result", title: "error", detail: "command not found: bad", callId: "oc6" })
})

// --- defensive ---

test("defensive: missing detail -> no summary, no throw", () => {
  const ev = { kind: "tool-call", tool: "shell", phase: "started", call_id: "c1" } as const
  expect(toActivityEvents("codex", ev, NOW)[0]).toEqual({ ts: ISO, kind: "tool", tool: "Bash", title: "Bash", detail: "", phase: "started", callId: "c1" })
})

test("defensive: null detail", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "started", call_id: "c1", detail: null } as const
  expect(toActivityEvents("codex", ev, NOW)[0]).toMatchObject({ kind: "tool", tool: "Bash", title: "Bash", detail: "" })
})
