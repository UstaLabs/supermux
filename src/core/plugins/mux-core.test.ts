import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, existsSync, readFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { ensureMuxCoreSkills, ensureMuxCoreRegistered } from "./mux-core"
import { loadPluginsRegistry, savePluginsRegistry } from "./registry"
import { listOpenCodePluginJs } from "./index"

test("ensureMuxCoreSkills writes manifests and removes the retired soul and new-personal-agent skills", () => {
  const root = mkdtempSync(join(tmpdir(), "mux-core-sync-"))
  try {
    const pluginDir = join(root, "mux-core")
    mkdirSync(join(pluginDir, "skills", "soul"), { recursive: true })
    writeFileSync(join(pluginDir, "skills", "soul", "SKILL.md"), "old")
    mkdirSync(join(pluginDir, "skills", "new-personal-agent"), { recursive: true })
    writeFileSync(join(pluginDir, "skills", "new-personal-agent", "SKILL.md"), "old")
    expect(ensureMuxCoreSkills({ pluginDir })).toBe(true)
    expect(existsSync(join(pluginDir, ".claude-plugin", "plugin.json"))).toBe(true)
    expect(existsSync(join(pluginDir, ".codex-plugin", "plugin.json"))).toBe(true)
    expect(existsSync(join(pluginDir, ".cursor-plugin", "plugin.json"))).toBe(true)
    // No OpenCode JS plugin: the core already maps skills/ to OpenCode's skills.paths (#102).
    expect(existsSync(join(pluginDir, ".opencode"))).toBe(false)
    expect(existsSync(join(pluginDir, "skills"))).toBe(true)
    expect(existsSync(join(pluginDir, "skills", "soul"))).toBe(false)
    expect(existsSync(join(pluginDir, "skills", "new-personal-agent"))).toBe(false)
    expect(ensureMuxCoreSkills({ pluginDir })).toBe(false)
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

// ── #102: any OpenCode `plugin` entry makes OpenCode install @opencode-ai/plugin (62 MB) into the
//    session's own config dir before it answers initialize (44–82 s per new session, measured). mux.js
//    only re-added mux-core/skills to skills.paths, which the core already maps, so it is removed. ────

test("ensureMuxCoreSkills removes the OpenCode JS plugin an older broker wrote", () => {
  const root = mkdtempSync(join(tmpdir(), "mux-core-mux-js-"))
  try {
    const pluginDir = join(root, "mux-core")
    mkdirSync(join(pluginDir, ".opencode", "plugins"), { recursive: true })
    writeFileSync(join(pluginDir, ".opencode", "plugins", "mux.js"), "export const MuxPlugin = async () => ({})\n")
    expect(ensureMuxCoreSkills({ pluginDir })).toBe(true)
    expect(existsSync(join(pluginDir, ".opencode"))).toBe(false)
    expect(listOpenCodePluginJs(pluginDir)).toEqual([])
    expect(ensureMuxCoreSkills({ pluginDir })).toBe(false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("ensureMuxCoreSkills removes only mux.js from .opencode/plugins", () => {
  const root = mkdtempSync(join(tmpdir(), "mux-core-mux-js-other-"))
  try {
    const pluginDir = join(root, "mux-core")
    mkdirSync(join(pluginDir, ".opencode", "plugins"), { recursive: true })
    writeFileSync(join(pluginDir, ".opencode", "plugins", "mux.js"), "old")
    writeFileSync(join(pluginDir, ".opencode", "plugins", "other.js"), "someone else's")
    ensureMuxCoreSkills({ pluginDir })
    expect(existsSync(join(pluginDir, ".opencode", "plugins", "mux.js"))).toBe(false)
    expect(readFileSync(join(pluginDir, ".opencode", "plugins", "other.js"), "utf8")).toBe("someone else's")
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// ── instructions reach agents through the Core session context; an install that still has the
//    old SessionStart hook + reply-conventions skill gets them removed. ────

test("ensureMuxCoreSkills removes the retired hook machinery and reply-conventions skill", () => {
  const root = mkdtempSync(join(tmpdir(), "mux-core-stale-"))
  try {
    const pluginDir = join(root, "mux-core")
    mkdirSync(join(pluginDir, "hooks"), { recursive: true })
    writeFileSync(join(pluginDir, "hooks", "session-start"), "old")
    mkdirSync(join(pluginDir, "skills", "reply-conventions"), { recursive: true })
    writeFileSync(join(pluginDir, "skills", "reply-conventions", "SKILL.md"), "old")
    expect(ensureMuxCoreSkills({ pluginDir })).toBe(true)
    expect(existsSync(join(pluginDir, "hooks"))).toBe(false)
    expect(existsSync(join(pluginDir, "skills", "reply-conventions"))).toBe(false)
    expect(JSON.parse(readFileSync(join(pluginDir, ".cursor-plugin", "plugin.json"), "utf8")).hooks).toBeUndefined()
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// ── ensureMuxCoreRegistered: the fix — mux-core must be ENABLED in plugins.json,
//    else a fresh install spawns sessions with zero plugins (no mux skills). ─────

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
    expect(muxCore!.scopes).toEqual(["claude", "codex", "cursor", "opencode", "grok", "antigravity"])
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
