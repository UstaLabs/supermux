// Plugin host types — see the plugin-host design spec.
//
// mux owns a canonical ~/.mux/plugins/ tree and a registry (~/.mux/plugins.json) of which
// plugins are enabled for which CLI. A session's selected plugin roots go into its
// SessionContext.plugins; supermux-core maps them per agent (C3).
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
   * canonical PLUGINS_DIR/<name>. Manifests are read relative to this.
   */
  dir: string
}

export interface PluginsRegistry {
  version: number
  plugins: Plugin[]
}

export const CLI_SCOPES: readonly CliScope[] = ["claude", "codex", "cursor", "opencode", "grok", "gemini"]
export const PLUGIN_SOURCE_TYPES: readonly PluginSourceType[] = ["local", "git", "git-subdir"]

/**
 * Is this plugin active for the given CLI and session?
 * Checks enabled flag, CLI scope, and any per-session override. Compatibility
 * (manifest presence) is checked separately (plugins/index.ts isPluginCompatible).
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
