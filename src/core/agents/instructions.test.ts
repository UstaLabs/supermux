import { expect, test } from "bun:test"
import { tmpdir } from "os"
import { SESSION_INBOX_DIR } from "../../shared/paths"
import { inboxInstructions, sessionInstructions } from "./instructions"

test("the inbox block names the session id, its inbox file and message_session", () => {
  const text = inboxInstructions("7c1d2e9a-0000", "/home/u/.mux/session_inboxes")
  expect(text).toContain("Your session id: 7c1d2e9a-0000.")
  expect(text).toContain("/home/u/.mux/session_inboxes/7c1d2e9a-0000.jsonl")
  expect(text).toContain('Message from [name:id]')
  expect(text).toContain("message_session(")
})

const DIR = "/home/u/.mux/session_inboxes"
const FILE = `${DIR}/7c1d2e9a-0000.jsonl`
const HOST = { cat: "/usr/bin/cat", head: "/usr/bin/head", tail: "/usr/bin/tail", wc: "/usr/bin/wc" }

test("how to read the file depends on the agent: the shell commands where they need no prompt, the file-read tool everywhere else", () => {
  const block = (agent: Parameters<typeof inboxInstructions>[2], hostPrograms?: typeof HOST) => inboxInstructions("7c1d2e9a-0000", DIR, agent, hostPrograms).split("\n")
  // Claude and Grok: the bare commands.
  for (const agent of ["claude", "grok"] as const) {
    expect(block(agent)).toContain(`From a shell, read it with exactly \`cat ${FILE}\` or \`tail ${FILE}\`.`)
  }
  // Codex: the absolute binaries its rules were written for, never the bare names.
  const codex = block("codex", HOST)
  expect(codex).toContain(`From a shell, read it with exactly \`/usr/bin/cat ${FILE}\` or \`/usr/bin/tail ${FILE}\`.`)
  expect(codex.join("\n")).not.toContain(`\`cat ${FILE}\``)
  // Only the programs that got a rule are offered; with none (no rules written) it is the file-read tool.
  expect(block("codex", { cat: "/usr/bin/cat" } as typeof HOST)).toContain(`From a shell, read it with exactly \`/usr/bin/cat ${FILE}\`.`)
  expect(block("codex", { head: "/usr/bin/head", wc: "/usr/bin/wc" } as typeof HOST)).toContain("Read it with your file-read tool.")
  expect(block("codex")).toContain("Read it with your file-read tool.")
  // Everything else, and any kind without rules: the file-read tool.
  for (const agent of ["cursor", "opencode", "antigravity", undefined] as const) {
    const lines = block(agent, HOST)
    expect(lines).toContain("Read it with your file-read tool.")
    expect(lines.join("\n")).not.toContain("From a shell")
  }
})

test("the shell line sits after the one that names the file, the other lines are all still there", () => {
  const lines = inboxInstructions("7c1d2e9a-0000", DIR, "claude").split("\n")
  expect(lines).toHaveLength(5)
  expect(lines[0]).toBe("## Inbox")
  expect(lines[1]).toContain(`Your inbox: ${FILE}`)
  expect(lines[2]).toContain("From a shell")
  expect(lines[3]).toContain("Message from [name:id]")
  expect(lines[4]).toContain("message_session(")
})

test("a folder that needs quoting is shown quoted, and Claude (exact rules, typed text) gets no shell line at all", () => {
  const spaced = "/Users/John Smith/.mux/session_inboxes"
  const quoted = "'/Users/John Smith/.mux/session_inboxes/7c1d2e9a-0000.jsonl'"
  expect(inboxInstructions("7c1d2e9a-0000", spaced, "grok")).toContain(`\`cat ${quoted}\` or \`tail ${quoted}\``)
  expect(inboxInstructions("7c1d2e9a-0000", spaced, "codex", HOST)).toContain(`\`/usr/bin/cat ${quoted}\` or \`/usr/bin/tail ${quoted}\``)
  const claude = inboxInstructions("7c1d2e9a-0000", spaced, "claude")
  expect(claude).toContain("Read it with your file-read tool.")
  expect(claude).not.toContain("From a shell")
})

test("sessionInstructions ends with the inbox block when it is given the session id, and has none without it", () => {
  const base = { agent: "codex" as const, sessionName: "inbox-t", workdir: tmpdir() }
  const withId = sessionInstructions({ ...base, sessionId: "7c1d2e9a-0000" })
  expect(withId).toContain("Your session id: 7c1d2e9a-0000.")
  expect(withId.trimEnd().endsWith(inboxInstructions("7c1d2e9a-0000", undefined, "codex").trimEnd())).toBe(true)
  expect(sessionInstructions(base)).not.toContain("## Inbox")
  // The agent and, for Codex, the binaries its rules name reach the line.
  const claude = sessionInstructions({ ...base, agent: "claude", sessionId: "7c1d2e9a-0000" })
  expect(claude).toContain(`From a shell, read it with exactly \`cat ${SESSION_INBOX_DIR}/7c1d2e9a-0000.jsonl\``)
  const codex = sessionInstructions({ ...base, sessionId: "7c1d2e9a-0000", shellReads: HOST })
  expect(codex).toContain(`From a shell, read it with exactly \`/usr/bin/cat ${SESSION_INBOX_DIR}/7c1d2e9a-0000.jsonl\``)
  expect(sessionInstructions({ ...base, agent: "cursor", sessionId: "7c1d2e9a-0000", shellReads: HOST })).toContain("Read it with your file-read tool.")
})
