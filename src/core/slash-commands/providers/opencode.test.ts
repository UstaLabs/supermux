import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { mapOpenCodeSkills, OpenCodeCommandProvider, scanOpenCodeSkillsFromDisk } from "./opencode"
import { agentCommand } from "../types"

test("mapOpenCodeSkills keeps source=skill and maps / insertText", () => {
  const cmds = mapOpenCodeSkills([
    { name: "brainstorming", description: "explore ideas", source: "skill" },
    { name: "init", source: "builtin" },
    { name: "review", source: "command" },
  ])
  expect(cmds.map((c) => c.name)).toEqual(["brainstorming"])
  expect(cmds[0]).toMatchObject({ family: "agent", sigil: "/", insertText: "/brainstorming " })
})

test("mapOpenCodeSkills without source excludes init/review fallback", () => {
  const cmds = mapOpenCodeSkills([
    { name: "browser", description: "web automation" },
    { name: "init" },
    { name: "review" },
  ])
  expect(cmds.map((c) => c.name)).toEqual(["browser"])
})

test("OpenCodeCommandProvider prefers live client over disk scan", async () => {
  const provider = new OpenCodeCommandProvider()
  const cmds = await provider.list({
    sessionName: "s1",
    workdir: "/proj",
    pluginSpawnArgs: [],
    agentContext: {
      client: {
        listCommands: async () => [{ name: "live-skill", source: "skill" }],
      },
      pluginDirs: ["/plugins/mux-core"],
    },
  })
  expect(cmds.map((c) => c.name)).toEqual(["live-skill"])
})

test("OpenCodeCommandProvider falls back to disk scan when no client", async () => {
  const provider = new OpenCodeCommandProvider()
  const root = mkdtempSync(join(tmpdir(), "opencode-scan-"))
  const plugin = join(root, "superpowers")
  mkdirSync(join(plugin, "skills", "brainstorming"), { recursive: true })
  writeFileSync(join(plugin, "skills", "brainstorming", "SKILL.md"), "---\nname: brainstorming\n---\n")
  const cmds = await provider.list({
    sessionName: "preview:opencode",
    workdir: join(root, "workdir"),
    pluginSpawnArgs: [],
    agentContext: { pluginDirs: [plugin] },
  })
  expect(cmds.map((c) => c.name)).toEqual(["brainstorming"])
  expect(cmds.every((c) => c.sigil === "/")).toBe(true)
})

test("scanOpenCodeSkillsFromDisk reads skill frontmatter name", () => {
  const root = mkdtempSync(join(tmpdir(), "opencode-scan2-"))
  const plugin = join(root, "mux-core")
  mkdirSync(join(plugin, "skills", "browser-dir"), { recursive: true })
  writeFileSync(join(plugin, "skills", "browser-dir", "SKILL.md"), "---\nname: mux:browser\n---\n")
  const cmds = scanOpenCodeSkillsFromDisk([plugin])
  expect(cmds).toEqual([agentCommand({ name: "mux:browser", sigil: "/" })])
})
