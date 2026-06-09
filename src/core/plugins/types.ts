// Plugin host types — see the plugin-host design spec.
//
// mux owns a canonical ~/.mux/plugins/ tree and per-CLI adapters
// propagate each enabled plugin into Claude/Codex/Cursor sessions via each
// CLI's native plugin discovery. These types model the registry
// (~/.mux/plugins.json) and the adapter contract.
import type { AgentKind } from "../../shared/agents"

export type PluginSourceType = "local" | "git" | "git-subdir"

export interface PluginSource {
  type: PluginSourceType
  /** local: absolute or ~-prefixed path to the plugin tree on disk. */
  path?: string
  /** git / git-subdir: clone URL. */
  url?: string
  /** git / git-subdir: branch/tag/sha. */
  ref?: string
  /** git-subdir: subdirectory within the repo that holds the plugin. */
  subdir?: string
}

export type CliScope = AgentKind | "gemini"

/** Per-session override of a plugin's participation. */
export interface PerSessionOverride {
  enabled?: boolean
}

export interface Plugin {
  name: string
  version?: string
  source: PluginSource
  enabled: boolean
  scopes: CliScope[]
  /** Keyed by session name. A session listed with enabled:false skips this plugin. */
  perSessionOverrides?: Record<string, PerSessionOverride>
  /**
   * Resolved absolute path to the plugin root on disk. Computed at load time:
   * a `local` source uses its (tilde-expanded) path; everything else uses the
   * canonical PLUGINS_DIR/<name>. Adapters read manifests relative to this.
   */
  dir: string
}

export interface PluginsRegistry {
  version: number
  plugins: Plugin[]
}

/** Flags + env an adapter contributes to a single session spawn. */
export interface SpawnArgs {
  args: string[]
  env: Record<string, string>
}

/** Minimal session shape an adapter needs (decoupled from the broker Session). */
export interface PluginSession {
  name: string
}

export interface PluginAdapter {
  readonly cli: CliScope

  /** True if the plugin ships the manifest this CLI needs (e.g. .claude-plugin/plugin.json). */
  isCompatible(plugin: Plugin): boolean

  /** Run once on registry change. Claude/Cursor: no-op. Codex: regenerate marketplace + install. */
  prepareGlobal(plugins: Plugin[]): Promise<void>

  /** Flags + env for a single session spawn, given the full plugin list. */
  spawnArgs(plugins: Plugin[], session: PluginSession): SpawnArgs
}

export const CLI_SCOPES: readonly CliScope[] = ["claude", "codex", "cursor", "opencode", "gemini"]
export const PLUGIN_SOURCE_TYPES: readonly PluginSourceType[] = ["local", "git", "git-subdir"]

/**
 * Is this plugin active for the given CLI and session?
 * Checks enabled flag, CLI scope, and any per-session override. Compatibility
 * (manifest presence) is the adapter's responsibility, checked separately.
 */
export function isActiveForCli(plugin: Plugin, cli: CliScope, sessionName?: string): boolean {
  if (!plugin.enabled) return false
  if (!plugin.scopes.includes(cli)) return false
  if (sessionName) {
    const override = plugin.perSessionOverrides?.[sessionName]
    if (override?.enabled === false) return false
  }
  return true
}
