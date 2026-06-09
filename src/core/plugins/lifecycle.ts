import { existsSync, rmSync, cpSync } from "fs"
import { basename, isAbsolute, join } from "path"
import { execFileSync } from "child_process"
import { PLUGINS_DIR } from "../../shared/paths"
import { home } from "../../shared/home"
import { loadPluginsRegistry, savePluginsRegistry } from "./registry"
import { ClaudePluginAdapter } from "./adapters/claude"
import { CursorPluginAdapter } from "./adapters/cursor"
import { CodexPluginAdapter, codexPluginId, CODEX_MARKETPLACE_NAME } from "./adapters/codex"
import { codexPrepareGlobal } from "./index"
import type { CliScope, Plugin, PluginSource, PluginsRegistry } from "./types"

// Lifecycle operations behind `mux plugin …`. The pure helpers
// (parseAddSource/applyEnable/applyRemove/pluginSummaries) carry the logic and
// are unit-tested; the side-effecting orchestrators (add/update/remove) compose
// them with git/fs/registry I/O and an injectable exec seam.

const claude = new ClaudePluginAdapter()
const cursor = new CursorPluginAdapter()
const codex = new CodexPluginAdapter()

export interface AddOptions {
  name?: string
  ref?: string
  scopes?: CliScope[]
}

const DEFAULT_SCOPES: CliScope[] = ["claude", "codex", "cursor"]

function expandTilde(p: string): string {
  if (p === "~") return home()
  if (p.startsWith("~/")) return join(home(), p.slice(2))
  return p
}

function looksLikePath(spec: string): boolean {
  return spec.startsWith("/") || spec.startsWith("~") || spec.startsWith("./") || spec.startsWith("../")
}

/** Parse an `add` source spec into a registry-ready name + source. Pure. */
export function parseAddSource(spec: string, opts: AddOptions = {}): { name: string; source: PluginSource } {
  let source: PluginSource
  let derivedName: string

  if (spec.startsWith("github:")) {
    const slug = spec.slice("github:".length).replace(/\.git$/, "")
    source = { type: "git", url: `https://github.com/${slug}` }
    derivedName = basename(slug)
  } else if (/^https?:\/\//.test(spec) || spec.startsWith("git@")) {
    source = { type: "git", url: spec }
    derivedName = basename(spec).replace(/\.git$/, "")
  } else if (looksLikePath(spec)) {
    source = { type: "local", path: spec }
    derivedName = basename(spec.replace(/\/+$/, ""))
  } else {
    throw new Error(`unrecognized plugin source: ${spec} (use a path, a https git URL, or github:org/repo)`)
  }

  if (opts.ref && source.type === "git") source.ref = opts.ref
  return { name: opts.name ?? derivedName, source }
}

/** Return a new registry with the named plugin's enabled/scopes updated. Pure. */
export function applyEnable(reg: PluginsRegistry, name: string, change: { enabled?: boolean; scopes?: CliScope[] }): PluginsRegistry {
  const plugin = reg.plugins.find((p) => p.name === name)
  if (!plugin) throw new Error(`no such plugin: ${name}`)
  const updated: Plugin = { ...plugin }
  if (change.enabled !== undefined) updated.enabled = change.enabled
  if (change.scopes !== undefined) updated.scopes = change.scopes
  return { ...reg, plugins: reg.plugins.map((p) => (p.name === name ? updated : p)) }
}

/** Return a new registry without the named plugin. Pure. */
export function applyRemove(reg: PluginsRegistry, name: string): PluginsRegistry {
  if (!reg.plugins.some((p) => p.name === name)) throw new Error(`no such plugin: ${name}`)
  return { ...reg, plugins: reg.plugins.filter((p) => p.name !== name) }
}

export interface PluginSummary {
  name: string
  version?: string
  enabled: boolean
  scopes: CliScope[]
  source: PluginSource
  compatibility: { claude: boolean; cursor: boolean; codex: boolean }
}

/** Build the per-plugin rows shown by `plugin list`. Reads manifests from disk. */
export function pluginSummaries(reg: PluginsRegistry): PluginSummary[] {
  return reg.plugins.map((p) => ({
    name: p.name,
    version: p.version,
    enabled: p.enabled,
    scopes: p.scopes,
    source: p.source,
    compatibility: {
      claude: claude.isCompatible(p),
      cursor: cursor.isCompatible(p),
      codex: codex.isCompatible(p),
    },
  }))
}

// ---- Side-effecting orchestrators -----------------------------------------

export type Exec = (cmd: string, args: string[]) => void
const defaultExec: Exec = (cmd, args) => { execFileSync(cmd, args, { stdio: "inherit" }) }

export interface LifecycleDeps {
  file?: string
  pluginsDir?: string
  exec?: Exec
  /** Re-run codex marketplace generation + install after a registry change. */
  prepareCodex?: () => Promise<void>
}

function deps(d: LifecycleDeps = {}) {
  const file = d.file                            // undefined → registry uses PLUGINS_FILE
  const pluginsDir = d.pluginsDir ?? PLUGINS_DIR
  const exec = d.exec ?? defaultExec
  const prepareCodex = d.prepareCodex ?? (() => codexPrepareGlobal({ file, pluginsDir }))
  return { file, pluginsDir, exec, prepareCodex }
}

/** Install a plugin: fetch into the canonical tree, append to the registry, refresh codex. */
export async function addPlugin(spec: string, opts: AddOptions = {}, d: LifecycleDeps = {}): Promise<PluginSummary> {
  const dd = deps(d)
  const { name, source } = parseAddSource(spec, opts)
  const reg = loadPluginsRegistry({ file: dd.file, pluginsDir: dd.pluginsDir })
  if (reg.plugins.some((p) => p.name === name)) throw new Error(`plugin already installed: ${name} (use 'plugin update ${name}')`)

  const dest = join(dd.pluginsDir, name)
  if (source.type === "git") {
    if (existsSync(dest)) rmSync(dest, { recursive: true, force: true })
    const args = ["clone", "--depth", "1"]
    if (source.ref) args.push("--branch", source.ref)
    args.push(source.url!, dest)
    dd.exec("git", args)
  } else {
    const src = expandTilde(source.path!)
    if (!existsSync(src)) throw new Error(`local source does not exist: ${src}`)
    if (existsSync(dest)) throw new Error(`destination already exists: ${dest}`)
    cpSync(src, dest, { recursive: true })
  }

  const scopes = opts.scopes ?? DEFAULT_SCOPES
  reg.plugins.push({ name, source, enabled: true, scopes, dir: dest })
  savePluginsRegistry(reg, { file: dd.file, pluginsDir: dd.pluginsDir })
  await dd.prepareCodex()
  return pluginSummaries(reg).find((s) => s.name === name)!
}

/** Update a git plugin in place (pull latest) and refresh codex. */
export async function updatePlugin(name: string, d: LifecycleDeps = {}): Promise<void> {
  const dd = deps(d)
  const reg = loadPluginsRegistry({ file: dd.file, pluginsDir: dd.pluginsDir })
  const plugin = reg.plugins.find((p) => p.name === name)
  if (!plugin) throw new Error(`no such plugin: ${name}`)
  if (plugin.source.type !== "git") throw new Error(`plugin '${name}' is a ${plugin.source.type} source; nothing to pull`)
  dd.exec("git", ["-C", plugin.dir, "pull", "--ff-only"])
  await dd.prepareCodex()
}

/** Remove a plugin from the registry (and codex), optionally deleting its tree. */
export async function removePlugin(name: string, opts: { purge?: boolean } = {}, d: LifecycleDeps = {}): Promise<void> {
  const dd = deps(d)
  const reg = loadPluginsRegistry({ file: dd.file, pluginsDir: dd.pluginsDir })
  const plugin = reg.plugins.find((p) => p.name === name)
  if (!plugin) throw new Error(`no such plugin: ${name}`)

  // Best-effort codex uninstall (no-op for plugins codex never installed).
  try { dd.exec("codex", ["plugin", "remove", `${codexPluginId(plugin)}@${CODEX_MARKETPLACE_NAME}`]) } catch { /* ignore */ }

  savePluginsRegistry(applyRemove(reg, name), { file: dd.file, pluginsDir: dd.pluginsDir })
  await dd.prepareCodex()
  if (opts.purge && existsSync(plugin.dir)) rmSync(plugin.dir, { recursive: true, force: true })
}

/** Enable/disable a plugin or change its scopes; persists + refreshes codex. */
export async function setPluginEnabled(name: string, change: { enabled?: boolean; scopes?: CliScope[] }, d: LifecycleDeps = {}): Promise<void> {
  const dd = deps(d)
  const reg = loadPluginsRegistry({ file: dd.file, pluginsDir: dd.pluginsDir })
  savePluginsRegistry(applyEnable(reg, name, change), { file: dd.file, pluginsDir: dd.pluginsDir })
  await dd.prepareCodex()
}

export function listPlugins(d: LifecycleDeps = {}): PluginSummary[] {
  const dd = deps(d)
  return pluginSummaries(loadPluginsRegistry({ file: dd.file, pluginsDir: dd.pluginsDir }))
}
