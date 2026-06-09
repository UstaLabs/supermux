import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { mapCursorCommands, CursorCommandProvider, scanCursorCommandsFromDisk, pluginDirsFromArgs } from "./cursor"
import { agentCommand } from "../types"

test("mapCursorCommands maps {name,description} with / sigil", () => {
  const cmds = mapCursorCommands([{ name: "write-tests", description: "scaffold tests" }, { name: "plan" }])
  expect(cmds.map((c) => c.name)).toEqual(["write-tests", "plan"])
  expect(cmds[0]).toMatchObject({ family: "agent", sigil: "/", insertText: "/write-tests " })
})

test("pluginDirsFromArgs extracts --plugin-dir values", () => {
  expect(pluginDirsFromArgs(["--plugin-dir", "/a", "--plugin-dir", "/b", "--other", "x"])).toEqual(["/a", "/b"])
})

test("CursorCommandProvider prefers the last pushed list over the disk scan", async () => {
  const provider = new CursorCommandProvider({ scanDisk: () => [agentCommand({ name: "from-disk", sigil: "/" })] })
  provider.update("s1", [{ name: "plan" }])
  expect((await provider.list({ sessionName: "s1", workdir: "/tmp", pluginSpawnArgs: [] })).map((c) => c.name)).toEqual(["plan"])
})

test("CursorCommandProvider falls back to disk scan (with plugin dirs) when nothing pushed", async () => {
  const provider = new CursorCommandProvider({
    scanDisk: (wd, dirs) => [agentCommand({ name: `disk:${wd}:${dirs.join(",")}`, sigil: "/" })],
  })
  const cmds = await provider.list({ sessionName: "s2", workdir: "/proj", pluginSpawnArgs: ["--plugin-dir", "/p1"] })
  expect(cmds.map((c) => c.name)).toEqual(["disk:/proj:/p1"])
})

test("scanCursorCommandsFromDisk reads plugin-dir skills + commands", () => {
  const root = mkdtempSync(join(tmpdir(), "cursor-scan-"))
  const plugin = join(root, "mux-core")
  mkdirSync(join(plugin, "skills", "browser"), { recursive: true })
  writeFileSync(join(plugin, "skills", "browser", "SKILL.md"), "---\nname: browser\n---\n")
  mkdirSync(join(plugin, "skills", "soul"), { recursive: true })
  writeFileSync(join(plugin, "skills", "soul", "SKILL.md"), "---\nname: soul\n---\n")
  mkdirSync(join(plugin, "commands"), { recursive: true })
  writeFileSync(join(plugin, "commands", "deploy.md"), "do it")
  const cmds = scanCursorCommandsFromDisk(join(root, "empty-workdir"), [plugin])
  expect(cmds.map((c) => c.name).sort()).toEqual(["browser", "deploy", "soul"])
  expect(cmds.every((c) => c.sigil === "/")).toBe(true)
})
