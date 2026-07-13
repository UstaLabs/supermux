import { test, expect } from "bun:test"
import { parseGrokUpdate } from "./stream-parser"

test("agent_message_chunk -> assistant-message delta", () => {
  const p = { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Creating" } } }
  expect(parseGrokUpdate(p)).toEqual([{ kind: "assistant-message", text: "Creating" }])
})

test("agent_thought_chunk -> thought delta", () => {
  const p = { update: { sessionUpdate: "agent_thought_chunk", content: { type: "text", text: "The" } } }
  expect(parseGrokUpdate(p)).toEqual([{ kind: "thought", text: "The" }])
})

test("tool_call -> tool-call started with title+rawInput detail", () => {
  const p = { update: { sessionUpdate: "tool_call", toolCallId: "call-abc-0", title: "write",
    rawInput: { file_path: "/w/poem.txt", content: "roses" } } }
  expect(parseGrokUpdate(p)).toEqual([{ kind: "tool-call", phase: "started", call_id: "call-abc-0",
    tool: "write", detail: { title: "write", rawInput: { file_path: "/w/poem.txt", content: "roses" } } }])
})

test("tool_call_update completed -> tool-call completed", () => {
  const p = { update: { sessionUpdate: "tool_call_update", toolCallId: "call-abc-0", kind: "edit",
    title: "Write `/w/poem.txt`", status: "completed", content: [{ type: "content", content: { type: "text", text: "ok" } }] } }
  expect(parseGrokUpdate(p)).toEqual([{ kind: "tool-call", phase: "completed", call_id: "call-abc-0",
    tool: "edit", detail: { kind: "edit", title: "Write `/w/poem.txt`", status: "completed",
      content: [{ type: "content", content: { type: "text", text: "ok" } }] } }])
})

test("tool_call_update failed -> tool-call failed", () => {
  const p = { update: { sessionUpdate: "tool_call_update", toolCallId: "c1", status: "failed",
    content: [{ type: "content", content: { type: "text", text: "denied" } }] } }
  const [ev] = parseGrokUpdate(p)
  expect(ev).toMatchObject({ kind: "tool-call", phase: "failed", call_id: "c1" })
})

test("unknown sessionUpdate -> []", () => {
  expect(parseGrokUpdate({ update: { sessionUpdate: "available_commands_update" } })).toEqual([])
  expect(parseGrokUpdate({ update: { sessionUpdate: "user_message_chunk", content: { type: "text", text: "hi" } } })).toEqual([])
})
