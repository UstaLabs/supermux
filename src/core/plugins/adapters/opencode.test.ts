import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { OpenCodePluginAdapter, hasSkillTrees, listOpenCodePluginJs } from "./opencode"
import type { Plugin } from "../types"

function tmpRoot(): string {
  return mkdtempSync(join(tmpdir(), "opencode-adapter-"))
}

function makePlugin(root: string, name: string, opts: {
  jsPlugin?: boolean
  skill?: boolean
  enabled?: boolean
  scopes?: Plugin["scopes"]
  overrides?: Plugin["perSessionOverrides"]
} = {}): Plugin {
  const dir = join(root, name)
  mkdirSync(dir, { recursive: true })
  if (opts.jsPlugin ?? false) {
    mkdirSync(join(dir, ".opencode", "plugins"), { recursive: true })
    writeFileSync(join(dir, ".opencode", "plugins", `${name}.js`), "export const Plugin = async () => ({})")
  }
  if (opts.skill ?? false) {
    mkdirSync(join(dir, "skills", "demo"), { recursive: true })
    writeFileSync(join(dir, "skills", "demo", "SKILL.md"), "---\nname: demo\n---\n")
  }
  return {
    name,
    source: { type: "local", path: dir },
    enabled: opts.enabled ?? true,
    scopes: opts.scopes ?? ["opencode"],
    perSessionOverrides: opts.overrides,
    dir,
  }
}

test("isCompatible is true when .opencode/plugins/*.js or skills/*/SKILL.md exists", () => {
  const root = tmpRoot()
  try {
    const a = new OpenCodePluginAdapter()
    expect(a.isCompatible(makePlugin(root, "js", { jsPlugin: true }))).toBe(true)
    expect(a.isCompatible(makePlugin(root, "skill", { skill: true }))).toBe(true)
    expect(a.isCompatible(makePlugin(root, "both", { jsPlugin: true, skill: true }))).toBe(true)
    expect(a.isCompatible(makePlugin(root, "none"))).toBe(false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("listOpenCodePluginJs returns absolute paths to .js files only", () => {
  const root = tmpRoot()
  try {
    const p = makePlugin(root, "sp", { jsPlugin: true })
    writeFileSync(join(p.dir, ".opencode", "plugins", "readme.txt"), "nope")
    expect(listOpenCodePluginJs(p.dir)).toEqual([join(p.dir, ".opencode", "plugins", "sp.js")])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("hasSkillTrees requires a SKILL.md inside a skills subdir", () => {
  const root = tmpRoot()
  try {
    const p = makePlugin(root, "x", { skill: true })
    expect(hasSkillTrees(p.dir)).toBe(true)
    mkdirSync(join(p.dir, "skills", "empty"), { recursive: true })
    expect(hasSkillTrees(join(root, "no-skills"))).toBe(false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("configEntries emits pluginPaths for JS plugins and skillsPaths for skills-only plugins", () => {
  const root = tmpRoot()
  try {
    const js = makePlugin(root, "superpowers", { jsPlugin: true, skill: true })
    const skillsOnly = makePlugin(root, "extra", { skill: true })
    const a = new OpenCodePluginAdapter()
    const { pluginPaths, skillsPaths } = a.configEntries([js, skillsOnly], { name: "sess" })
    expect(pluginPaths).toEqual([js.dir])
    expect(skillsPaths).toEqual([join(skillsOnly.dir, "skills")])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("configEntries skips disabled, non-opencode-scoped, incompatible, and per-session-disabled plugins", () => {
  const root = tmpRoot()
  try {
    const a = new OpenCodePluginAdapter()
    expect(a.configEntries([makePlugin(root, "d", { jsPlugin: true, enabled: false })], { name: "s" }).pluginPaths).toEqual([])
    expect(a.configEntries([makePlugin(root, "x", { jsPlugin: true, scopes: ["claude"] })], { name: "s" }).pluginPaths).toEqual([])
    expect(a.configEntries([makePlugin(root, "n")], { name: "s" }).pluginPaths).toEqual([])
    const ov = makePlugin(root, "o", { jsPlugin: true, overrides: { sess: { enabled: false } } })
    expect(a.configEntries([ov], { name: "sess" }).pluginPaths).toEqual([])
    expect(a.configEntries([ov], { name: "other" }).pluginPaths).toEqual([ov.dir])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
