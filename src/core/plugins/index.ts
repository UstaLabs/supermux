// The broker's plugin host after C3: the REGISTRY (~/.mux/plugins.json: which plugins are
// installed, enabled, scoped to which CLI, overridden per session) and the selection of a
// session's plugins. How each agent loads a plugin folder is supermux-core's job: a session's
// selected plugin roots go into its SessionContext.plugins and the core maps them per agent
// (Claude/Cursor/Grok --plugin-dir, Codex skills extraRoots + .mcp.json servers, OpenCode
// skills.paths + .opencode/plugins, Antigravity generated wrappers in the shadow HOME's
// .gemini/config/plugins). The dead Gemini CLI scope is gone: a stored "gemini" scope is warned +
// skipped by parseScopes like any unknown scope. The per-CLI adapters and the Codex marketplace install
// (`codex plugin add` into ~/.codex and every session CODEX_HOME) are gone.
import { existsSync, readdirSync } from "fs"
import { join } from "path"
import { home } from "../../shared/home"
import { loadPluginsForSpawn, loadPluginsRegistry, savePluginsRegistry } from "./registry"
import { AgentKind } from "../../shared/agents"
import { isActiveForCli, type CliScope, type PerSessionOverride, type Plugin } from "./types"

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
 * Cursor and Codex; an OpenCode JS plugin or skill trees for OpenCode; skill trees for Grok;
 * skill trees or a `.mcp.json` for Antigravity.
 */
export function isPluginCompatible(cli: CliScope, plugin: Plugin): boolean {
  switch (cli) {
    case "claude": return existsSync(join(plugin.dir, ".claude-plugin", "plugin.json"))
    case "cursor": return existsSync(join(plugin.dir, ".cursor-plugin", "plugin.json"))
    case "codex": return existsSync(join(plugin.dir, ".codex-plugin", "plugin.json"))
    case "opencode": return listOpenCodePluginJs(plugin.dir).length > 0 || hasSkillTrees(plugin.dir)
    case "grok": return hasSkillTrees(plugin.dir)
    case "antigravity": return hasSkillTrees(plugin.dir) || existsSync(join(plugin.dir, ".mcp.json"))
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
 * CLI, not switched off for this session (by its id), compatible. Never throws: a missing / invalid
 * plugins.json yields none (and `onError` gets the validation message).
 */
export function sessionPlugins(cli: CliScope, sessionId: string, opts?: SessionPluginsOpts): string[] {
  const { plugins, error } = loadPluginsForSpawn({ file: opts?.file, pluginsDir: opts?.pluginsDir })
  if (error) opts?.onError?.(error)
  return plugins.filter((p) => isActiveForCli(p, cli, sessionId) && isPluginCompatible(cli, p)).map((p) => p.dir)
}

/**
 * Antigravity slash-command discovery: the session's plugin skills, then the skills of the
 * user's own agy plugins (~/.gemini/config/plugins/<name>/skills). The user's dir is only read.
 */
export function antigravitySkillsDirs(sessionId: string, opts?: SessionPluginsOpts & { userHome?: string }): string[] {
  const own = sessionPlugins(AgentKind.Antigravity, sessionId, opts).filter(hasSkillTrees).map((dir) => join(dir, "skills"))
  const userPlugins = join(opts?.userHome ?? home(), ".gemini", "config", "plugins")
  let user: string[] = []
  try {
    if (existsSync(userPlugins)) user = readdirSync(userPlugins).map((n) => join(userPlugins, n, "skills")).filter((d) => existsSync(d))
  } catch (e) {
    opts?.onError?.(`antigravity user plugins unreadable: ${e instanceof Error ? e.message : String(e)}`)
  }
  return [...own, ...user]
}

/**
 * Add `scope` to any enabled plugin compatible with that CLI, once per registry: a migration for registries
 * written before the CLI was supported. It is recorded in plugins.json (`migrations`), so a later boot never
 * adds the scope again over a plugin the user narrowed (#102: it used to run on every boot). Returns true when
 * plugins.json was updated.
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
  const migration = `scope:${scope}`
  if (reg.migrations?.includes(migration)) return false
  const plugins: Plugin[] = reg.plugins.map((p) => {
    if (!p.enabled || p.scopes.includes(scope)) return p
    if (!isPluginCompatible(scope, p)) return p
    return { ...p, scopes: [...p.scopes, scope] as CliScope[] }
  })
  savePluginsRegistry({ ...reg, migrations: [...(reg.migrations ?? []), migration], plugins }, { file, pluginsDir })
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

/** Add `antigravity` to scopes for any enabled plugin with skill trees or MCP servers agy can take. */
export function ensureAntigravityPluginScopes(opts?: { file?: string; pluginsDir?: string }): boolean {
  return ensurePluginScopes("antigravity", opts)
}

/**
 * Re-keys the per-session overrides written by session NAME (before #102; lost when a session renamed itself) to the
 * ids of the sessions with that name, once per registry (recorded in `migrations`). A key that is a session id, or
 * that no session is named, stays as it is. Returns true when plugins.json was updated.
 */
export function migrateOverridesToSessionIds(sessions: Array<{ id: string; name: string }>, opts?: { file?: string; pluginsDir?: string }): boolean {
  let reg: ReturnType<typeof loadPluginsRegistry>
  try {
    reg = loadPluginsRegistry({ file: opts?.file, pluginsDir: opts?.pluginsDir })
  } catch {
    return false
  }
  const migration = "overrides:session-id"
  if (reg.migrations?.includes(migration)) return false
  const ids = new Set(sessions.map((s) => s.id))
  const named = new Map<string, string[]>()
  for (const s of sessions) named.set(s.name, [...(named.get(s.name) ?? []), s.id])
  const plugins: Plugin[] = reg.plugins.map((p) => {
    if (!p.perSessionOverrides) return p
    const overrides: Record<string, PerSessionOverride> = {}
    for (const [key, override] of Object.entries(p.perSessionOverrides)) {
      const owners = ids.has(key) ? undefined : named.get(key)
      if (owners?.length) for (const id of owners) overrides[id] = override
      else overrides[key] = override
    }
    return { ...p, perSessionOverrides: overrides }
  })
  savePluginsRegistry({ ...reg, migrations: [...(reg.migrations ?? []), migration], plugins }, { file: opts?.file, pluginsDir: opts?.pluginsDir })
  return true
}
