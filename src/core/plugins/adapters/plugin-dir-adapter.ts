import { existsSync } from "fs"
import { join } from "path"
import { isActiveForCli, type CliScope, type Plugin, type PluginAdapter, type PluginSession, type SpawnArgs } from "../types"

// Shared base for CLIs that discover plugins via a repeatable `--plugin-dir`
// flag (Claude and Cursor — both smoke-tested 2026-05-30). They differ only in
// the manifest file they require and their `cli` tag. No persistent global
// state, so prepareGlobal is a no-op.
export abstract class PluginDirAdapter implements PluginAdapter {
  abstract readonly cli: CliScope
  /** Manifest the CLI needs, relative to the plugin root (e.g. ".claude-plugin/plugin.json"). */
  protected abstract readonly manifestPath: string

  isCompatible(plugin: Plugin): boolean {
    return existsSync(join(plugin.dir, this.manifestPath))
  }

  async prepareGlobal(_plugins: Plugin[]): Promise<void> {
    // Discovery is per-spawn via --plugin-dir; nothing to prepare globally.
  }

  spawnArgs(plugins: Plugin[], session: PluginSession): SpawnArgs {
    const args: string[] = []
    for (const p of plugins) {
      if (!isActiveForCli(p, this.cli, session.name)) continue
      if (!this.isCompatible(p)) continue
      args.push("--plugin-dir", p.dir)
    }
    return { args, env: {} }
  }
}
