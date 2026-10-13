import { describe, expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { join } from "node:path"
import { createAntigravityNormalizer, agyToolInput } from "../src/antigravity/normalize.js"
import { ACCOUNT_UNAVAILABLE_ERROR } from "../src/accounts/policy.js"
import type { NormalizedBody } from "../src/events/normalized.js"

const frames = (name: string) => readFileSync(join(import.meta.dir, "fixtures/real/antigravity", name), "utf8")
  .split("\n").filter(l => l.trim()).map(l => JSON.parse(l) as Record<string, any>)
function replay(name: string, setup?: (n: ReturnType<typeof createAntigravityNormalizer>) => void): NormalizedBody[] {
  const n = createAntigravityNormalizer(); setup?.(n)
  const out: NormalizedBody[] = []
  for (const f of frames(name)) out.push(...n({ protocol: "native", value: f }))
  out.push(...n.flush())
  return out
}
const resultOf = (name: string) => frames(name).find(f => f.event === "result")!.result
const SI = { kind: "session-info", cwd: "/tmp/agy-probe" }

describe("antigravity normalizer (real agy 1.3.4 captures)", () => {
  test("multi-turn: one message per step_index, per-turn usage from step sums", () => {
    expect(replay("multi-turn.ndjson")).toEqual([
      SI,
      { kind: "assistant-delta", messageId: "0:1", text: "OK." },
      { kind: "assistant-delta", messageId: "0:1", text: "\n" },
      { kind: "assistant-message", messageId: "0:1", text: "OK.\n" },
      { kind: "usage", tokens: { input: 11829, output: 78, total: 11907 } },
      { kind: "assistant-delta", messageId: "0:3", text: "PINEAPPLE" },
      { kind: "assistant-delta", messageId: "0:3", text: "\n" },
      { kind: "assistant-message", messageId: "0:3", text: "PINEAPPLE\n" },
      { kind: "usage", tokens: { input: 11985, output: 78, total: 12063 } },
    ])
  })

  test("tool-turn: write_to_file + run_command, streamed reply", () => {
    const out = replay("tool-turn.ndjson")
    const RESPONSE = resultOf("tool-turn.ndjson").response as string
    const OUT = frames("tool-turn.ndjson").find(f => f.step_update?.step_index === 4 && f.step_update.state === "DONE")!.step_update.tool_info.output
    const deltas = out.filter(b => b.kind === "assistant-delta")
    expect(deltas).toHaveLength(12)
    expect(deltas.every(b => b.kind === "assistant-delta" && b.messageId === "0:5")).toBe(true)
    expect(deltas.map(b => (b as any).text).join("")).toBe(RESPONSE)
    expect(out.filter(b => b.kind !== "assistant-delta")).toEqual([
      SI,
      { kind: "tool-call", callId: "0:2", tool: "write_to_file", phase: "started", category: "edit",
        input: { TargetFile: "/tmp/agy-probe/hello.txt", file_path: "/tmp/agy-probe/hello.txt" }, locations: [{ path: "/tmp/agy-probe/hello.txt" }] },
      { kind: "tool-call", callId: "0:2", tool: "write_to_file", phase: "completed", category: "edit" },
      { kind: "tool-call", callId: "0:4", tool: "run_command", phase: "started", category: "execute", input: { CommandLine: "ls -la", command: "ls -la" } },
      { kind: "command-output", callId: "0:4", stream: "merged", delta: OUT },
      { kind: "tool-call", callId: "0:4", tool: "run_command", phase: "completed", category: "execute", output: OUT },
      { kind: "assistant-message", messageId: "0:5", text: RESPONSE },
      { kind: "usage", tokens: { input: 36565, output: 598, total: 37163 } },
    ])
  })

  test("permission-denied: tool completes with no output; denial becomes a visible warning", () => {
    expect(replay("permission-denied.ndjson")).toEqual([
      SI,
      { kind: "tool-call", callId: "0:2", tool: "run_command", phase: "started", category: "execute",
        input: { CommandLine: "touch /tmp/agy-probe/perm.txt", command: "touch /tmp/agy-probe/perm.txt" } },
      { kind: "tool-call", callId: "0:2", tool: "run_command", phase: "completed", category: "execute" },
      { kind: "usage", tokens: { input: 11834, output: 222, total: 12056 } },
      { kind: "warning", source: "antigravity", message: "Antigravity denied: RunCommand — switch permission mode to allow" },
    ])
  })

  test("mcp-call: call_mcp_tool unwraps to mcp__server__tool + mcp-tool bodies", () => {
    const out = replay("mcp-call.ndjson")
    const RESPONSE = resultOf("mcp-call.ndjson").response
    const P = "/tmp/agyhome/.gemini/antigravity-cli/mcp/probe-echo/echo.json"
    expect(out.filter(b => b.kind !== "assistant-delta")).toEqual([
      SI,
      { kind: "tool-call", callId: "0:2", tool: "view_file", phase: "started", category: "read", input: { AbsolutePath: P, file_path: P }, locations: [{ path: P }] },
      { kind: "tool-call", callId: "0:2", tool: "view_file", phase: "completed", category: "read", output: "1 lines, 143 bytes" },
      { kind: "tool-call", callId: "0:4", tool: "mcp__probe-echo__echo", phase: "started", category: "mcp", input: { server: "probe-echo", tool: "echo", arguments: { message: "ZEBRA" } } },
      { kind: "mcp-tool", callId: "0:4", server: "probe-echo", tool: "echo", phase: "started", arguments: { message: "ZEBRA" } },
      { kind: "mcp-tool", callId: "0:4", server: "probe-echo", tool: "echo", phase: "completed", result: "ECHO:ZEBRA" },
      { kind: "tool-call", callId: "0:4", tool: "mcp__probe-echo__echo", phase: "completed", category: "mcp", output: "ECHO:ZEBRA" },
      { kind: "assistant-message", messageId: "0:5", text: RESPONSE },
      { kind: "usage", tokens: { input: 39391, output: 549, total: 39940 } },
    ])
  })

  test("interrupted: the open tool fails, no error body (Session reports the interrupt)", () => {
    expect(replay("interrupted.ndjson")).toEqual([
      SI,
      { kind: "tool-call", callId: "0:2", tool: "run_command", phase: "started", category: "execute",
        input: { CommandLine: "sleep 40 && echo finished", command: "sleep 40 && echo finished" } },
      { kind: "tool-call", callId: "0:2", tool: "run_command", phase: "failed", category: "execute" },
      { kind: "usage", tokens: { input: 12657, output: 875, total: 13532 } },
    ])
  })

  test("server aliases map supermux-* back to the context name", () => {
    const n = createAntigravityNormalizer(); n.setServerAliases({ "supermux-mux-shim": "mux-shim" })
    const out = n({ protocol: "native", value: { event: "step_update", step_update: { step_index: 7, state: "ACTIVE", step_type: "tool", tool_name: "call_mcp_tool",
      tool_info: { name: "call_mcp_tool", parameters: { ServerName: "supermux-mux-shim", ToolName: "attach", Arguments: { files: ["/a"] } } } } } })
    expect(out[0]).toMatchObject({ kind: "tool-call", tool: "mcp__mux-shim__attach" })
    expect(out[1]).toMatchObject({ kind: "mcp-tool", server: "mux-shim", tool: "attach" })
  })

  test("errors: eligibility marks the account unavailable; others are plain", () => {
    const n = createAntigravityNormalizer()
    const r = (error: string) => n({ protocol: "native", value: { event: "result", result: { conversation_id: "", status: "ERROR", response: "", error } } })
    expect(r("Eligibility check failed: Your current account is not eligible")).toContainEqual(
      { kind: "error", message: "Eligibility check failed: Your current account is not eligible", errorType: ACCOUNT_UNAVAILABLE_ERROR })
    expect(r("model exploded")).toContainEqual({ kind: "error", message: "model exploded" })
  })

  test("newGeneration namespaces ids; unknown frames are ignored", () => {
    const n = createAntigravityNormalizer(); n.newGeneration("x")
    expect(n({ protocol: "native", value: { event: "step_update", step_update: { step_index: 1, state: "DONE", step_type: "agent_response", text_delta: "hi" } } }))
      .toEqual([{ kind: "assistant-delta", messageId: "x:1", text: "hi" }, { kind: "assistant-message", messageId: "x:1", text: "hi" }])
    expect(n({ protocol: "native", value: { event: "step_update", step_update: { step_index: 2, state: "DONE", step_type: "planner_thing" } } })).toEqual([])
    expect(n({ protocol: "native", value: { event: "mystery" } })).toEqual([])
    expect(n({ protocol: "acp", value: {} as any })).toEqual([])
  })

  test("flush emits text left open by a dead process", () => {
    const n = createAntigravityNormalizer()
    n({ protocol: "native", value: { event: "step_update", step_update: { step_index: 3, state: "ACTIVE", step_type: "agent_response", text_delta: "partial" } } })
    expect(n.flush()).toEqual([{ kind: "assistant-message", messageId: "0:3", text: "partial" }])
  })

  test("agyToolInput keeps raw keys and never overrides canonical ones", () => {
    expect(agyToolInput({ CommandLine: "x", command: "keep" })).toEqual({ CommandLine: "x", command: "keep" })
    expect(agyToolInput(undefined)).toBeUndefined()
  })
})
