import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, existsSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { parseAddSource, applyEnable, applyRemove, pluginSummaries, addPlugin, removePlugin, setPluginEnabled } from "./lifecycle"
import { loadPluginsRegistry } from "./registry"
import { parsePluginsRegistry } from "./registry"
import type { PluginsRegistry } from "./types"

const PLUGINS_DIR = "/canonical/plugins"

function reg(plugins: unknown[]): PluginsRegistry {
  return parsePluginsRegistry(JSON.stringify({ version: 1, plugins }), PLUGINS_DIR)
}

test("parseAddSource: github: shorthand → git source named after the repo", () => {
  const { name, source } = parseAddSource("github:obra/superpowers")
  expect(name).toBe("superpowers")
  expect(source).toEqual({ type: "git", url: "https://github.com/obra/superpowers" })
})

test("parseAddSource: https git URL → git source, .git stripped from the name", () => {
  const { name, source } = parseAddSource("https://github.com/obra/superpowers.git")
  expect(name).toBe("superpowers")
  expect(source).toEqual({ type: "git", url: "https://github.com/obra/superpowers.git" })
})

test("parseAddSource: a filesystem path → local source named after the basename", () => {
  const { name, source } = parseAddSource("/home/u/dev/my-plugin")
  expect(name).toBe("my-plugin")
  expect(source).toEqual({ type: "local", path: "/home/u/dev/my-plugin" })
})

test("parseAddSource: --name and --ref overrides are honored", () => {
  const { name, source } = parseAddSource("github:obra/superpowers", { name: "sp", ref: "v5.1.0" })
  expect(name).toBe("sp")
  expect(source).toEqual({ type: "git", url: "https://github.com/obra/superpowers", ref: "v5.1.0" })
})

test("applyEnable toggles enabled and replaces scopes when provided", () => {
  const r = reg([{ name: "sp", source: { type: "git", url: "u" }, enabled: true, scopes: ["claude", "codex", "cursor"] }])
  const off = applyEnable(r, "sp", { enabled: false })
  expect(off.plugins[0]!.enabled).toBe(false)
  const scoped = applyEnable(r, "sp", { scopes: ["claude"] })
  expect(scoped.plugins[0]!.scopes).toEqual(["claude"])
})

test("applyEnable throws for an unknown plugin", () => {
  const r = reg([{ name: "sp", source: { type: "git", url: "u" }, scopes: ["claude"] }])
  expect(() => applyEnable(r, "nope", { enabled: false })).toThrow(/nope/)
})

test("applyRemove drops the named plugin", () => {
  const r = reg([
    { name: "sp", source: { type: "git", url: "u" }, scopes: ["claude"] },
    { name: "core", source: { type: "local", path: "p" }, scopes: ["claude"] },
  ])
  const after = applyRemove(r, "sp")
  expect(after.plugins.map((p) => p.name)).toEqual(["core"])
})

test("addPlugin copies a local source into the tree and appends to the registry", async () => {
  const root = mkdtempSync(join(tmpdir(), "lifecycle-add-"))
  try {
    const pluginsDir = join(root, "plugins")
    mkdirSync(pluginsDir, { recursive: true })
    const file = join(root, "plugins.json")
    writeFileSync(file, JSON.stringify({ version: 1, plugins: [] }))

    // A local source tree with a claude manifest. The registry name is derived
    // from the source dir's basename ("my-plugin"), kept separate from pluginsDir.
    const src = join(root, "external", "my-plugin")
    mkdirSync(join(src, ".claude-plugin"), { recursive: true })
    writeFileSync(join(src, ".claude-plugin", "plugin.json"), JSON.stringify({ name: "my-plugin" }))

    let prepared = 0
    const summary = await addPlugin(src, {}, { file, pluginsDir, prepareCodex: async () => { prepared++ } })

    expect(summary.name).toBe("my-plugin")
    expect(summary.compatibility.claude).toBe(true)
    expect(prepared).toBe(1)
    const reg = loadPluginsRegistry({ file, pluginsDir })
    expect(reg.plugins.map((p) => p.name)).toEqual(["my-plugin"])
    expect(reg.plugins[0]!.source).toEqual({ type: "local", path: src })
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("removePlugin drops the entry, runs codex uninstall, and can purge the tree", async () => {
  const root = mkdtempSync(join(tmpdir(), "lifecycle-rm-"))
  try {
    const pluginsDir = join(root, "plugins")
    const dest = join(pluginsDir, "sp")
    mkdirSync(dest, { recursive: true })
    const file = join(root, "plugins.json")
    writeFileSync(file, JSON.stringify({ version: 1, plugins: [{ name: "sp", source: { type: "git", url: "u" }, enabled: true, scopes: ["claude"] }] }))

    const execCalls: string[][] = []
    await removePlugin("sp", { purge: true }, {
      file, pluginsDir,
      exec: (cmd, args) => execCalls.push([cmd, ...args]),
      prepareCodex: async () => {},
    })

    expect(loadPluginsRegistry({ file, pluginsDir }).plugins).toEqual([])
    expect(existsSync(dest)).toBe(false)
    expect(execCalls).toEqual([["codex", "plugin", "remove", "sp@mux"]])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("setPluginEnabled persists the toggle", async () => {
  const root = mkdtempSync(join(tmpdir(), "lifecycle-en-"))
  try {
    const file = join(root, "plugins.json")
    writeFileSync(file, JSON.stringify({ version: 1, plugins: [{ name: "sp", source: { type: "git", url: "u" }, enabled: true, scopes: ["claude", "codex"] }] }))
    await setPluginEnabled("sp", { enabled: false }, { file, pluginsDir: root, prepareCodex: async () => {} })
    expect(loadPluginsRegistry({ file, pluginsDir: root }).plugins[0]!.enabled).toBe(false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("pluginSummaries reports per-CLI compatibility from on-disk manifests", () => {
  const root = mkdtempSync(join(tmpdir(), "lifecycle-"))
  try {
    // A plugin dir with only a claude manifest.
    const dir = join(root, "sp")
    mkdirSync(join(dir, ".claude-plugin"), { recursive: true })
    writeFileSync(join(dir, ".claude-plugin", "plugin.json"), JSON.stringify({ name: "sp" }))

    const r = reg([{ name: "sp", version: "1.0.0", source: { type: "local", path: dir }, enabled: true, scopes: ["claude", "codex"] }])
    const [row] = pluginSummaries(r)
    expect(row!.name).toBe("sp")
    expect(row!.enabled).toBe(true)
    expect(row!.compatibility).toEqual({ claude: true, cursor: false, codex: false })
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
