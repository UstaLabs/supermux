import { test, expect } from "bun:test"
import { CursorCommandProvider, mapCursorCommands } from "./cursor"

// Shape of cursor-agent 2026.09.18's ACP available_commands_update (names and descriptions only).
const PUSHED = [
  { name: "simplify", description: "Simplify code" },
  { name: "cursor-proj-cmd", description: "Run the project command" },
  { name: "cursor-proj-skill", description: "A project skill" },
]

test("mapCursorCommands keeps every command Cursor reports; it does not mark the folder's own", () => {
  const list = mapCursorCommands(PUSHED)
  expect(list.commands.map((c) => c.insertText)).toEqual(["/simplify ", "/cursor-proj-cmd ", "/cursor-proj-skill "])
  expect(list.projectIds).toBeUndefined()
})

test("CursorCommandProvider reads the session's pushed list; nothing before the push", async () => {
  const p = new CursorCommandProvider()
  expect((await p.list({ sessionName: "s", workdir: "/w", agentContext: { commands: PUSHED } }))?.commands).toHaveLength(3)
  expect(await p.list({ sessionName: "s", workdir: "/w", agentContext: { commands: undefined } })).toBeUndefined()
})
