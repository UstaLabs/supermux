import { describe, expect, test } from "bun:test"
import { createCursorNormalizer } from "../src/cursor/normalize.js"
import type { AgentUpdate } from "../src/types.js"

const native = (value: unknown, extra?: { replay?: boolean }): AgentUpdate => ({
  protocol: "native",
  value,
  ...(extra?.replay ? { replay: true } : {}),
})

describe("cursor normalizer", () => {
  test("system/init session-info", () => {
    const n = createCursorNormalizer()
    expect(n(native({ type: "system", subtype: "init", cwd: "/w", model: "composer" }))).toEqual([
      { kind: "session-info", cwd: "/w", model: "composer" },
    ])
  })

  test("assistant cumulative snapshot emits suffix deltas", () => {
    const n = createCursorNormalizer()
    expect(n(native({ type: "assistant", message: { content: [{ type: "text", text: "Hel" }] } }))[0]).toMatchObject({
      kind: "assistant-delta",
      messageId: "assistant:1",
      text: "Hel",
    })
    expect(n(native({ type: "assistant", message: { content: [{ type: "text", text: "Hello" }] } }))[0]).toMatchObject({
      kind: "assistant-delta",
      text: "lo",
    })
    expect(n.flush()).toEqual([{ kind: "assistant-message", messageId: "assistant:1", text: "Hello" }])
  })

  test("tool_call splits assistant runs", () => {
    const n = createCursorNormalizer()
    n(native({ type: "assistant", message: { content: [{ type: "text", text: "before" }] } }))
    const tool = n(native({
      type: "tool_call",
      subtype: "started",
      call_id: "c1",
      tool_call: { readToolCall: { args: { path: "a" } } },
    }))
    expect(tool[0]).toMatchObject({ kind: "assistant-message", messageId: "assistant:1", text: "before" })
    expect(tool[1]).toMatchObject({ kind: "tool-call", callId: "c1", tool: "readToolCall", phase: "started", input: { path: "a" } })
    n(native({ type: "assistant", message: { content: [{ type: "text", text: "after" }] } }))
    expect(n.flush()[0]).toMatchObject({ kind: "assistant-message", messageId: "assistant:2", text: "after" })
  })

  test("tool_call completed includes output", () => {
    const n = createCursorNormalizer()
    expect(n(native({
      type: "tool_call",
      subtype: "completed",
      call_id: "c1",
      tool_call: { shellToolCall: { args: { command: "ls" }, result: { stdout: "ok" } } },
    }))[0]).toMatchObject({
      kind: "tool-call",
      callId: "c1",
      tool: "shellToolCall",
      phase: "completed",
      output: { stdout: "ok" },
    })
  })

  test("result success finalizes assistant; result error emits error", () => {
    const n = createCursorNormalizer()
    n(native({ type: "assistant", message: { content: [{ type: "text", text: "hi" }] } }))
    expect(n(native({ type: "result", subtype: "success", is_error: false, result: "ok" }))).toEqual([
      { kind: "assistant-message", messageId: "assistant:1", text: "hi" },
    ])
    expect(n(native({ type: "result", is_error: true, result: "boom" }))[0]).toMatchObject({
      kind: "error",
      message: "boom",
    })
  })

  test("unknown ignored; replay passthrough", () => {
    const n = createCursorNormalizer()
    expect(n(native({ type: "user", message: { content: [{ type: "text", text: "q" }] } }))).toEqual([])
    expect(n(native({ type: "system", subtype: "status" }))).toEqual([])
    expect(n({ protocol: "acp", value: { sessionUpdate: "plan", entries: [] } })).toEqual([])
    const replayed = n(native({ type: "system", subtype: "init", cwd: "/", model: "m" }, { replay: true }))
    expect(replayed[0]?.kind).toBe("session-info")
  })
})
