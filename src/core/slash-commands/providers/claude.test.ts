import { test, expect } from "bun:test"
import { ClaudeCommandProvider, mapClaudeCommands } from "./claude"

// Shapes from Claude Code 2.1.308 (initialize reply / commands_changed push), as Core normalizes them.
const REPORTED = [
  { name: "code-review", description: "Review a PR", argumentHint: "[pr]", builtin: true },
  { name: "superpowers:brainstorming", description: "(superpowers) Explore intent" },
  { name: "proj-cmd", description: "A project command (project)", argumentHint: "thing" },
  { name: "mini:greet-prompt (MCP)", description: "An MCP prompt" },
  { name: "__remote-workflow", description: "internal" },
  { name: "code-review", description: "a duplicate" },
]

test("mapClaudeCommands: each reported command as a / entry; MCP suffix stripped; internal and duplicate names dropped", () => {
  const { commands } = mapClaudeCommands(REPORTED)
  expect(commands.map((c) => c.insertText)).toEqual(["/code-review ", "/superpowers:brainstorming ", "/proj-cmd ", "/mini:greet-prompt "])
  expect(commands[0]).toMatchObject({ id: "agent:code-review", family: "agent", sigil: "/", description: "Review a PR" })
})

test("mapClaudeCommands marks the folder's own commands (description ending in \"(project)\")", () => {
  expect(mapClaudeCommands(REPORTED).projectIds).toEqual(["agent:proj-cmd"])
})

test("ClaudeCommandProvider reads the list the session's Claude reported; nothing before it reports", async () => {
  const p = new ClaudeCommandProvider()
  const reported = REPORTED.slice(0, 2)
  expect(await p.list({ sessionName: "s", workdir: "/w", agentContext: { commands: reported } })).toEqual(mapClaudeCommands(reported))
  expect(await p.list({ sessionName: "s", workdir: "/w", agentContext: { commands: undefined } })).toBeUndefined()
  expect(await p.list({ sessionName: "s", workdir: "/w" })).toBeUndefined()
})
