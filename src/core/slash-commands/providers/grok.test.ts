import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { GrokCommandProvider, mapGrokCommands, scanGrokSkillsFromDisk } from "./grok"
import type { GrokAcpCommand } from "../types"

// Shapes captured live from `grok agent stdio` 0.2.101 (2026-08-08):
// built-ins carry no _meta; skills carry _meta.scope + _meta.path → SKILL.md.
const LIVE_PUSH: GrokAcpCommand[] = [
  { name: "compact", description: "Compress conversation history to save context window" },
  { name: "always-approve", description: "Toggle always-approve mode (skip all permission prompts)" },
  { name: "context", description: "Show context window usage and session stats" },
  { name: "check-work", description: "Verify changes with a subagent", _meta: { scope: "user", path: "/home/x/.grok/skills/check-work/SKILL.md" } },
  { name: "probe-skill", description: "A probe skill.", _meta: { scope: "user", path: "/plugins/sp/skills/probe-skill/SKILL.md" } },
  { name: "build-with-ai", _meta: { scope: "bundled", path: "/opt/grok/bundled/build-with-ai/SKILL.md" } },
]

test("mapGrokCommands keeps skill-backed entries and drops TUI built-ins", () => {
  const cmds = mapGrokCommands(LIVE_PUSH)
  expect(cmds.map((c) => c.name)).toEqual(["check-work", "probe-skill", "build-with-ai"])
  const probe = cmds.find((c) => c.name === "probe-skill")!
  expect(probe.family).toBe("agent")
  expect(probe.sigil).toBe("/")
  expect(probe.insertText).toBe("/probe-skill ")
  expect(probe.description).toBe("A probe skill.")
})

test("scanGrokSkillsFromDisk reads frontmatter names and dedupes across dirs", () => {
  const root = mkdtempSync(join(tmpdir(), "grok-skills-scan-"))
  try {
    const a = join(root, "a")
    const b = join(root, "b")
    mkdirSync(join(a, "soul"), { recursive: true })
    writeFileSync(join(a, "soul", "SKILL.md"), "---\nname: soul\ndescription: d\n---\nbody\n")
    mkdirSync(join(a, "no-skill-md"), { recursive: true })
    mkdirSync(join(b, "dirname-fallback"), { recursive: true })
    writeFileSync(join(b, "dirname-fallback", "SKILL.md"), "no frontmatter\n")
    mkdirSync(join(b, "soul-dupe"), { recursive: true })
    writeFileSync(join(b, "soul-dupe", "SKILL.md"), "---\nname: soul\n---\n")
    const cmds = scanGrokSkillsFromDisk([a, b, join(root, "missing")])
    expect(cmds.map((c) => c.name).sort()).toEqual(["dirname-fallback", "soul"])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("provider prefers the live ACP list and falls back to the disk scan", async () => {
  const root = mkdtempSync(join(tmpdir(), "grok-skills-prov-"))
  try {
    mkdirSync(join(root, "disk-skill"), { recursive: true })
    writeFileSync(join(root, "disk-skill", "SKILL.md"), "---\nname: disk-skill\n---\n")
    const p = new GrokCommandProvider()
    const base = { sessionName: "s", workdir: "/w", pluginSpawnArgs: [] as string[], grokSkillsDirs: [root] }
    const live = await p.list({ ...base, grokCommands: LIVE_PUSH })
    expect(live.map((c) => c.name)).toEqual(["check-work", "probe-skill", "build-with-ai"])
    const fallback = await p.list(base)
    expect(fallback.map((c) => c.name)).toEqual(["disk-skill"])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
