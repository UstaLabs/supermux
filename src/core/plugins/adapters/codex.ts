import { existsSync, mkdirSync, writeFileSync, readFileSync } from "fs"
import { dirname, join, relative, resolve } from "path"
import { execFileSync, execFile } from "child_process"
import { promisify } from "util"

const execFileAsync = promisify(execFile)
import { home } from "../../../shared/home"
import { makeLogger } from "../../../shared/log"
import { isActiveForCli, type Plugin, type PluginAdapter, type PluginSession, type SpawnArgs } from "../types"

const log = makeLogger("plugins/codex")

// Codex enforces that a marketplace entry's name EQUALS the name inside the
// plugin's .codex-plugin/plugin.json (verified 2026-05-30: "plugin.json name
// `mux` does not match marketplace plugin name `mux-core`"). The
// registry/dir name can differ from the manifest name (e.g. dir `mux-core`
// ships manifest name `mux` so skills namespace as `mux:`), so for
// ALL codex operations — marketplace entry, `codex plugin add`, and the
// per-spawn `-c` flag — we use the MANIFEST name, falling back to the registry
// name if the manifest is missing/unreadable.
export function codexPluginId(plugin: Plugin): string {
  try {
    const manifest = JSON.parse(readFileSync(join(plugin.dir, ".codex-plugin", "plugin.json"), "utf8"))
    if (typeof manifest?.name === "string" && manifest.name.length > 0) return manifest.name
  } catch {
    // missing/unreadable manifest — fall back below
  }
  return plugin.name
}

// Codex has no `--plugin-dir` equivalent (verified by binary/docs search), so
// it works differently from Claude/Cursor:
//   - prepareGlobal regenerates ~/.agents/plugins/marketplace.json (implicitly
//     discovered by codex — no `marketplace add` needed) and runs an idempotent
//     `codex plugin add <name>@mux` per plugin.
//   - spawnArgs emits `-c plugins."<name>@mux".enabled=true` per plugin,
//     spliced into the `codex app-server` invocation (which already takes -c).
// See the plugin-host spec, "Per-CLI adapters" + "Verified mechanisms".

export const CODEX_MARKETPLACE_NAME = "mux"

/** Where codex implicitly discovers marketplaces. */
export function agentsMarketplacePath(): string {
  return join(home(), ".agents", "plugins", "marketplace.json")
}

// codex treats the marketplace ROOT as the dir two levels above the
// `.agents/plugins/marketplace.json` file (i.e. the dir that *contains*
// `.agents`), and requires each plugin's `source.path` to be RELATIVE to that
// root — absolute paths are rejected ("plugin not found"). Verified 2026-05-30.
export function marketplaceRootFor(marketplacePath: string): string {
  return resolve(dirname(marketplacePath), "..", "..")
}

function toRelativeSource(root: string, dir: string): string {
  const rel = relative(root, dir)
  // Codex wants an explicit "./" prefix. Note a dotfile path like
  // ".mux/…" already starts with "." but still needs the "./" — only a
  // genuine "../" (outside root) or existing "./" prefix is left untouched.
  if (rel.startsWith("..") || rel.startsWith("./")) return rel
  return `./${rel}`
}

export interface CodexMarketplaceEntry {
  name: string
  source: { source: "local"; path: string }
  policy: { installation: "AVAILABLE" }
}

export interface CodexMarketplace {
  name: string
  interface: { displayName: string }
  plugins: CodexMarketplaceEntry[]
}

/**
 * Pure: build the marketplace.json content for the given (already-filtered)
 * plugins. Paths are emitted RELATIVE to `marketplaceRoot` (codex rejects
 * absolute source paths).
 */
export function buildCodexMarketplace(plugins: Plugin[], marketplaceRoot: string): CodexMarketplace {
  return {
    name: CODEX_MARKETPLACE_NAME,
    interface: { displayName: "mux plugins" },
    plugins: plugins.map((p) => ({
      // Must equal the plugin's manifest name — codex rejects a mismatch.
      name: codexPluginId(p),
      source: { source: "local", path: toRelativeSource(marketplaceRoot, p.dir) },
      policy: { installation: "AVAILABLE" },
    })),
  }
}

/** Installs one plugin from the mux marketplace. Injectable for tests. */
export type CodexPluginInstaller = (pluginName: string) => void

function defaultInstaller(pluginName: string): void {
  // Idempotent in practice: re-adding an installed plugin is a no-op or a
  // harmless non-zero exit, which prepareGlobal swallows per-plugin.
  execFileSync("codex", ["plugin", "add", `${pluginName}@${CODEX_MARKETPLACE_NAME}`], { stdio: "ignore" })
}

/** Installs one plugin into a specific CODEX_HOME (async; non-blocking). Injectable for tests. */
export type CodexHomeInstaller = (pluginName: string, codexHome: string) => Promise<void>

async function defaultHomeInstaller(pluginName: string, codexHome: string): Promise<void> {
  // `-c` enable flags only take effect once a plugin is actually INSTALLED in
  // the target CODEX_HOME; the global install (prepareGlobal) lands in ~/.codex,
  // not the per-session home, so we install here too. Idempotent.
  await execFileAsync("codex", ["plugin", "add", `${pluginName}@${CODEX_MARKETPLACE_NAME}`], {
    env: { ...process.env, CODEX_HOME: codexHome },
  })
}

export interface CodexPluginAdapterOpts {
  marketplacePath?: string
  installPlugin?: CodexPluginInstaller
  installPluginForHome?: CodexHomeInstaller
}

export class CodexPluginAdapter implements PluginAdapter {
  readonly cli = "codex" as const
  private readonly marketplacePath: string
  private readonly installPlugin: CodexPluginInstaller
  private readonly installPluginForHome: CodexHomeInstaller

  constructor(opts: CodexPluginAdapterOpts = {}) {
    this.marketplacePath = opts.marketplacePath ?? agentsMarketplacePath()
    this.installPlugin = opts.installPlugin ?? defaultInstaller
    this.installPluginForHome = opts.installPluginForHome ?? defaultHomeInstaller
  }

  /**
   * Install the codex-active plugins into a specific session's CODEX_HOME so the
   * session's app-server can discover + enable them (skills/list, native
   * invocation). Idempotent; per-plugin failures are swallowed. Returns the
   * plugin ids it attempted, for logging.
   */
  async prepareSessionHome(plugins: Plugin[], codexHome: string): Promise<string[]> {
    const ids: string[] = []
    for (const p of this.globalPlugins(plugins)) {
      const id = codexPluginId(p)
      try {
        await this.installPluginForHome(id, codexHome)
        ids.push(id)
      } catch (err: any) {
        log.warn("codex_plugin_add_home_failed", { plugin: id, codexHome, err: err?.message ?? String(err) })
      }
    }
    return ids
  }

  isCompatible(plugin: Plugin): boolean {
    return existsSync(join(plugin.dir, ".codex-plugin", "plugin.json"))
  }

  /** codex-scoped, enabled, compatible plugins (global view — no per-session filtering). */
  private globalPlugins(plugins: Plugin[]): Plugin[] {
    return plugins.filter((p) => isActiveForCli(p, this.cli) && this.isCompatible(p))
  }

  async prepareGlobal(plugins: Plugin[]): Promise<void> {
    const codexPlugins = this.globalPlugins(plugins)
    const market = buildCodexMarketplace(codexPlugins, marketplaceRootFor(this.marketplacePath))
    mkdirSync(dirname(this.marketplacePath), { recursive: true })
    writeFileSync(this.marketplacePath, JSON.stringify(market, null, 2))
    for (const p of codexPlugins) {
      const id = codexPluginId(p)
      try {
        this.installPlugin(id)
      } catch (err: any) {
        // Idempotent: a plugin already installed (or codex unavailable) must not
        // abort the rest or crash broker boot. Log and continue.
        log.warn("codex_plugin_add_failed", { plugin: id, err: err?.message ?? String(err) })
      }
    }
  }

  spawnArgs(plugins: Plugin[], session: PluginSession): SpawnArgs {
    const args: string[] = []
    for (const p of plugins) {
      if (!isActiveForCli(p, this.cli, session.name)) continue
      if (!this.isCompatible(p)) continue
      args.push("-c", `plugins."${codexPluginId(p)}@${CODEX_MARKETPLACE_NAME}".enabled=true`)
    }
    return { args, env: {} }
  }
}
