import { expect, test } from "bun:test"
import { tmpdir } from "os"
import { inboxInstructions, sessionInstructions } from "./instructions"

test("the inbox block names the session id, its inbox file and message_session", () => {
  const text = inboxInstructions("7c1d2e9a-0000", "/home/u/.mux/session_inboxes")
  expect(text).toContain("Your session id: 7c1d2e9a-0000.")
  expect(text).toContain("/home/u/.mux/session_inboxes/7c1d2e9a-0000.jsonl")
  expect(text).toContain('Message from [name:id]')
  expect(text).toContain("message_session(")
})

test("sessionInstructions ends with the inbox block when it is given the session id, and has none without it", () => {
  const base = { agent: "codex" as const, sessionName: "inbox-t", workdir: tmpdir() }
  const withId = sessionInstructions({ ...base, sessionId: "7c1d2e9a-0000" })
  expect(withId).toContain("Your session id: 7c1d2e9a-0000.")
  expect(withId.trimEnd().endsWith(inboxInstructions("7c1d2e9a-0000").trimEnd())).toBe(true)
  expect(sessionInstructions(base)).not.toContain("## Inbox")
})
