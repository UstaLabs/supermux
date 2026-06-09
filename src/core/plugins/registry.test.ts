import { test, expect } from "bun:test"
import { mkdtempSync, writeFileSync, rmSync } from "fs"
import { tmpdir, homedir } from "os"
import { join } from "path"
import { parsePluginsRegistry, loadPluginsRegistry, savePluginsRegistry, serializePluginsRegistry } from "./registry"

const PLUGINS_DIR = "/canonical/plugins"

function tmp(): string {
  return mkdtempSync(join(tmpdir(), "plugins-test-"))
}

test("parses a valid registry and resolves git-source dir to PLUGINS_DIR/<name>", () => {
  const raw = JSON.stringify({
    version: 1,
    plugins: [
      { name: "superpowers", version: "5.1.0", source: { type: "git", url: "https://x/y", ref: "v5.1.0" }, enabled: true, scopes: ["claude", "codex", "cursor"] },
    ],
  })
  const reg = parsePluginsRegistry(raw, PLUGINS_DIR)
  expect(reg.version).toBe(1)
  expect(reg.plugins).toHaveLength(1)
  const p = reg.plugins[0]!
  expect(p.name).toBe("superpowers")
  expect(p.dir).toBe(join(PLUGINS_DIR, "superpowers"))
  expect(p.scopes).toEqual(["claude", "codex", "cursor"])
})

test("resolves a local source path, expanding a leading ~", () => {
  const raw = JSON.stringify({
    version: 1,
    plugins: [
      { name: "mux-core", source: { type: "local", path: "~/.mux/plugins/mux-core" }, enabled: true, scopes: ["claude"] },
    ],
  })
  const p = parsePluginsRegistry(raw, PLUGINS_DIR).plugins[0]!
  expect(p.dir).toBe(join(homedir(), ".mux/plugins/mux-core"))
})

test("local source without a path falls back to canonical PLUGINS_DIR/<name>", () => {
  const raw = JSON.stringify({
    version: 1,
    plugins: [{ name: "foo", source: { type: "local" }, enabled: true, scopes: ["claude"] }],
  })
  expect(parsePluginsRegistry(raw, PLUGINS_DIR).plugins[0]!.dir).toBe(join(PLUGINS_DIR, "foo"))
})

test("defaults: enabled true, scopes [], version omitted when absent", () => {
  const raw = JSON.stringify({ version: 1, plugins: [{ name: "bare", source: { type: "git", url: "u" } }] })
  const p = parsePluginsRegistry(raw, PLUGINS_DIR).plugins[0]!
  expect(p.enabled).toBe(true)
  expect(p.scopes).toEqual([])
  expect(p.version).toBeUndefined()
})

test("preserves perSessionOverrides", () => {
  const raw = JSON.stringify({
    version: 1,
    plugins: [{ name: "sp", source: { type: "git", url: "u" }, scopes: ["claude"], perSessionOverrides: { "zs-analysis": { enabled: false } } }],
  })
  const p = parsePluginsRegistry(raw, PLUGINS_DIR).plugins[0]!
  expect(p.perSessionOverrides).toEqual({ "zs-analysis": { enabled: false } })
})

test("tolerates unknown top-level and per-plugin fields (forward compat)", () => {
  const raw = JSON.stringify({
    version: 1,
    futureKnob: true,
    plugins: [{ name: "sp", source: { type: "git", url: "u" }, scopes: ["claude"], experimentalFlag: 7 }],
  })
  expect(parsePluginsRegistry(raw, PLUGINS_DIR).plugins[0]!.name).toBe("sp")
})

test("throws on malformed JSON", () => {
  expect(() => parsePluginsRegistry("{ not json", PLUGINS_DIR)).toThrow()
})

test("throws when plugins is not an array", () => {
  expect(() => parsePluginsRegistry(JSON.stringify({ version: 1, plugins: {} }), PLUGINS_DIR)).toThrow(/plugins must be an array/)
})

test("throws when a plugin is missing a name", () => {
  const raw = JSON.stringify({ version: 1, plugins: [{ source: { type: "git", url: "u" } }] })
  expect(() => parsePluginsRegistry(raw, PLUGINS_DIR)).toThrow(/name/)
})

test("throws on an invalid source type", () => {
  const raw = JSON.stringify({ version: 1, plugins: [{ name: "x", source: { type: "ftp" } }] })
  expect(() => parsePluginsRegistry(raw, PLUGINS_DIR)).toThrow(/source\.type/)
})

test("throws on an unknown CLI scope", () => {
  const raw = JSON.stringify({ version: 1, plugins: [{ name: "x", source: { type: "git", url: "u" }, scopes: ["emacs"] }] })
  expect(() => parsePluginsRegistry(raw, PLUGINS_DIR)).toThrow(/scope/)
})

test("loadPluginsRegistry returns an empty registry when the file is absent", () => {
  const reg = loadPluginsRegistry({ file: "/no/such/plugins.json", pluginsDir: PLUGINS_DIR })
  expect(reg).toEqual({ version: 1, plugins: [] })
})

test("serializePluginsRegistry drops the derived dir field and round-trips through parse", () => {
  const raw = JSON.stringify({
    version: 1,
    plugins: [
      { name: "superpowers", version: "5.1.0", source: { type: "git", url: "https://x/y", ref: "v5.1.0" }, enabled: true, scopes: ["claude", "codex"] },
      { name: "core", source: { type: "local", path: "~/p/core" }, enabled: false, scopes: ["claude"], perSessionOverrides: { s: { enabled: false } } },
    ],
  })
  const reg = parsePluginsRegistry(raw, PLUGINS_DIR)
  const text = serializePluginsRegistry(reg)
  expect(text).not.toContain('"dir"')
  // Re-parsing the serialized form yields an equivalent registry.
  const reparsed = parsePluginsRegistry(text, PLUGINS_DIR)
  expect(reparsed).toEqual(reg)
})

test("savePluginsRegistry writes a file loadPluginsRegistry can read back", () => {
  const dir = tmp()
  try {
    const file = join(dir, "plugins.json")
    const reg = parsePluginsRegistry(JSON.stringify({
      version: 1,
      plugins: [{ name: "sp", version: "1.0.0", source: { type: "git", url: "u", ref: "v1" }, enabled: true, scopes: ["claude"] }],
    }), PLUGINS_DIR)
    savePluginsRegistry(reg, { file, pluginsDir: PLUGINS_DIR })
    const loaded = loadPluginsRegistry({ file, pluginsDir: PLUGINS_DIR })
    expect(loaded).toEqual(reg)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test("loadPluginsRegistry reads and parses an existing file", () => {
  const dir = tmp()
  try {
    const file = join(dir, "plugins.json")
    writeFileSync(file, JSON.stringify({ version: 1, plugins: [{ name: "sp", source: { type: "git", url: "u" }, scopes: ["claude"] }] }))
    const reg = loadPluginsRegistry({ file, pluginsDir: PLUGINS_DIR })
    expect(reg.plugins[0]!.name).toBe("sp")
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
