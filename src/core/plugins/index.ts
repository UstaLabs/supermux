// The broker's plugin host after C3: the REGISTRY (~/.mux/plugins.json: which plugins are
// installed, enabled, scoped to which CLI, overridden per session) and the selection of a
// session's plugins. How each agent loads a plugin folder is supermux-core's job: a session's
// selected plugin roots go into its SessionContext.plugins and the core maps them per agent
// (Claude/Cursor/Grok --plugin-dir, Codex skills extraRoots + .mcp.json servers, OpenCode
// skills.paths + .opencode/plugins). The per-CLI adapters and the Codex marketplace install
// (`codex plugin add` into ~/.codex and every session CODEX_HOME) are gone.
import { existsSync, readdirSync } from "fs"
import { join } from "path"
import { loadPluginsForSpawn, loadPluginsRegistry, savePluginsRegistry } from "./registry"
import { AgentKind } from "../../shared/agents"
import { isActiveForCli, type CliScope, type Plugin } from "./types"

export type { Plugin, PluginsRegistry, CliScope } from "./types"
export { parsePluginsRegistry, loadPluginsRegistry, loadPluginsForSpawn } from "./registry"

/** True when the plugin ships `skills/<name>/SKILL.md` trees. */
export function hasSkillTrees(pluginDir: string): boolean {
  const skillsDir = join(pluginDir, "skills")
  if (!existsSync(skillsDir)) return false
  for (const entry of readdirSync(skillsDir, { withFileTypes: true })) {
    if (entry.isDirectory() && existsSync(join(skillsDir, entry.name, "SKILL.md"))) return true
  }
  return false
}

/** Absolute paths to `.opencode/plugins/*.js` in a plugin tree. */
export function listOpenCodePluginJs(pluginDir: string): string[] {
  const dir = join(pluginDir, ".opencode", "plugins")
  if (!existsSync(dir)) return []
  return readdirSync(dir).filter((f) => f.endsWith(".js")).map((f) => join(dir, f))
}

/**
 * Whether a plugin has what the CLI can load (the rule the per-CLI adapters applied before C3,
 * kept so a session gets exactly the plugins it got before): the CLI's manifest for Claude,
 * Cursor and Codex; an OpenCode JS plugin or skill trees for OpenCode; skill trees for Grok.
 */
export function isPluginCompatible(cli: CliScope, plugin: Plugin): boolean {
  switch (cli) {
    case "claude": return existsSync(join(plugin.dir, ".claude-plugin", "plugin.json"))
    case "cursor": return existsSync(join(plugin.dir, ".cursor-plugin", "plugin.json"))
    case "codex": return existsSync(join(plugin.dir, ".codex-plugin", "plugin.json"))
    case "opencode": return listOpenCodePluginJs(plugin.dir).length > 0 || hasSkillTrees(plugin.dir)
    case "grok": return hasSkillTrees(plugin.dir)
    default: return false
  }
}

interface SessionPluginsOpts {
  file?: string
  pluginsDir?: string
  onError?: (msg: string) => void
}

/**
 * The plugin roots a session of `cli` gets (its SessionContext.plugins): enabled, scoped to the
 * CLI, not switched off for this session, compatible. Never throws: a missing / invalid
 * plugins.json yields none (and `onError` gets the validation message).
 */
export function sessionPlugins(cli: CliScope, sessionName: string, opts?: SessionPluginsOpts): string[] {
  const { plugins, error } = loadPluginsForSpawn({ file: opts?.file, pluginsDir: opts?.pluginsDir })
  if (error) opts?.onError?.(error)
  return plugins.filter((p) => isActiveForCli(p, cli, sessionName) && isPluginCompatible(cli, p)).map((p) => p.dir)
}

/** Slash-command discovery probes (claude / cursor read `--plugin-dir` pairs; the others take none). */
export function pluginSpawnArgsForKind(kind: AgentKind, opts?: SessionPluginsOpts & { sessionName?: string }): string[] {
  if (kind !== AgentKind.Claude && kind !== AgentKind.Cursor) return []
  return sessionPlugins(kind, opts?.sessionName ?? "", opts).flatMap((dir) => ["--plugin-dir", dir])
}

/** Grok slash-command discovery: the skills folders of the session's plugins. */
export function grokSkillsDirs(sessionName: string, opts?: SessionPluginsOpts): string[] {
  return sessionPlugins(AgentKind.Grok, sessionName, opts).filter(hasSkillTrees).map((dir) => join(dir, "skills"))
}

/** OpenCode slash-command discovery: the session's plugins that ship an OpenCode JS plugin. */
export function opencodePluginRoots(sessionName: string, opts?: SessionPluginsOpts): string[] {
  return sessionPlugins(AgentKind.OpenCode, sessionName, opts).filter((dir) => listOpenCodePluginJs(dir).length > 0)
}

/**
 * Add `scope` to any enabled plugin compatible with that CLI. Idempotent; returns true when
 * plugins.json was updated. Run at boot for registries written before the CLI was supported.
 */
function ensurePluginScopes(scope: CliScope, opts?: { file?: string; pluginsDir?: string }): boolean {
  const file = opts?.file
  const pluginsDir = opts?.pluginsDir
  let reg: ReturnType<typeof loadPluginsRegistry>
  try {
    reg = loadPluginsRegistry({ file, pluginsDir })
  } catch {
    return false
  }
  let changed = false
  const plugins: Plugin[] = reg.plugins.map((p) => {
    if (!p.enabled || p.scopes.includes(scope)) return p
    if (!isPluginCompatible(scope, p)) return p
    changed = true
    return { ...p, scopes: [...p.scopes, scope] as CliScope[] }
  })
  if (!changed) return false
  savePluginsRegistry({ ...reg, plugins }, { file, pluginsDir })
  return true
}

/** Add `opencode` to scopes for any enabled plugin that ships an opencode-compatible tree. */
export function ensureOpenCodePluginScopes(opts?: { file?: string; pluginsDir?: string }): boolean {
  return ensurePluginScopes("opencode", opts)
}

/** Add `grok` to scopes for any enabled plugin that ships skill trees grok can discover. */
export function ensureGrokPluginScopes(opts?: { file?: string; pluginsDir?: string }): boolean {
  return ensurePluginScopes("grok", opts)
}
