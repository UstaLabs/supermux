import { test, expect } from "bun:test"
import { buildVoicePayload } from "./voice-context"

test("maps messages to role/text, drops empty, includes draft + skills", () => {
  const payload = buildVoicePayload("helo wrld", [
    { direction: "inbound", text: "deploy the app" } as any,
    { direction: "outbound", text: "done" } as any,
    { direction: "inbound", text: undefined } as any,    // dropped
    { direction: "inbound", text: "   " } as any,          // dropped (whitespace)
  ], ["brainstorming", "code-review"])
  expect(payload.draft).toBe("helo wrld")
  expect(payload.context.recentMessages).toEqual([
    { role: "user", text: "deploy the app" },
    { role: "assistant", text: "done" },
  ])
  expect(payload.context.skills).toEqual(["brainstorming", "code-review"])
})
