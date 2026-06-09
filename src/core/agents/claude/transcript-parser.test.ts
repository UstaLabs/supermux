// src/core/agents/claude/transcript-parser.test.ts
import { test, expect } from "bun:test"
import { parseTranscriptLine } from "./transcript-parser"

const TS = "2026-05-29T18:40:14.188Z"

test("parses a tool_use block", () => {
  const line = JSON.stringify({
    type: "assistant", timestamp: TS,
    message: { content: [{ type: "tool_use", id: "t1", name: "Bash", input: { command: "npm test" } }] },
  })
  const events = parseTranscriptLine(line)
  expect(events).toEqual([
    { ts: TS, kind: "tool", tool: "Bash", title: "Bash: npm test", detail: '{"command":"npm test"}', phase: "started", callId: "t1" },
  ])
})

test("ignores thinking blocks (content is redacted; durations come from agent state)", () => {
  const line = JSON.stringify({
    type: "assistant", timestamp: TS,
    message: { content: [{ type: "thinking", thinking: "Let me check the config.\nThen run tests.", signature: "x" }] },
  })
  expect(parseTranscriptLine(line)).toEqual([])
})

test("parses a tool_result block (string content, error flag)", () => {
  const line = JSON.stringify({
    type: "user", timestamp: TS,
    message: { content: [{ type: "tool_result", tool_use_id: "t1", content: "boom", is_error: true }] },
  })
  expect(parseTranscriptLine(line)).toEqual([
    { ts: TS, kind: "tool_result", title: "error", detail: "boom", phase: "completed", callId: "t1" },
  ])
})

test("ignores text blocks and unknown top-level types", () => {
  expect(parseTranscriptLine(JSON.stringify({ type: "assistant", timestamp: TS, message: { content: [{ type: "text", text: "hi" }] } }))).toEqual([])
  expect(parseTranscriptLine(JSON.stringify({ type: "system", timestamp: TS }))).toEqual([])
})

test("defensive: never throws on malformed input", () => {
  expect(parseTranscriptLine("not json")).toEqual([])
  expect(parseTranscriptLine("")).toEqual([])
  expect(parseTranscriptLine(JSON.stringify({ type: "assistant", message: { content: "a plain string" } }))).toEqual([])
  const mixed = JSON.stringify({ type: "assistant", timestamp: TS, message: { content: [{ type: "weird" }, { type: "tool_use", name: "Read", input: { file_path: "a.ts" } }] } })
  expect(parseTranscriptLine(mixed)).toEqual([
    { ts: TS, kind: "tool", tool: "Read", title: "Read: a.ts", detail: '{"file_path":"a.ts"}', phase: "started" },
  ])
})

test("truncates long detail and flags it", () => {
  const big = "x".repeat(5000)
  const line = JSON.stringify({ type: "user", timestamp: TS, message: { content: [{ type: "tool_result", tool_use_id: "t1", content: big }] } })
  const [ev] = parseTranscriptLine(line)
  expect(ev).toBeDefined()
  expect(ev!.truncated).toBe(true)
  expect(ev!.detail!.length).toBeLessThan(big.length)
})

test("truncated detail is capped at DETAIL_MAX length", () => {
  const big = "x".repeat(5000)
  const line = JSON.stringify({ type: "user", timestamp: TS, message: { content: [{ type: "tool_result", tool_use_id: "t1", content: big }] } })
  const [ev] = parseTranscriptLine(line)
  expect(ev).toBeDefined()
  expect(ev!.detail!.length).toBe(2000)
})

test("falls back to epoch ts when timestamp is missing", () => {
  const line = JSON.stringify({ type: "assistant", message: { content: [{ type: "tool_use", name: "Bash", input: { command: "ls" } }] } })
  const [ev] = parseTranscriptLine(line)
  expect(ev).toBeDefined()
  expect(ev!.ts).toBe("1970-01-01T00:00:00.000Z")
})

test("tool_result with array content concatenates text blocks", () => {
  const line = JSON.stringify({ type: "user", timestamp: TS, message: { content: [{ type: "tool_result", tool_use_id: "t1", content: [{ type: "text", text: "line1 " }, { type: "text", text: "line2" }] }] } })
  const [ev] = parseTranscriptLine(line)
  expect(ev).toBeDefined()
  expect(ev!.kind).toBe("tool_result")
  expect(ev!.detail).toBe("line1 line2")
})

test("tool_use without id produces no callId key", () => {
  const line = JSON.stringify({
    type: "assistant", timestamp: TS,
    message: { content: [{ type: "tool_use", name: "Bash", input: { command: "ls" } }] },
  })
  const [ev] = parseTranscriptLine(line)
  expect(ev).toBeDefined()
  expect("callId" in ev!).toBe(false)
})
