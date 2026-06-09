import { describe, test, expect } from "bun:test"
import { parseCursorStream, type CursorStreamEvent } from "../../src/core/agents/cursor/stream-parser"

describe("parseCursorStream", () => {
  test("captures session_id from system:init", () => {
    const lines = [
      JSON.stringify({ type: "system", subtype: "init", session_id: "uuid-1", cwd: "/x", model: "m" }),
      JSON.stringify({ type: "result", subtype: "ok", is_error: false }),
    ]
    const events: CursorStreamEvent[] = []
    for (const l of lines) events.push(...parseCursorStream(l))
    expect(events[0]?.kind).toBe("init")
    expect((events[0] as any).session_id).toBe("uuid-1")
    expect(events[1]?.kind).toBe("result")
  })

  test("decodes assistant text", () => {
    const line = JSON.stringify({ type: "assistant", message: { content: [{ text: "hi there" }] }, session_id: "u" })
    const evs = parseCursorStream(line)
    expect(evs[0]?.kind).toBe("assistant-message")
    expect((evs[0] as any).text).toBe("hi there")
  })

  test("decodes tool_call started + completed", () => {
    const started = JSON.stringify({ type: "tool_call", subtype: "started", call_id: "c1", tool_call: { readToolCall: { path: "/x" } } })
    const completed = JSON.stringify({ type: "tool_call", subtype: "completed", call_id: "c1", result: { success: true } })
    const out = [...parseCursorStream(started), ...parseCursorStream(completed)]
    expect(out[0]?.kind).toBe("tool-call")
    expect((out[0] as any).phase).toBe("started")
    expect(out[1]?.kind).toBe("tool-call")
    expect((out[1] as any).phase).toBe("completed")
  })

  test("returns empty array on malformed JSON", () => {
    expect(parseCursorStream("{not json")).toEqual([])
  })
})
