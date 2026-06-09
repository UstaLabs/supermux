import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, existsSync, readFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { ensureMuxCoreSkills } from "./mux-core"

test("ensureMuxCoreSkills writes manifests and the mux:soul skill", () => {
  const root = mkdtempSync(join(tmpdir(), "mux-core-sync-"))
  try {
    const pluginDir = join(root, "mux-core")
    const changed = ensureMuxCoreSkills({ pluginDir })

    expect(changed).toBe(true)
    expect(existsSync(join(pluginDir, ".claude-plugin", "plugin.json"))).toBe(true)
    expect(existsSync(join(pluginDir, ".codex-plugin", "plugin.json"))).toBe(true)
    expect(existsSync(join(pluginDir, ".cursor-plugin", "plugin.json"))).toBe(true)
    expect(existsSync(join(pluginDir, ".opencode", "plugins", "mux.js"))).toBe(true)

    const muxJs = readFileSync(join(pluginDir, ".opencode", "plugins", "mux.js"), "utf8")
    expect(muxJs).toContain("skills.paths")
    expect(muxJs).not.toContain("bootstrap")

    const skill = readFileSync(join(pluginDir, "skills", "soul", "SKILL.md"), "utf8")
    expect(skill).toContain("name: soul")
    expect(skill).toContain("PA-only")
    expect(skill).toContain("~/.mux/soul.md")
    expect(skill).toContain("soul-setup.json")
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("ensureMuxCoreSkills writes the mux:new-personal-agent skill", () => {
  const root = mkdtempSync(join(tmpdir(), "mux-core-new-pa-"))
  try {
    const pluginDir = join(root, "mux-core")
    ensureMuxCoreSkills({ pluginDir })

    const skill = readFileSync(join(pluginDir, "skills", "new-personal-agent", "SKILL.md"), "utf8")
    expect(skill).toContain("name: new-personal-agent")
    expect(skill).toContain("PA-only")
    expect(skill).toContain("spawn_session")
    expect(skill).toContain("~/.mux/workspace/<name>")
    expect(skill).toContain("focus.md")
    expect(skill).toContain("soul.md")
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("ensureMuxCoreSkills preserves unrelated mux-core skills", () => {
  const root = mkdtempSync(join(tmpdir(), "mux-core-preserve-"))
  try {
    const pluginDir = join(root, "mux-core")
    mkdirSync(join(pluginDir, "skills", "browser"), { recursive: true })
    writeFileSync(join(pluginDir, "skills", "browser", "SKILL.md"), "browser skill")

    ensureMuxCoreSkills({ pluginDir })

    expect(readFileSync(join(pluginDir, "skills", "browser", "SKILL.md"), "utf8")).toBe("browser skill")
    expect(existsSync(join(pluginDir, "skills", "soul", "SKILL.md"))).toBe(true)
    expect(existsSync(join(pluginDir, "skills", "new-personal-agent", "SKILL.md"))).toBe(true)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("ensureMuxCoreSkills is idempotent when files already match", () => {
  const root = mkdtempSync(join(tmpdir(), "mux-core-idem-"))
  try {
    const pluginDir = join(root, "mux-core")
    expect(ensureMuxCoreSkills({ pluginDir })).toBe(true)
    expect(ensureMuxCoreSkills({ pluginDir })).toBe(false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
