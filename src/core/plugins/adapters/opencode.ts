import { existsSync, readdirSync } from "fs"
import { join } from "path"
import { isActiveForCli, type Plugin, type PluginAdapter, type PluginSession, type SpawnArgs } from "../types"

export interface OpenCodeConfigEntries {
  pluginPaths: string[]
  skillsPaths: string[]
}

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
  return readdirSync(dir)
    .filter((f) => f.endsWith(".js"))
    .map((f) => join(dir, f))
}

// OpenCode discovers plugins via session `opencode.json`:
//   - `plugin: [abs paths]` for trees with `.opencode/plugins/*.js`
//   - `skills.paths` for skills-only plugins (no JS plugin)
// See the opencode skills+slash design spec.
export class OpenCodePluginAdapter implements PluginAdapter {
  readonly cli = "opencode" as const

  isCompatible(plugin: Plugin): boolean {
    return listOpenCodePluginJs(plugin.dir).length > 0 || hasSkillTrees(plugin.dir)
  }

  async prepareGlobal(_plugins: Plugin[]): Promise<void> {
    // Discovery is per-session via opencode.json; nothing to prepare globally.
  }

  configEntries(plugins: Plugin[], session: PluginSession): OpenCodeConfigEntries {
    const pluginPaths: string[] = []
    const skillsPaths: string[] = []
    for (const p of plugins) {
      if (!isActiveForCli(p, this.cli, session.name)) continue
      if (!this.isCompatible(p)) continue
      // opencode.json `plugin` entries are plugin ROOT dirs (it discovers
      // `.opencode/plugins/*.js` inside), not individual .js paths.
      if (listOpenCodePluginJs(p.dir).length > 0) {
        pluginPaths.push(p.dir)
      } else if (hasSkillTrees(p.dir)) {
        skillsPaths.push(join(p.dir, "skills"))
      }
    }
    return { pluginPaths, skillsPaths }
  }

  spawnArgs(_plugins: Plugin[], _session: PluginSession): SpawnArgs {
    return { args: [], env: {} }
  }
}
