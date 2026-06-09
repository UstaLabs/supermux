import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { ClaudePluginAdapter } from "./claude"
import type { Plugin } from "../types"

function tmpRoot(): string {
  return mkdtempSync(join(tmpdir(), "claude-adapter-"))
}

/** Create a plugin dir; if claudeManifest, drop a .claude-plugin/plugin.json into it. */
function makePlugin(root: string, name: string, opts: { claudeManifest?: boolean; enabled?: boolean; scopes?: Plugin["scopes"]; overrides?: Plugin["perSessionOverrides"] } = {}): Plugin {
  const dir = join(root, name)
  mkdirSync(dir, { recursive: true })
  if (opts.claudeManifest ?? true) {
    mkdirSync(join(dir, ".claude-plugin"), { recursive: true })
    writeFileSync(join(dir, ".claude-plugin", "plugin.json"), JSON.stringify({ name, version: "0.0.0" }))
  }
  return {
    name,
    source: { type: "local", path: dir },
    enabled: opts.enabled ?? true,
    scopes: opts.scopes ?? ["claude"],
    perSessionOverrides: opts.overrides,
    dir,
  }
}

test("isCompatible is true only when .claude-plugin/plugin.json exists", () => {
  const root = tmpRoot()
  try {
    const a = new ClaudePluginAdapter()
    expect(a.isCompatible(makePlugin(root, "good", { claudeManifest: true }))).toBe(true)
    expect(a.isCompatible(makePlugin(root, "bad", { claudeManifest: false }))).toBe(false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("spawnArgs emits one --plugin-dir per enabled, claude-scoped, compatible plugin", () => {
  const root = tmpRoot()
  try {
    const a = new ClaudePluginAdapter()
    const sp = makePlugin(root, "superpowers")
    const core = makePlugin(root, "mux-core")
    const { args, env } = a.spawnArgs([sp, core], { name: "sess" })
    expect(args).toEqual(["--plugin-dir", sp.dir, "--plugin-dir", core.dir])
    expect(env).toEqual({})
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("spawnArgs skips disabled plugins", () => {
  const root = tmpRoot()
  try {
    const sp = makePlugin(root, "sp", { enabled: false })
    expect(new ClaudePluginAdapter().spawnArgs([sp], { name: "s" }).args).toEqual([])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("spawnArgs skips plugins not scoped to claude", () => {
  const root = tmpRoot()
  try {
    const sp = makePlugin(root, "sp", { scopes: ["codex", "cursor"] })
    expect(new ClaudePluginAdapter().spawnArgs([sp], { name: "s" }).args).toEqual([])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("spawnArgs skips plugins without a claude manifest", () => {
  const root = tmpRoot()
  try {
    const sp = makePlugin(root, "sp", { claudeManifest: false })
    expect(new ClaudePluginAdapter().spawnArgs([sp], { name: "s" }).args).toEqual([])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("spawnArgs honors a per-session override disabling the plugin", () => {
  const root = tmpRoot()
  try {
    const sp = makePlugin(root, "sp", { overrides: { "zs-analysis": { enabled: false } } })
    const a = new ClaudePluginAdapter()
    expect(a.spawnArgs([sp], { name: "zs-analysis" }).args).toEqual([])
    expect(a.spawnArgs([sp], { name: "other" }).args).toEqual(["--plugin-dir", sp.dir])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("prepareGlobal is a no-op (resolves without throwing)", async () => {
  await expect(new ClaudePluginAdapter().prepareGlobal([])).resolves.toBeUndefined()
})
