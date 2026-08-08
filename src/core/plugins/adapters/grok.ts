import { join } from "path"
import { isActiveForCli, type Plugin, type PluginAdapter, type PluginSession, type SpawnArgs } from "../types"
import { hasSkillTrees } from "./opencode"

export interface GrokConfigEntries {
  skillsPaths: string[]
}

// Grok discovers skills from extra directories declared in its config.toml:
//   [skills]
//   paths = ["<dir>", …]        # each dir holds <name>/SKILL.md trees
// The broker writes a session-private ~/.grok/config.toml per session (HOME is
// redirected there — see agents/grok/config-writer.ts), so propagation is a
// config entry, exactly like opencode's `skills.paths`. Live-verified against
// grok 0.2.101 (2026-08-08): a path listed there surfaces the skill in the ACP
// `available_commands_update` push and `/name` in a prompt injects the SKILL.md.
export class GrokPluginAdapter implements PluginAdapter {
  readonly cli = "grok" as const

  isCompatible(plugin: Plugin): boolean {
    return hasSkillTrees(plugin.dir)
  }

  async prepareGlobal(_plugins: Plugin[]): Promise<void> {
    // Discovery is per-session via the private config.toml; nothing global.
  }

  configEntries(plugins: Plugin[], session: PluginSession): GrokConfigEntries {
    const skillsPaths: string[] = []
    for (const p of plugins) {
      if (!isActiveForCli(p, this.cli, session.name)) continue
      if (!this.isCompatible(p)) continue
      skillsPaths.push(join(p.dir, "skills"))
    }
    return { skillsPaths }
  }

  spawnArgs(_plugins: Plugin[], _session: PluginSession): SpawnArgs {
    return { args: [], env: {} }
  }
}
