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



