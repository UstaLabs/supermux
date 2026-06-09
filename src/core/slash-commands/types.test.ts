import { test, expect } from "bun:test"
import { agentCommand, type SlashCommand } from "./types"

test("agentCommand builds insertText from sigil + name", () => {
  const c: SlashCommand = agentCommand({ name: "code-review", sigil: "/", description: "review the diff" })
  expect(c.family).toBe("agent")
  expect(c.id).toBe("agent:code-review")
  expect(c.insertText).toBe("/code-review ")
})

test("agentCommand with $ sigil (codex)", () => {
  const c = agentCommand({ name: "browser", sigil: "$" })
  expect(c.insertText).toBe("$browser ")
  expect(c.id).toBe("agent:browser")
})
