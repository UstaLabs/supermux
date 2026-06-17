import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, existsSync, readFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { ensureMuxCoreSkills, ensureMuxCoreRegistered } from "./mux-core"
import { loadPluginsRegistry, savePluginsRegistry } from "./registry"

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

// ── ensureMuxCoreRegistered: the fix — mux-core must be ENABLED in plugins.json,
//    else a fresh install spawns sessions with zero plugins (no /mux:soul). ─────

test("ensureMuxCoreRegistered adds mux-core (enabled, all scopes) to a fresh registry", () => {
  const root = mkdtempSync(join(tmpdir(), "mux-core-reg-"))
  try {
    const file = join(root, "plugins.json")
    const pluginsDir = join(root, "plugins")
    expect(existsSync(file)).toBe(false) // truly fresh: no registry yet

    const changed = ensureMuxCoreRegistered({ file, pluginsDir })

    expect(changed).toBe(true)
    expect(existsSync(file)).toBe(true)
    const reg = loadPluginsRegistry({ file, pluginsDir })
    const muxCore = reg.plugins.find((p) => p.name === "mux-core")
    expect(muxCore).toBeDefined()
    expect(muxCore!.enabled).toBe(true)
    expect(muxCore!.scopes).toEqual(["claude", "codex", "cursor", "opencode"])
    expect(muxCore!.source.type).toBe("local")
    expect(muxCore!.dir).toBe(join(pluginsDir, "mux-core"))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("ensureMuxCoreRegistered is idempotent (no duplicate, returns false second time)", () => {
  const root = mkdtempSync(join(tmpdir(), "mux-core-reg-idem-"))
  try {
    const file = join(root, "plugins.json")
    const pluginsDir = join(root, "plugins")
    expect(ensureMuxCoreRegistered({ file, pluginsDir })).toBe(true)
    expect(ensureMuxCoreRegistered({ file, pluginsDir })).toBe(false)
    const reg = loadPluginsRegistry({ file, pluginsDir })
    expect(reg.plugins.filter((p) => p.name === "mux-core")).toHaveLength(1)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("ensureMuxCoreRegistered respects an explicitly-disabled mux-core (never re-enables)", () => {
  const root = mkdtempSync(join(tmpdir(), "mux-core-reg-disabled-"))
  try {
    const file = join(root, "plugins.json")
    const pluginsDir = join(root, "plugins")
    const dir = join(pluginsDir, "mux-core")
    savePluginsRegistry(
      { version: 1, plugins: [{ name: "mux-core", source: { type: "local", path: dir }, enabled: false, scopes: ["claude"], dir }] },
      { file, pluginsDir },
    )
    expect(ensureMuxCoreRegistered({ file, pluginsDir })).toBe(false) // present → untouched
    const reg = loadPluginsRegistry({ file, pluginsDir })
    expect(reg.plugins.find((p) => p.name === "mux-core")!.enabled).toBe(false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("ensureMuxCoreRegistered preserves other plugins already in the registry", () => {
  const root = mkdtempSync(join(tmpdir(), "mux-core-reg-others-"))
  try {
    const file = join(root, "plugins.json")
    const pluginsDir = join(root, "plugins")
    savePluginsRegistry(
      { version: 1, plugins: [{ name: "superpowers", source: { type: "git", url: "https://example/x" }, enabled: true, scopes: ["claude"], dir: join(pluginsDir, "superpowers") }] },
      { file, pluginsDir },
    )
    expect(ensureMuxCoreRegistered({ file, pluginsDir })).toBe(true)
    const reg = loadPluginsRegistry({ file, pluginsDir })
    expect(reg.plugins.map((p) => p.name).sort()).toEqual(["mux-core", "superpowers"])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
