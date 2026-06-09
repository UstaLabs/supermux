import { describe, test, expect } from "bun:test"
import type { AgentAdapter, AgentKind, ToolCallEvent, AssistantMessageEvent } from "../../src/core/agents/types"

describe("AgentAdapter interface", () => {
  test("AgentKind union includes claude, codex, cursor", () => {
    const kinds: AgentKind[] = ["claude", "codex", "cursor"]
    expect(kinds.length).toBe(3)
  })

  test("ToolCallEvent shape", () => {
    const ev: ToolCallEvent = { kind: "tool-call", tool: "shell", phase: "started", call_id: "x" }
    expect(ev.kind).toBe("tool-call")
  })

  test("AssistantMessageEvent shape", () => {
    const ev: AssistantMessageEvent = { kind: "assistant-message", text: "hi" }
    expect(ev.text).toBe("hi")
  })
})
