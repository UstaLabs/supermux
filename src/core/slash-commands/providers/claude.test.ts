import { test, expect } from "bun:test"
import { EventEmitter } from "events"
import { parseClaudeInitLine, claudeNamesToCommands, ClaudeCommandProvider } from "./claude"

test("parseClaudeInitLine returns slash_commands only for the init frame", () => {
  expect(parseClaudeInitLine('{"type":"system","subtype":"hook_started"}')).toBeNull()
  expect(parseClaudeInitLine("not json")).toBeNull()
  const got = parseClaudeInitLine('{"type":"system","subtype":"init","slash_commands":["code-review","superpowers:brainstorming"]}')
  expect(got).toEqual(["code-review", "superpowers:brainstorming"])
})

test("claudeNamesToCommands maps to / agent commands", () => {
  const cmds = claudeNamesToCommands(["code-review", "superpowers:brainstorming"])
  expect(cmds[0]).toMatchObject({ family: "agent", name: "code-review", sigil: "/", insertText: "/code-review " })
  expect(cmds[1]!.name).toBe("superpowers:brainstorming")
})

function fakeChild(lines: string[]) {
  const child: any = new EventEmitter()
  child.stdout = new EventEmitter()
  child.stdin = { end() {} }
  child.killed = false
  child.kill = () => { child.killed = true }
  queueMicrotask(() => { for (const l of lines) child.stdout.emit("data", Buffer.from(l + "\n")) })
  return child
}

test("ClaudeCommandProvider returns commands from the init frame and kills the child", async () => {
  let spawned: any
  const provider = new ClaudeCommandProvider({
    spawn: () => (spawned = fakeChild([
      '{"type":"system","subtype":"hook_started"}',
      '{"type":"system","subtype":"init","slash_commands":["verify","loop"]}',
      '{"type":"assistant"}', // must never be read — we kill at init
    ])),
  })
  const cmds = await provider.list({ sessionName: "s", workdir: "/tmp", pluginSpawnArgs: [] })
  expect(cmds.map((c) => c.name)).toEqual(["verify", "loop"])
  expect(spawned.killed).toBe(true)
})

test("ClaudeCommandProvider returns [] when the child errors", async () => {
  const provider = new ClaudeCommandProvider({
    spawn: () => { const c = fakeChild([]); queueMicrotask(() => c.emit("error", new Error("ENOENT"))); return c },
  })
  expect(await provider.list({ sessionName: "s", workdir: "/tmp", pluginSpawnArgs: [] })).toEqual([])
})
