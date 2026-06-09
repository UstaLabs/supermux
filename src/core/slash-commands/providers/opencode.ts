import { existsSync, readdirSync, readFileSync } from "fs"
import { join } from "path"
import { AgentKind } from "../../../shared/agents"
import {
  agentCommand,
  type AgentCommandProvider,
  type OpenCodeCommandEntry,
  type ProviderCtx,
  type SlashCommand,
} from "../types"

export type { OpenCodeCommandEntry }

const BUILTIN_FALLBACK = new Set(["init", "review"])

/** Keep skill-sourced commands; when `source` is absent exclude built-in fallbacks. */
export function mapOpenCodeSkills(cmds: OpenCodeCommandEntry[]): SlashCommand[] {
  return cmds
    .filter((c) => (c.source !== undefined ? c.source === "skill" : !BUILTIN_FALLBACK.has(c.name)))
    .map((c) => agentCommand({ name: c.name, sigil: "/", description: c.description }))
}

function skillNameFromSkillMd(skillMd: string, fallback: string): string {
  try {
    const content = readFileSync(skillMd, "utf8")
    const m = content.match(/^---\r?\n([\s\S]*?)\r?\n---/)
    if (m) {
      const nameLine = m[1]!.match(/^name:\s*(.+)$/m)
      if (nameLine) return nameLine[1]!.trim()
    }
  } catch {}
  return fallback
}

/**
 * Read-only disk scan of enabled plugin trees for launcher preview (no live
 * `opencode serve`). Scans each plugin dir's `skills/<name>/SKILL.md` frontmatter.
 */
export function scanOpenCodeSkillsFromDisk(pluginDirs: string[]): SlashCommand[] {
  const names = new Set<string>()
  const out: SlashCommand[] = []
  const add = (name: string) => {
    if (!name || names.has(name)) return
    names.add(name)
    out.push(agentCommand({ name, sigil: "/" }))
  }
  for (const pluginDir of pluginDirs) {
    const skillsDir = join(pluginDir, "skills")
    if (!existsSync(skillsDir)) continue
    for (const entry of readdirSync(skillsDir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue
      const skillMd = join(skillsDir, entry.name, "SKILL.md")
      if (!existsSync(skillMd)) continue
      add(skillNameFromSkillMd(skillMd, entry.name))
    }
  }
  return out
}

// OpenCode skills are text-insert only — the user submits `/name ` as a normal
// prompt; the broker does not route `session.command`.
export class OpenCodeCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.OpenCode

  async list(ctx: ProviderCtx): Promise<SlashCommand[]> {
    if (ctx.opencodeClient) {
      try {
        const cmds = await ctx.opencodeClient.listCommands(ctx.workdir)
        return mapOpenCodeSkills(cmds)
      } catch {
        // fall through to disk scan
      }
    }
    return scanOpenCodeSkillsFromDisk(ctx.opencodePluginDirs ?? [])
  }
}
