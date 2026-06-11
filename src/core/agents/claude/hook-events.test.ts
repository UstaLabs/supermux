import { test, expect } from "bun:test"
import { claudeHookToAgentEvent } from "./hook-events"

test("UserPromptSubmit → turn-start", () => {
  expect(claudeHookToAgentEvent("UserPromptSubmit")).toEqual({ kind: "turn-start" })
})

test("PreToolUse → tool-call started with tool name", () => {
  expect(claudeHookToAgentEvent("PreToolUse", { tool: "Bash" })).toEqual({
    kind: "tool-call", tool: "Bash", phase: "started", call_id: "",
  })
})

test("PostToolUse → tool-call completed", () => {
  expect(claudeHookToAgentEvent("PostToolUse", { tool: "Bash" })).toEqual({
    kind: "tool-call", tool: "Bash", phase: "completed", call_id: "",
  })
})

test("Stop → turn-complete", () => {
  expect(claudeHookToAgentEvent("Stop")).toEqual({ kind: "turn-complete" })
})

test("StopFailure → error carrying type + message", () => {
  const ev = claudeHookToAgentEvent("StopFailure", { errorType: "timeout", errorMessage: "boom" })
  expect(ev?.kind).toBe("error")
  expect((ev as any).errorType).toBe("timeout")
  expect((ev as any).error).toBeInstanceOf(Error)
  expect((ev as any).error.message).toBe("boom")
})

test("StopFailure defaults type/message when absent", () => {
  const ev = claudeHookToAgentEvent("StopFailure") as any
  expect(ev.errorType).toBe("error")
  expect(ev.error.message).toBe("Agent turn failed")
})

test("unknown/no-op hooks (e.g. SessionStart) → null", () => {
  expect(claudeHookToAgentEvent("SessionStart")).toBeNull()
  expect(claudeHookToAgentEvent("Whatever")).toBeNull()
})
