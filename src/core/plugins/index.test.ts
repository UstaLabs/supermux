import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { ensureGrokPluginScopes, ensureOpenCodePluginScopes, grokSkillsDirs, loadPluginsRegistry, opencodePluginRoots, pluginSpawnArgsForKind, sessionPlugins } from "./index"
import { AgentKind } from "../../shared/agents"

function setup(): { root: string; file: string } {
  const root = mkdtempSync(join(tmpdir(), "plugins-index-"))
  return { root, file: join(root, "plugins.json") }
}

function writeSkillPlugin(root: string, name: string): string {
  const dir = join(root, name)
  mkdirSync(join(dir, "skills", "demo"), { recursive: true })
  writeFileSync(join(dir, "skills", "demo", "SKILL.md"), "---\nname: demo\n---\n")
  return dir
}

test("ensureGrokPluginScopes adds grok to enabled skill-shipping plugins, once", () => {
  const { root, file } = setup()
  try {
    const dir = writeSkillPlugin(root, "sp")
    const bare = join(root, "bare")
    mkdirSync(bare, { recursive: true })
    writeFileSync(file, JSON.stringify({
      version: 1,
      plugins: [
        { name: "sp", source: { type: "local", path: dir }, enabled: true, scopes: ["claude"] },
        { name: "bare", source: { type: "local", path: bare }, enabled: true, scopes: ["claude"] },
        { name: "off", source: { type: "local", path: dir }, enabled: false, scopes: ["claude"] },
      ],
    }))
    expect(ensureGrokPluginScopes({ file, pluginsDir: root })).toBe(true)
    const reg = loadPluginsRegistry({ file, pluginsDir: root })
    expect(reg.plugins.find((p) => p.name === "sp")!.scopes).toEqual(["claude", "grok"])
    expect(reg.plugins.find((p) => p.name === "bare")!.scopes).toEqual(["claude"])
    expect(reg.plugins.find((p) => p.name === "off")!.scopes).toEqual(["claude"])
    // Idempotent: a second run changes nothing.
    expect(ensureGrokPluginScopes({ file, pluginsDir: root })).toBe(false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("ensureOpenCodePluginScopes still adds opencode via the shared engine", () => {
  const { root, file } = setup()
  try {
    const dir = writeSkillPlugin(root, "sp")
    writeFileSync(file, JSON.stringify({
      version: 1,
      plugins: [{ name: "sp", source: { type: "local", path: dir }, enabled: true, scopes: ["claude"] }],
    }))
    expect(ensureOpenCodePluginScopes({ file, pluginsDir: root })).toBe(true)
    expect(loadPluginsRegistry({ file, pluginsDir: root }).plugins[0]!.scopes).toEqual(["claude", "opencode"])
    expect(ensureOpenCodePluginScopes({ file, pluginsDir: root })).toBe(false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("grokSkillsDirs resolves skills dirs for grok-scoped plugins from the registry file", () => {
  const { root, file } = setup()
  try {
    const dir = writeSkillPlugin(root, "sp")
    writeFileSync(file, JSON.stringify({
      version: 1,
      plugins: [
        { name: "sp", source: { type: "local", path: dir }, enabled: true, scopes: ["grok"] },
        { name: "other", source: { type: "local", path: writeSkillPlugin(root, "other") }, enabled: true, scopes: ["claude"] },
      ],
    }))
    expect(grokSkillsDirs("s", { file, pluginsDir: root })).toEqual([join(dir, "skills")])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("sessionPlugins never throws: a malformed plugins.json yields no paths and reports the error", () => {
  const { root, file } = setup()
  try {
    writeFileSync(file, "{not json")
    let reported: string | undefined
    expect(sessionPlugins("grok", "s", { file, pluginsDir: root, onError: (e) => { reported = e } })).toEqual([])
    expect(reported).toMatch(/invalid JSON/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

// The selection rule the per-CLI adapters applied before C3 (enabled, scoped, not switched off for
// the session, the CLI's manifest / loadable parts); the core maps the selected roots per agent.
test("sessionPlugins: enabled + scoped + not overridden off + compatible, in registry order", () => {
  const { root, file } = setup()
  try {
    const manifest = (dir: string, m: string) => { mkdirSync(join(dir, m), { recursive: true }); writeFileSync(join(dir, m, "plugin.json"), "{}") }
    const all = writeSkillPlugin(root, "all")
    for (const m of [".claude-plugin", ".codex-plugin", ".cursor-plugin"]) manifest(all, m)
    mkdirSync(join(all, ".opencode", "plugins"), { recursive: true }); writeFileSync(join(all, ".opencode", "plugins", "x.js"), "")
    const claudeOnly = join(root, "claude-only"); manifest(claudeOnly, ".claude-plugin")
    const off = writeSkillPlugin(root, "off"); manifest(off, ".claude-plugin")
    const muted = writeSkillPlugin(root, "muted"); manifest(muted, ".claude-plugin")
    const unscoped = writeSkillPlugin(root, "unscoped"); manifest(unscoped, ".claude-plugin")
    const every = ["claude", "codex", "cursor", "opencode", "grok"]
    writeFileSync(file, JSON.stringify({ version: 1, plugins: [
      { name: "all", source: { type: "local", path: all }, enabled: true, scopes: every },
      { name: "claude-only", source: { type: "local", path: claudeOnly }, enabled: true, scopes: every },
      { name: "off", source: { type: "local", path: off }, enabled: false, scopes: every },
      { name: "muted", source: { type: "local", path: muted }, enabled: true, scopes: every, perSessionOverrides: { s: { enabled: false } } },
      { name: "unscoped", source: { type: "local", path: unscoped }, enabled: true, scopes: ["codex"] },
    ] }))
    const o = { file, pluginsDir: root }
    expect(sessionPlugins("claude", "s", o)).toEqual([all, claudeOnly])
    expect(sessionPlugins("claude", "other", o)).toEqual([all, claudeOnly, muted])
    expect(sessionPlugins("codex", "s", o)).toEqual([all])
    expect(sessionPlugins("cursor", "s", o)).toEqual([all])
    expect(sessionPlugins("opencode", "s", o)).toEqual([all])
    expect(sessionPlugins("grok", "s", o)).toEqual([all])
    expect(pluginSpawnArgsForKind(AgentKind.Claude, { ...o, sessionName: "s" })).toEqual(["--plugin-dir", all, "--plugin-dir", claudeOnly])
    expect(pluginSpawnArgsForKind(AgentKind.Codex, { ...o, sessionName: "s" })).toEqual([])
    expect(opencodePluginRoots("s", o)).toEqual([all])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
