import { test, expect } from "bun:test"
import { EventEmitter } from "events"
import { wireClaudeStateEvents } from "./state-projection"

function harness() {
  const adapter = new EventEmitter()
  const stateCalls: Array<[string, string | undefined]> = []
  const errorCalls: Array<[string, string]> = []
  wireClaudeStateEvents(adapter as any, {
    onState: (event, tool) => stateCalls.push([event, tool]),
    onError: (type, message) => errorCalls.push([type, message]),
  })
  return { adapter, stateCalls, errorCalls }
}

test("turn-start → UserPromptSubmit", () => {
  const h = harness()
  h.adapter.emit("turn-start", { kind: "turn-start" })
  expect(h.stateCalls).toEqual([["UserPromptSubmit", undefined]])
})

test("tool-call started → PreToolUse with tool; completed/failed → PostToolUse", () => {
  const h = harness()
  h.adapter.emit("tool-call", { kind: "tool-call", tool: "Bash", phase: "started", call_id: "" })
  h.adapter.emit("tool-call", { kind: "tool-call", tool: "Bash", phase: "completed", call_id: "" })
  h.adapter.emit("tool-call", { kind: "tool-call", tool: "Read", phase: "failed", call_id: "" })
  expect(h.stateCalls).toEqual([
    ["PreToolUse", "Bash"],
    ["PostToolUse", undefined],
    ["PostToolUse", undefined],
  ])
})

test("turn-complete → Stop", () => {
  const h = harness()
  h.adapter.emit("turn-complete", { kind: "turn-complete" })
  expect(h.stateCalls).toEqual([["Stop", undefined]])
})

test("error → onError with type + message", () => {
  const h = harness()
  h.adapter.emit("error", { kind: "error", error: new Error("boom"), errorType: "timeout" })
  expect(h.errorCalls).toEqual([["timeout", "boom"]])
})

test("error without errorType defaults to 'error'", () => {
  const h = harness()
  h.adapter.emit("error", { kind: "error", error: new Error("x") })
  expect(h.errorCalls).toEqual([["error", "x"]])
})
