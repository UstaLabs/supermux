import { readFileSync, writeFileSync, existsSync, mkdirSync } from "fs"
import { join, isAbsolute, dirname } from "path"
import { home } from "../../shared/home"
import { PLUGINS_DIR, PLUGINS_FILE } from "../../shared/paths"
import {
  CLI_SCOPES, PLUGIN_SOURCE_TYPES,
  type CliScope, type Plugin, type PluginSource, type PluginSourceType,
  type PluginsRegistry, type PerSessionOverride,
} from "./types"

// Hand-rolled validation, matching the codebase convention (no Zod in src/ —
// see the registry.json import path in main.ts). parsePluginsRegistry is pure
// and throws on any structurally-invalid input so it is easy to unit test;
// callers on the spawn hot-path use loadPluginsForSpawn(), which never throws.

function expandTilde(p: string): string {
  if (p === "~") return home()
  if (p.startsWith("~/")) return join(home(), p.slice(2))
  return p
}

function asObject(v: unknown, what: string): Record<string, unknown> {
  if (typeof v !== "object" || v === null || Array.isArray(v)) {
    throw new Error(`plugins.json: ${what} must be an object`)
  }
  return v as Record<string, unknown>
}

function parseSource(raw: unknown, pluginName: string): PluginSource {
  const o = asObject(raw, `plugin "${pluginName}" source`)
  const type = o.type
  if (typeof type !== "string" || !PLUGIN_SOURCE_TYPES.includes(type as PluginSourceType)) {
    throw new Error(`plugins.json: plugin "${pluginName}" has invalid source.type (got ${JSON.stringify(type)}; expected one of ${PLUGIN_SOURCE_TYPES.join(", ")})`)
  }
  const src: PluginSource = { type: type as PluginSourceType }
  if (typeof o.path === "string") src.path = o.path
  if (typeof o.url === "string") src.url = o.url
  if (typeof o.ref === "string") src.ref = o.ref
  if (typeof o.subdir === "string") src.subdir = o.subdir
  return src
}

function parseScopes(raw: unknown, pluginName: string): CliScope[] {
  if (raw === undefined) return []
  if (!Array.isArray(raw)) throw new Error(`plugins.json: plugin "${pluginName}" scopes must be an array`)
  for (const s of raw) {
    if (typeof s !== "string" || !CLI_SCOPES.includes(s as CliScope)) {
      throw new Error(`plugins.json: plugin "${pluginName}" has invalid scope ${JSON.stringify(s)} (expected one of ${CLI_SCOPES.join(", ")})`)
    }
  }
  return raw as CliScope[]
}

function parseOverrides(raw: unknown, pluginName: string): Record<string, PerSessionOverride> | undefined {
  if (raw === undefined) return undefined
  const o = asObject(raw, `plugin "${pluginName}" perSessionOverrides`)
  const out: Record<string, PerSessionOverride> = {}
  for (const [session, val] of Object.entries(o)) {
    const ov = asObject(val, `plugin "${pluginName}" perSessionOverrides["${session}"]`)
    out[session] = typeof ov.enabled === "boolean" ? { enabled: ov.enabled } : {}
  }
  return out
}

/** Resolve the on-disk plugin root: a local path (tilde-expanded) or canonical PLUGINS_DIR/<name>. */
function resolveDir(name: string, source: PluginSource, pluginsDir: string): string {
  if (source.type === "local" && source.path) {
    const expanded = expandTilde(source.path)
    return isAbsolute(expanded) ? expanded : join(pluginsDir, expanded)
  }
  return join(pluginsDir, name)
}

function parsePlugin(raw: unknown, pluginsDir: string): Plugin {
  const o = asObject(raw, "plugin entry")
  const name = o.name
  if (typeof name !== "string" || name.length === 0) {
    throw new Error(`plugins.json: every plugin needs a non-empty string name (got ${JSON.stringify(name)})`)
  }
  const source = parseSource(o.source, name)
  const scopes = parseScopes(o.scopes, name)
  const plugin: Plugin = {
    name,
    source,
    enabled: o.enabled === undefined ? true : o.enabled === true,
    scopes,
    dir: resolveDir(name, source, pluginsDir),
  }
  if (typeof o.version === "string") plugin.version = o.version
  const overrides = parseOverrides(o.perSessionOverrides, name)
  if (overrides) plugin.perSessionOverrides = overrides
  return plugin
}

/** Parse + validate registry text. Pure; throws on any invalid input. */
export function parsePluginsRegistry(text: string, pluginsDir: string = PLUGINS_DIR): PluginsRegistry {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch (err: any) {
    throw new Error(`plugins.json: invalid JSON (${err?.message ?? String(err)})`)
  }
  const root = asObject(parsed, "root")
  const version = typeof root.version === "number" ? root.version : 1
  if (root.plugins !== undefined && !Array.isArray(root.plugins)) {
    throw new Error("plugins.json: plugins must be an array")
  }
  const plugins = ((root.plugins as unknown[]) ?? []).map((p) => parsePlugin(p, pluginsDir))
  return { version, plugins }
}

/** Read + parse the registry file. Missing file → empty registry. Throws on invalid content. */
export function loadPluginsRegistry(opts?: { file?: string; pluginsDir?: string }): PluginsRegistry {
  const file = opts?.file ?? PLUGINS_FILE
  const pluginsDir = opts?.pluginsDir ?? PLUGINS_DIR
  if (!existsSync(file)) return { version: 1, plugins: [] }
  return parsePluginsRegistry(readFileSync(file, "utf8"), pluginsDir)
}

/**
 * Serialize a registry back to plugins.json text. Drops the derived `dir`
 * field (recomputed on load) so the file stays declarative. Round-trips
 * through parsePluginsRegistry.
 */
export function serializePluginsRegistry(reg: PluginsRegistry): string {
  const out = {
    version: reg.version,
    plugins: reg.plugins.map((p) => {
      const entry: Record<string, unknown> = { name: p.name }
      if (p.version !== undefined) entry.version = p.version
      entry.source = p.source
      entry.enabled = p.enabled
      entry.scopes = p.scopes
      if (p.perSessionOverrides) entry.perSessionOverrides = p.perSessionOverrides
      return entry
    }),
  }
  return JSON.stringify(out, null, 2) + "\n"
}

/** Write the registry to plugins.json (creating parent dirs as needed). */
export function savePluginsRegistry(reg: PluginsRegistry, opts?: { file?: string; pluginsDir?: string }): void {
  const file = opts?.file ?? PLUGINS_FILE
  mkdirSync(dirname(file), { recursive: true })
  writeFileSync(file, serializePluginsRegistry(reg))
}

/**
 * Spawn-hot-path loader: never throws. A missing/malformed registry yields an
 * empty plugin list so a bad plugins.json can never break session spawns.
 * Returns the validation error (if any) so callers can log it.
 */
export function loadPluginsForSpawn(opts?: { file?: string; pluginsDir?: string }): { plugins: Plugin[]; error?: string } {
  try {
    return { plugins: loadPluginsRegistry(opts).plugins }
  } catch (err: any) {
    return { plugins: [], error: err?.message ?? String(err) }
  }
}
