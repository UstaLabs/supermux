import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { GrokPluginAdapter } from "./grok"
import type { Plugin } from "../types"

function tmpRoot(): string {
  return mkdtempSync(join(tmpdir(), "grok-adapter-"))
}

function makePlugin(root: string, name: string, opts: {
  skill?: boolean
  enabled?: boolean
  scopes?: Plugin["scopes"]
  overrides?: Plugin["perSessionOverrides"]
} = {}): Plugin {
  const dir = join(root, name)
  mkdirSync(dir, { recursive: true })
  if (opts.skill ?? false) {
    mkdirSync(join(dir, "skills", "demo"), { recursive: true })
    writeFileSync(join(dir, "skills", "demo", "SKILL.md"), "---\nname: demo\n---\n")
  }
  return {
    name,
    source: { type: "local", path: dir },
    enabled: opts.enabled ?? true,
    scopes: opts.scopes ?? ["grok"],
    perSessionOverrides: opts.overrides,
    dir,
  }
}

test("isCompatible is true only when the plugin ships skills/*/SKILL.md trees", () => {
  const root = tmpRoot()
  try {
    const a = new GrokPluginAdapter()
    expect(a.isCompatible(makePlugin(root, "skill", { skill: true }))).toBe(true)
    expect(a.isCompatible(makePlugin(root, "none"))).toBe(false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("configEntries emits each active plugin's skills dir", () => {
  const root = tmpRoot()
  try {
    const one = makePlugin(root, "superpowers", { skill: true })
    const two = makePlugin(root, "mux-core", { skill: true })
    const a = new GrokPluginAdapter()
    const { skillsPaths } = a.configEntries([one, two], { name: "sess" })
    expect(skillsPaths).toEqual([join(one.dir, "skills"), join(two.dir, "skills")])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("configEntries skips disabled, non-grok-scoped, incompatible, and per-session-disabled plugins", () => {
  const root = tmpRoot()
  try {
    const a = new GrokPluginAdapter()
    expect(a.configEntries([makePlugin(root, "d", { skill: true, enabled: false })], { name: "s" }).skillsPaths).toEqual([])
    expect(a.configEntries([makePlugin(root, "x", { skill: true, scopes: ["claude"] })], { name: "s" }).skillsPaths).toEqual([])
    expect(a.configEntries([makePlugin(root, "n")], { name: "s" }).skillsPaths).toEqual([])
    const ov = makePlugin(root, "o", { skill: true, overrides: { sess: { enabled: false } } })
    expect(a.configEntries([ov], { name: "sess" }).skillsPaths).toEqual([])
    expect(a.configEntries([ov], { name: "other" }).skillsPaths).toEqual([join(ov.dir, "skills")])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("spawnArgs contributes no flags (propagation is a config entry, not a CLI flag)", () => {
  const a = new GrokPluginAdapter()
  expect(a.spawnArgs([], { name: "s" })).toEqual({ args: [], env: {} })
})
