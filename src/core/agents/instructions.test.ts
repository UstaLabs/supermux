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

test("the inbox block tells a shell to read the file with exactly cat or tail, with the real path", () => {
  const file = "/home/u/.mux/session_inboxes/7c1d2e9a-0000.jsonl"
  const lines = inboxInstructions("7c1d2e9a-0000", "/home/u/.mux/session_inboxes").split("\n")
  expect(lines).toContain(`From a shell, read it with exactly \`cat ${file}\` or \`tail ${file}\`.`)
  // The earlier lines are all still there, the new one after the one that names the file.
  expect(lines[0]).toBe("## Inbox")
  expect(lines[1]).toContain(`Your inbox: ${file}`)
  expect(lines[2]).toContain("From a shell")
  expect(lines).toHaveLength(5)
  // A folder that needs quoting is shown quoted, so the command can be typed as shown.
  const spaced = inboxInstructions("7c1d2e9a-0000", "/Users/John Smith/.mux/session_inboxes")
  expect(spaced).toContain("`cat '/Users/John Smith/.mux/session_inboxes/7c1d2e9a-0000.jsonl'` or `tail '/Users/John Smith/.mux/session_inboxes/7c1d2e9a-0000.jsonl'`")
})

test("sessionInstructions ends with the inbox block when it is given the session id, and has none without it", () => {
  const base = { agent: "codex" as const, sessionName: "inbox-t", workdir: tmpdir() }
  const withId = sessionInstructions({ ...base, sessionId: "7c1d2e9a-0000" })
  expect(withId).toContain("Your session id: 7c1d2e9a-0000.")
  expect(withId.trimEnd().endsWith(inboxInstructions("7c1d2e9a-0000").trimEnd())).toBe(true)
  expect(sessionInstructions(base)).not.toContain("## Inbox")
})
