import { test, expect } from "bun:test"
import { toActivityEvents } from "./adapter-activity"

const NOW = 1730000000000
const ISO = new Date(NOW).toISOString()
const WD = "/w"

// --- codex native tools ---

test("codex shell started -> tool card with command summary", () => {
  const ev = { kind: "tool-call", tool: "shell", phase: "started", call_id: "c1", detail: { type: "command_execution", command: "npm test" } } as const
  expect(toActivityEvents("codex", ev, NOW, WD)).toEqual([
    { ts: ISO, kind: "tool", tool: "Bash", title: "Bash: npm test", detail: "npm test", phase: "started", callId: "c1" },
  ])
})

test("codex completed -> tool_result done with aggregated_output as detail", () => {
  const ev = { kind: "tool-call", tool: "shell", phase: "completed", call_id: "c1", detail: { type: "command_execution", aggregated_output: "ok" } } as const
  const [r] = toActivityEvents("codex", ev, NOW, WD)
  expect(r).toEqual({ ts: ISO, kind: "tool_result", title: "done", detail: "ok", phase: "completed", callId: "c1" })
})

test("codex completed with no aggregated_output -> empty detail", () => {
  const ev = { kind: "tool-call", tool: "shell", phase: "completed", call_id: "c1", detail: { type: "command_execution" } } as const
  const [r] = toActivityEvents("codex", ev, NOW, WD)
  expect(r).toEqual({ ts: ISO, kind: "tool_result", title: "done", detail: "", phase: "completed", callId: "c1" })
})

test("failed -> tool_result error", () => {
  const ev = { kind: "tool-call", tool: "shell", phase: "failed", call_id: "c1", detail: {} } as const
  expect(toActivityEvents("codex", ev, NOW, WD)[0]).toMatchObject({ kind: "tool_result", title: "error", callId: "c1" })
})

test("codex file_change started -> Edit with path (workdir-relative)", () => {
  const ev = { kind: "tool-call", tool: "file_change", phase: "started", call_id: "c2", detail: { type: "fileChange", path: "/w/a/b.ts" } } as const
  expect(toActivityEvents("codex", ev, NOW, WD)[0]).toMatchObject({ kind: "tool", tool: "Edit", title: "Edit: a/b.ts", detail: "/w/a/b.ts" })
})

test("codex web_search started -> WebFetch with query", () => {
  const ev = { kind: "tool-call", tool: "web_search", phase: "started", call_id: "c3", detail: { type: "webSearch", query: "how to npm" } } as const
  expect(toActivityEvents("codex", ev, NOW, WD)[0]).toMatchObject({ kind: "tool", tool: "WebFetch", title: "WebFetch: how to npm" })
})

// --- codex MCP tools ---

// "/tmp" is the mcp-tool argument, not the session workdir — strip is a no-op
test("codex mcpToolCall started -> Tool card with mcp tool name + arg", () => {
  const ev = { kind: "tool-call", tool: "mcp_tool_call", phase: "started", call_id: "m1", detail: { type: "mcpToolCall", toolName: "spawn_session", arguments: { name: "test", workdir: "/tmp" } } } as const
  const [r] = toActivityEvents("codex", ev, NOW, WD)
  expect(r).toEqual({ ts: ISO, kind: "tool", tool: "Tool", title: "Tool: spawn_session /tmp", detail: "spawn_session /tmp", phase: "started", callId: "m1" })
})

test("codex mcpToolCall started with no arg -> just tool name", () => {
  const ev = { kind: "tool-call", tool: "mcp_tool_call", phase: "started", call_id: "m2", detail: { type: "mcpToolCall", toolName: "reply", arguments: {} } } as const
  const [r] = toActivityEvents("codex", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool", tool: "Tool", title: "Tool: reply", detail: "reply" })
})

test("codex mcpToolCall completed -> result detail", () => {
  const ev = { kind: "tool-call", tool: "mcp_tool_call", phase: "completed", call_id: "m1", detail: { type: "mcpToolCall", toolName: "spawn_session", arguments: { name: "test" }, result: '{"session_id":"abc"}' } } as const
  const [r] = toActivityEvents("codex", ev, NOW, WD)
  expect(r).toEqual({ ts: ISO, kind: "tool_result", title: "done", detail: '{"session_id":"abc"}', phase: "completed", callId: "m1" })
})

// --- cursor ---
// Shapes verified against cursor-agent's bundled protobuf-es toJSON output:
// the tool_call field is an agent.v1.ToolCall message whose oneof `tool` unwraps
// to { <caseName>: { args: {...}, result: {...} } }. Args are nested under `.args`,
// and the result oneof unwraps to { success: { stdout,...} } | { failure: { stderr,...} }.

test("cursor read started -> Read card with path summary (workdir-relative)", () => {
  const ev = { kind: "tool-call", tool: "readToolCall", phase: "started", call_id: "x", detail: { tool_call: { readToolCall: { args: { path: "/w/a/b.ts" } } } } } as const
  expect(toActivityEvents("cursor", ev, NOW, WD)).toEqual([
    { ts: ISO, kind: "tool", tool: "Read", title: "Read: a/b.ts", detail: "/w/a/b.ts", phase: "started", callId: "x" },
  ])
})

test("cursor shell started -> Bash with command", () => {
  const ev = { kind: "tool-call", tool: "shellToolCall", phase: "started", call_id: "b1", detail: { tool_call: { shellToolCall: { args: { command: "npm test" } } } } } as const
  expect(toActivityEvents("cursor", ev, NOW, WD)[0]).toMatchObject({ kind: "tool", tool: "Bash", title: "Bash: npm test", detail: "npm test" })
})

test("cursor grep started -> Grep with pattern from args", () => {
  const ev = { kind: "tool-call", tool: "grepToolCall", phase: "started", call_id: "g1", detail: { tool_call: { grepToolCall: { args: { pattern: "TODO", path: "/src" } } } } } as const
  expect(toActivityEvents("cursor", ev, NOW, WD)[0]).toMatchObject({ kind: "tool", tool: "Grep", title: "Grep: TODO", detail: "TODO" })
})

test("cursor completed -> detail from result.success.stdout", () => {
  const ev = { kind: "tool-call", tool: "shellToolCall", phase: "completed", call_id: "b1", detail: { tool_call: { shellToolCall: { args: { command: "npm test" }, result: { success: { stdout: "tests passed", stderr: "" } } } } } } as const
  const [r] = toActivityEvents("cursor", ev, NOW, WD)
  expect(r).toEqual({ ts: ISO, kind: "tool_result", title: "done", detail: "tests passed", phase: "completed", callId: "b1" })
})

test("cursor failed -> error title with detail from result.failure.stderr", () => {
  const ev = { kind: "tool-call", tool: "shellToolCall", phase: "failed", call_id: "b2", detail: { tool_call: { shellToolCall: { args: { command: "badcmd" }, result: { failure: { exitCode: 127, stderr: "command not found" } } } } } } as const
  const [r] = toActivityEvents("cursor", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool_result", title: "error", detail: "command not found", callId: "b2" })
})

// --- opencode ---

test("opencode bash started -> Bash with command from state.input", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "started", call_id: "oc1", detail: { type: "tool", tool: "bash", callID: "oc1", state: { status: "running", input: { command: "npm install" } } } } as const
  expect(toActivityEvents("opencode", ev, NOW, WD)).toEqual([
    { ts: ISO, kind: "tool", tool: "Bash", title: "Bash: npm install", detail: "npm install", phase: "started", callId: "oc1" },
  ])
})

test("opencode read started -> Read with path from state.input (workdir-relative)", () => {
  const ev = { kind: "tool-call", tool: "read", phase: "started", call_id: "oc2", detail: { type: "tool", tool: "read", callID: "oc2", state: { status: "running", input: { path: "/w/src/main.ts" } } } } as const
  expect(toActivityEvents("opencode", ev, NOW, WD)[0]).toMatchObject({ kind: "tool", tool: "Read", title: "Read: src/main.ts", detail: "/w/src/main.ts" })
})

test("opencode MCP tool started -> short name from mcp__ prefix", () => {
  const ev = { kind: "tool-call", tool: "mcp__mux-shim__reply", phase: "started", call_id: "oc3", detail: { type: "tool", tool: "mcp__mux-shim__reply", callID: "oc3", state: { status: "running", input: { text: "hello world" } } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool", tool: "Reply", title: "Reply: hello world", detail: "hello world" })
})

test("opencode MCP tool no arg -> just tool name", () => {
  const ev = { kind: "tool-call", tool: "mcp__mux-shim__reply", phase: "started", call_id: "oc4", detail: { type: "tool", tool: "mcp__mux-shim__reply", callID: "oc4", state: { status: "running", input: {} } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool", tool: "Reply", title: "Reply", detail: "" })
})

test("opencode completed -> detail from state.output (not title)", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "completed", call_id: "oc1", detail: { type: "tool", tool: "bash", callID: "oc1", state: { status: "completed", input: { command: "npm install" }, output: "added 42 packages", title: "npm install" } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toEqual({ ts: ISO, kind: "tool_result", title: "done", detail: "added 42 packages", phase: "completed", callId: "oc1" })
})

test("opencode completed with output but no title -> uses output", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "completed", call_id: "oc5", detail: { type: "tool", tool: "bash", callID: "oc5", state: { status: "completed", input: { command: "ls" }, output: "file1\nfile2" } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool_result", title: "done", detail: "file1" }) // firstLine
})

test("opencode started with state.title but no input -> uses title as summary", () => {
  const ev = { kind: "tool-call", tool: "task", phase: "started", call_id: "oc7", detail: { type: "tool", tool: "task", callID: "oc7", state: { status: "running", title: "Searching codebase for API routes" } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool", tool: "Task", title: "Task: Searching codebase for API routes", detail: "Searching codebase for API routes" })
})

test("opencode started with no input and no title -> just tool name", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "started", call_id: "oc8", detail: { type: "tool", tool: "bash", callID: "oc8", state: { status: "running" } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool", tool: "Bash", title: "Bash", detail: "" })
})

test("opencode failed -> detail from state.error", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "failed", call_id: "oc6", detail: { type: "tool", tool: "bash", callID: "oc6", state: { status: "error", input: { command: "bad" }, error: "command not found: bad" } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool_result", title: "error", detail: "command not found: bad", callId: "oc6" })
})

test("opencode completed -> detail from state.content when output absent", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "completed", call_id: "oc9", detail: { type: "tool", tool: "bash", callID: "oc9", state: { status: "completed", input: { command: "ls" }, title: "ls", content: [{ type: "text", text: "file1\nfile2" }] } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool_result", title: "done", detail: "file1", callId: "oc9" })
})

test("opencode started -> summary from pending raw when input absent", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "started", call_id: "oc10", detail: { type: "tool", tool: "bash", callID: "oc10", state: { status: "pending", raw: "npm install", input: {} } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool", tool: "Bash", title: "Bash: npm install", detail: "npm install" })
})

test("opencode edit started -> Edit with filePath from state.input (workdir-relative)", () => {
  const ev = { kind: "tool-call", tool: "edit", phase: "started", call_id: "oc11", detail: { type: "tool", tool: "edit", callID: "oc11", state: { status: "running", input: { filePath: "/w/src/main.ts" } } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool", tool: "Edit", title: "Edit: src/main.ts", detail: "/w/src/main.ts" })
})

// --- defensive ---

test("defensive: missing detail -> no summary, no throw", () => {
  const ev = { kind: "tool-call", tool: "shell", phase: "started", call_id: "c1" } as const
  expect(toActivityEvents("codex", ev, NOW, WD)[0]).toEqual({ ts: ISO, kind: "tool", tool: "Bash", title: "Bash", detail: "", phase: "started", callId: "c1" })
})

test("defensive: null detail", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "started", call_id: "c1", detail: null } as const
  expect(toActivityEvents("codex", ev, NOW, WD)[0]).toMatchObject({ kind: "tool", tool: "Bash", title: "Bash", detail: "" })
})

// --- workdir strip ---

test("workdir strip: outside-workdir path stays absolute", () => {
  const ev = { kind: "tool-call", tool: "read", phase: "started", call_id: "x", detail: { type: "tool", tool: "read", callID: "x", state: { status: "running", input: { path: "/etc/hosts" } } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool", tool: "Read", title: "Read: /etc/hosts" })
})

test("workdir strip: undefined workdir -> no-op", () => {
  const ev = { kind: "tool-call", tool: "read", phase: "started", call_id: "x", detail: { type: "tool", tool: "read", callID: "x", state: { status: "running", input: { path: "/src/main.ts" } } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, undefined)
  expect(r).toMatchObject({ kind: "tool", tool: "Read", title: "Read: /src/main.ts" })
})
