import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { ensureGrokPluginScopes, ensureOpenCodePluginScopes, grokConfigEntries, loadPluginsRegistry } from "./index"

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

test("grokConfigEntries resolves skills dirs for grok-scoped plugins from the registry file", () => {
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
    expect(grokConfigEntries({ file, pluginsDir: root, sessionName: "s" }).skillsPaths).toEqual([join(dir, "skills")])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("grokConfigEntries never throws: a malformed plugins.json yields no paths and reports the error", () => {
  const { root, file } = setup()
  try {
    writeFileSync(file, "{not json")
    let reported: string | undefined
    const { skillsPaths } = grokConfigEntries({ file, pluginsDir: root, onError: (e) => { reported = e } })
    expect(skillsPaths).toEqual([])
    expect(reported).toMatch(/invalid JSON/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
