import { test, expect } from "bun:test"
import { OpenCodeCommandProvider, mapOpenCodeCommands } from "./opencode"

// OpenCode 1.18.34's ACP push. Its ACP prompt handler runs session.command for any name in this list
// (read from the binary), so init, review, custom commands, skills and MCP prompts all work as "/name".
const PUSHED = [
  { name: "init", description: "create/update AGENTS.md" },
  { name: "review", description: "review changes" },
  { name: "oc-proj-cmd", description: "project command" },
  { name: "graphify", description: "a skill" },
  { name: "mini:greet-prompt", description: "an MCP prompt" },
]

test("mapOpenCodeCommands keeps every command OpenCode reports, init and review included", () => {
  const list = mapOpenCodeCommands(PUSHED)
  expect(list.commands.map((c) => c.insertText)).toEqual(["/init ", "/review ", "/oc-proj-cmd ", "/graphify ", "/mini:greet-prompt "])
  expect(list.projectIds).toBeUndefined()
})

test("OpenCodeCommandProvider reads the session's pushed list; nothing before the push", async () => {
  const p = new OpenCodeCommandProvider()
  expect((await p.list({ sessionName: "s", workdir: "/w", agentContext: { commands: PUSHED } }))?.commands).toHaveLength(5)
  expect(await p.list({ sessionName: "s", workdir: "/w" })).toBeUndefined()
})
