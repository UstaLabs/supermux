import { existsSync, readdirSync, readFileSync } from "fs"
import { join } from "path"
import { AgentKind } from "../../../shared/agents"
import { agentCommand, type AgentCommandProvider, type GrokAcpCommand, type ProviderCtx, type SlashCommand } from "../types"

export type { GrokAcpCommand }

/**
 * Keep only skill-backed entries: those whose `_meta.path` points at a
 * SKILL.md. Grok's list also carries TUI built-ins (compact, always-approve,
 * context, …) with no `_meta` — those are client-side features of grok's own
 * UI; sent as prompt text through the broker they would just be words. A
 * `/skill-name` prompt, by contrast, injects the SKILL.md server-side
 * (live-verified against grok 0.2.101).
 */
export function mapGrokCommands(cmds: GrokAcpCommand[]): SlashCommand[] {
  return cmds
    .filter((c) => typeof c._meta?.path === "string" && c._meta.path.endsWith("SKILL.md"))
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
 * Read-only disk scan of the plugins' skills dirs (`<dir>/<name>/SKILL.md`)
 * for launcher preview, before any grok child runs. Grok resolves a skill's
 * name from its frontmatter, so the scan does too.
 */
export function scanGrokSkillsFromDisk(skillsDirs: string[]): SlashCommand[] {
  const names = new Set<string>()
  const out: SlashCommand[] = []
  for (const dir of skillsDirs) {
    if (!existsSync(dir)) continue
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue
      const skillMd = join(dir, entry.name, "SKILL.md")
      if (!existsSync(skillMd)) continue
      const name = skillNameFromSkillMd(skillMd, entry.name)
      if (!name || names.has(name)) continue
      names.add(name)
      out.push(agentCommand({ name, sigil: "/" }))
    }
  }
  return out
}

// Grok skills are text-insert only — the user submits `/name ` as a normal
// prompt and grok injects the SKILL.md server-side. The provider prefers the
// adapter's live ACP list (fed in via ctx.grokCommands; refreshed on every
// available_commands_update); with no live adapter (launcher preview) it
// falls back to a read-only disk scan of the plugin skills dirs.
export class GrokCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.Grok

  async list(ctx: ProviderCtx): Promise<SlashCommand[]> {
    if (ctx.grokCommands?.length) return mapGrokCommands(ctx.grokCommands)
    return scanGrokSkillsFromDisk(ctx.grokSkillsDirs ?? [])
  }
}
