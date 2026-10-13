import { AgentKind } from "../../../shared/agents"
import type { AgentCommandProvider, ProviderCtx, SlashCommand } from "../types"
import { scanGrokSkillsFromDisk } from "./grok"

// agy skills are text-insert only: the user submits `/name ` as a normal prompt and agy runs the
// skill itself. agy has no live command list over stream-json, so the provider always scans the
// plugin skills dirs (`<dir>/<name>/SKILL.md`, names from frontmatter) handed in via agentContext.
export class AntigravityCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.Antigravity

  async list(ctx: ProviderCtx): Promise<SlashCommand[]> {
    const a = ctx.agentContext as { skillsDirs?: string[] } | undefined
    return scanGrokSkillsFromDisk(a?.skillsDirs ?? [])
  }
}
