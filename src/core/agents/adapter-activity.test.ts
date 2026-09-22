import { test, expect } from "bun:test"
import { toActivityEvents } from "./adapter-activity"

const NOW = 1730000000000
const ISO = new Date(NOW).toISOString()
const WD = "/w"
// --- cursor ---
// Shapes verified against cursor-agent's bundled protobuf-es toJSON output:
// the tool_call field is an agent.v1.ToolCall message whose oneof `tool` unwraps
// to { <caseName>: { args: {...}, result: {...} } }. Args are nested under `.args`,
// and the result oneof unwraps to { success: { stdout,...} } | { failure: { stderr,...} }.

test("cursor read started -> Read card with path summary (workdir-relative)", () => {
  const ev = { kind: "tool-call", tool: "readToolCall", phase: "started", call_id: "x", detail: { tool_call: { readToolCall: { args: { path: "/w/a/b.ts" } } } } } as const
  expect(toActivityEvents("cursor", ev, NOW, WD)[0]).toMatchObject({
    ts: ISO, kind: "tool", tool: "Read", title: "Read: a/b.ts", detail: "/w/a/b.ts", phase: "started", callId: "x",
  })
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
  expect(r).toMatchObject({
    ts: ISO, kind: "tool_result", title: "done", detail: "tests passed", phase: "completed", callId: "b1",
    body: { kind: "bash", command: "npm test", output: "tests passed" },
  })
})

test("cursor failed -> error title with detail from result.failure.stderr", () => {
  const ev = { kind: "tool-call", tool: "shellToolCall", phase: "failed", call_id: "b2", detail: { tool_call: { shellToolCall: { args: { command: "badcmd" }, result: { failure: { exitCode: 127, stderr: "command not found" } } } } } } as const
  const [r] = toActivityEvents("cursor", ev, NOW, WD)
  expect(r).toMatchObject({ kind: "tool_result", title: "error", detail: "command not found", callId: "b2" })
})

// --- opencode ---

test("opencode bash started -> Bash with command from state.input", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "started", call_id: "oc1", detail: { type: "tool", tool: "bash", callID: "oc1", state: { status: "running", input: { command: "npm install" } } } } as const
  expect(toActivityEvents("opencode", ev, NOW, WD)[0]).toMatchObject({
    ts: ISO, kind: "tool", tool: "Bash", title: "Bash: npm install", detail: "npm install", phase: "started", callId: "oc1",
    body: { kind: "bash", command: "npm install" },
  })
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
  expect(r).toMatchObject({
    ts: ISO, kind: "tool_result", title: "done", detail: "added 42 packages", phase: "completed", callId: "oc1",
    body: { kind: "bash", command: "npm install", output: "added 42 packages" },
  })
})

test("opencode completed with output but no title -> keeps multiline output", () => {
  const ev = { kind: "tool-call", tool: "bash", phase: "completed", call_id: "oc5", detail: { type: "tool", tool: "bash", callID: "oc5", state: { status: "completed", input: { command: "ls" }, output: "file1\nfile2" } } } as const
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({
    kind: "tool_result", title: "done", detail: "file1\nfile2",
    body: { kind: "bash", command: "ls", output: "file1\nfile2" },
  })
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
  expect(r).toMatchObject({ kind: "tool_result", title: "done", detail: "file1\nfile2", callId: "oc9" })
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

test("opencode edit started -> body includes oldString/newString as unified diff", () => {
  const ev = {
    kind: "tool-call", tool: "edit", phase: "started" as const, call_id: "oc12",
    detail: {
      type: "tool", tool: "edit", callID: "oc12",
      state: {
        status: "running",
        input: { filePath: "/w/src/main.ts", oldString: "const a = 1", newString: "const a = 2" },
      },
    },
  }
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({
    tool: "Edit",
    title: "Edit: src/main.ts",
    body: {
      kind: "edit",
      path: "src/main.ts",
      oldText: "const a = 1",
      newText: "const a = 2",
    },
  })
  expect(r!.body && r!.body.kind === "edit" && r!.body.diff).toContain("-const a = 1")
  expect(r!.body && r!.body.kind === "edit" && r!.body.diff).toContain("+const a = 2")
})

test("cursor editToolCall body from old/new strings", () => {
  const ev = {
    kind: "tool-call", tool: "editToolCall", phase: "started" as const, call_id: "e1",
    detail: {
      tool_call: {
        editToolCall: {
          args: { path: "/w/a.ts", old_string: "foo", new_string: "bar" },
        },
      },
    },
  }
  const [r] = toActivityEvents("cursor", ev, NOW, WD)
  expect(r).toMatchObject({
    tool: "Edit",
    body: { kind: "edit", path: "a.ts", oldText: "foo", newText: "bar" },
  })
})

test("claude-style via opencode: description from input.description", () => {
  // opencode can also carry description on input for some tools
  const ev = {
    kind: "tool-call", tool: "bash", phase: "started" as const, call_id: "oc-d",
    detail: {
      type: "tool", tool: "bash", callID: "oc-d",
      state: {
        status: "running",
        title: "npm test",
        input: { command: "npm test", description: "Run unit tests before shipping" },
      },
    },
  }
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({
    tool: "Bash",
    description: "Run unit tests before shipping",
    body: { kind: "bash", command: "npm test" },
  })
})

test("opencode state.title becomes description when it differs from command", () => {
  const ev = {
    kind: "tool-call", tool: "task", phase: "started" as const, call_id: "oc-t",
    detail: {
      type: "tool", tool: "task", callID: "oc-t",
      state: { status: "running", title: "Searching codebase for API routes" },
    },
  }
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r).toMatchObject({
    description: "Searching codebase for API routes",
  })
})

test("opencode title equal to command is not stored as description", () => {
  const ev = {
    kind: "tool-call", tool: "bash", phase: "started" as const, call_id: "oc-e",
    detail: {
      type: "tool", tool: "bash", callID: "oc-e",
      state: { status: "running", title: "npm test", input: { command: "npm test" } },
    },
  }
  const [r] = toActivityEvents("opencode", ev, NOW, WD)
  expect(r!.description).toBeUndefined()
})

test("cursor shell description from args", () => {
  const ev = {
    kind: "tool-call", tool: "shellToolCall", phase: "started" as const, call_id: "c-d",
    detail: {
      tool_call: {
        shellToolCall: {
          args: { command: "rg TODO", description: "Find remaining TODOs in the tree" },
        },
      },
    },
  }
  const [r] = toActivityEvents("cursor", ev, NOW, WD)
  expect(r).toMatchObject({
    tool: "Bash",
    description: "Find remaining TODOs in the tree",
    body: { kind: "bash", command: "rg TODO" },
  })
})

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

