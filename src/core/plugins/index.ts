import { loadPluginsForSpawn, loadPluginsRegistry, savePluginsRegistry } from "./registry"
import { makeLogger } from "../../shared/log"
import { ClaudePluginAdapter } from "./adapters/claude"

const pluginLog = makeLogger("plugins/index")
import { CursorPluginAdapter } from "./adapters/cursor"
import { CodexPluginAdapter } from "./adapters/codex"
import { OpenCodePluginAdapter } from "./adapters/opencode"
import type { OpenCodeConfigEntries } from "./adapters/opencode"
import { GrokPluginAdapter } from "./adapters/grok"
import type { GrokConfigEntries } from "./adapters/grok"
import type { SpawnArgs, Plugin, CliScope } from "./types"

export type { Plugin, PluginAdapter, PluginsRegistry, SpawnArgs, CliScope } from "./types"
export { parsePluginsRegistry, loadPluginsRegistry, loadPluginsForSpawn } from "./registry"
export { ClaudePluginAdapter } from "./adapters/claude"
export { CursorPluginAdapter } from "./adapters/cursor"
export { CodexPluginAdapter, CODEX_MARKETPLACE_NAME, agentsMarketplacePath } from "./adapters/codex"
export { OpenCodePluginAdapter } from "./adapters/opencode"
export type { OpenCodeConfigEntries } from "./adapters/opencode"
export { GrokPluginAdapter } from "./adapters/grok"
export type { GrokConfigEntries } from "./adapters/grok"

const claudeAdapter = new ClaudePluginAdapter()
const cursorAdapter = new CursorPluginAdapter()
const codexAdapter = new CodexPluginAdapter()
const opencodeAdapter = new OpenCodePluginAdapter()
const grokAdapter = new GrokPluginAdapter()

interface SpawnArgsOpts {
  sessionName?: string
  file?: string
  pluginsDir?: string
  onError?: (msg: string) => void
}

function spawnArgsFor(adapter: { spawnArgs: (p: any, s: any) => SpawnArgs }, opts?: SpawnArgsOpts): SpawnArgs {
  const { plugins, error } = loadPluginsForSpawn({ file: opts?.file, pluginsDir: opts?.pluginsDir })
  if (error) opts?.onError?.(error)
  return adapter.spawnArgs(plugins, { name: opts?.sessionName ?? "" })
}

/**
 * Resolve the per-session plugin flags for a CLI spawn from the on-disk
 * registry. Never throws — a missing/invalid plugins.json yields no flags so
 * spawns are never broken. `onError` surfaces a validation message for logging.
 *
 *   - Claude/Cursor: `--plugin-dir <dir>` pairs.
 *   - Codex: `-c plugins."<name>@mux".enabled=true` pairs.
 */
export function claudeSpawnArgs(opts?: SpawnArgsOpts): SpawnArgs {
  return spawnArgsFor(claudeAdapter, opts)
}

export function cursorSpawnArgs(opts?: SpawnArgsOpts): SpawnArgs {
  return spawnArgsFor(cursorAdapter, opts)
}

export function codexSpawnArgs(opts?: SpawnArgsOpts): SpawnArgs {
  return spawnArgsFor(codexAdapter, opts)
}

/**
 * Resolve plugin + skills paths for an opencode session's opencode.json from the
 * on-disk registry. Never throws — a missing/invalid plugins.json yields empty
 * lists so spawns are never broken.
 */
export function opencodeConfigEntries(opts?: SpawnArgsOpts): OpenCodeConfigEntries {
  const { plugins, error } = loadPluginsForSpawn({ file: opts?.file, pluginsDir: opts?.pluginsDir })
  if (error) opts?.onError?.(error)
  return opencodeAdapter.configEntries(plugins, { name: opts?.sessionName ?? "" })
}

/**
 * Resolve skills paths for a grok session's private ~/.grok/config.toml from
 * the on-disk registry. Never throws — a missing/invalid plugins.json yields an
 * empty list so spawns are never broken.
 */
export function grokConfigEntries(opts?: SpawnArgsOpts): GrokConfigEntries {
  const { plugins, error } = loadPluginsForSpawn({ file: opts?.file, pluginsDir: opts?.pluginsDir })
  if (error) opts?.onError?.(error)
  return grokAdapter.configEntries(plugins, { name: opts?.sessionName ?? "" })
}

/**
 * Add `scope` to any enabled plugin the adapter reports compatible. Idempotent;
 * returns true when plugins.json was updated. Shared engine for the per-CLI
 * scope-migration helpers below (run at boot for registries written before the
 * CLI's adapter existed).
 */
function ensurePluginScopes(
  adapter: { isCompatible(plugin: Plugin): boolean },
  scope: CliScope,
  opts?: { file?: string; pluginsDir?: string },
): boolean {
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
    if (!adapter.isCompatible(p)) return p
    changed = true
    return { ...p, scopes: [...p.scopes, scope] as CliScope[] }
  })
  if (!changed) return false
  savePluginsRegistry({ ...reg, plugins }, { file, pluginsDir })
  return true
}

/**
 * Add `opencode` to scopes for any enabled plugin that ships an opencode-
 * compatible tree. Idempotent; returns true when plugins.json was updated.
 */
export function ensureOpenCodePluginScopes(opts?: { file?: string; pluginsDir?: string }): boolean {
  return ensurePluginScopes(opencodeAdapter, "opencode", opts)
}

/**
 * Add `grok` to scopes for any enabled plugin that ships skill trees grok can
 * discover. Idempotent; returns true when plugins.json was updated.
 */
export function ensureGrokPluginScopes(opts?: { file?: string; pluginsDir?: string }): boolean {
  return ensurePluginScopes(grokAdapter, "grok", opts)
}

/**
 * Regenerate Codex's marketplace + install plugins from the registry. Run once
 * at broker boot (and on registry change). Never throws — logs and continues
 * so a bad registry or missing codex binary can't crash startup.
 */
export async function codexPrepareGlobal(opts?: { file?: string; pluginsDir?: string; onError?: (msg: string) => void }): Promise<void> {
  const { plugins, error } = loadPluginsForSpawn({ file: opts?.file, pluginsDir: opts?.pluginsDir })
  if (error) opts?.onError?.(error)
  try {
    await codexAdapter.prepareGlobal(plugins)
  } catch (err: any) {
    opts?.onError?.(err?.message ?? String(err))
  }
}

// Homes already prepared this broker lifetime — avoids re-running `codex plugin
// add` on every spawn/resume of the same session.
const preparedCodexHomes = new Set<string>()

/**
 * Install the enabled codex plugins into a specific session's CODEX_HOME so the
 * session app-server can discover + natively use them. Idempotent + cached per
 * home; safe to call before every codex spawn. Never throws.
 */
export async function codexPrepareSessionHome(
  codexHome: string,
  opts?: { file?: string; pluginsDir?: string; force?: boolean; onError?: (msg: string) => void },
): Promise<void> {
  if (!codexHome) return
  if (!opts?.force && preparedCodexHomes.has(codexHome)) return
  const { plugins, error } = loadPluginsForSpawn({ file: opts?.file, pluginsDir: opts?.pluginsDir })
  if (error) opts?.onError?.(error)
  try {
    pluginLog.info("codex_prepare_home_start", { codexHome })
    const ids = await codexAdapter.prepareSessionHome(plugins, codexHome)
    pluginLog.info("codex_prepare_home_done", { codexHome, installed: ids })
    preparedCodexHomes.add(codexHome)
  } catch (err: any) {
    opts?.onError?.(err?.message ?? String(err))
  }
}
