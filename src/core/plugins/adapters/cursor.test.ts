import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { CursorPluginAdapter } from "./cursor"
import type { Plugin } from "../types"

function tmpRoot(): string {
  return mkdtempSync(join(tmpdir(), "cursor-adapter-"))
}

function makePlugin(root: string, name: string, opts: { cursorManifest?: boolean; enabled?: boolean; scopes?: Plugin["scopes"]; overrides?: Plugin["perSessionOverrides"] } = {}): Plugin {
  const dir = join(root, name)
  mkdirSync(dir, { recursive: true })
  if (opts.cursorManifest ?? true) {
    mkdirSync(join(dir, ".cursor-plugin"), { recursive: true })
    writeFileSync(join(dir, ".cursor-plugin", "plugin.json"), JSON.stringify({ name }))
  }
  return {
    name,
    source: { type: "local", path: dir },
    enabled: opts.enabled ?? true,
    scopes: opts.scopes ?? ["cursor"],
    perSessionOverrides: opts.overrides,
    dir,
  }
}

test("isCompatible is true only when .cursor-plugin/plugin.json exists", () => {
  const root = tmpRoot()
  try {
    const a = new CursorPluginAdapter()
    expect(a.isCompatible(makePlugin(root, "good", { cursorManifest: true }))).toBe(true)
    expect(a.isCompatible(makePlugin(root, "bad", { cursorManifest: false }))).toBe(false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("spawnArgs emits one --plugin-dir per enabled, cursor-scoped, compatible plugin", () => {
  const root = tmpRoot()
  try {
    const sp = makePlugin(root, "superpowers")
    const core = makePlugin(root, "mux-core")
    const { args, env } = new CursorPluginAdapter().spawnArgs([sp, core], { name: "sess" })
    expect(args).toEqual(["--plugin-dir", sp.dir, "--plugin-dir", core.dir])
    expect(env).toEqual({})
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("spawnArgs skips disabled, non-cursor-scoped, and incompatible plugins", () => {
  const root = tmpRoot()
  try {
    const a = new CursorPluginAdapter()
    expect(a.spawnArgs([makePlugin(root, "d", { enabled: false })], { name: "s" }).args).toEqual([])
    expect(a.spawnArgs([makePlugin(root, "x", { scopes: ["claude", "codex"] })], { name: "s" }).args).toEqual([])
    expect(a.spawnArgs([makePlugin(root, "n", { cursorManifest: false })], { name: "s" }).args).toEqual([])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("spawnArgs honors a per-session override disabling the plugin", () => {
  const root = tmpRoot()
  try {
    const sp = makePlugin(root, "sp", { overrides: { "zs-analysis": { enabled: false } } })
    const a = new CursorPluginAdapter()
    expect(a.spawnArgs([sp], { name: "zs-analysis" }).args).toEqual([])
    expect(a.spawnArgs([sp], { name: "other" }).args).toEqual(["--plugin-dir", sp.dir])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
