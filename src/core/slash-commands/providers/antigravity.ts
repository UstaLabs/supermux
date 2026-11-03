import { AgentKind } from "../../../shared/agents"
import type { AgentCommandList, AgentCommandProvider, ProviderCtx } from "../types"
import { scanSkillDirs } from "./skill-dirs"

// agy skills are text-insert only: the user submits `/name ` as a normal prompt and agy runs the
// skill itself. agy reports no command list (1.4.12: its stream-json init frame lists tools only and
// its input takes only user turns), so the provider scans the plugin skills dirs (`<dir>/<name>/SKILL.md`,
// names from frontmatter) handed in via agentContext. None of them depends on the folder.
export class AntigravityCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.Antigravity

  async list(ctx: ProviderCtx): Promise<AgentCommandList | undefined> {
    const a = ctx.agentContext as { skillsDirs?: string[] } | undefined
    return { commands: scanSkillDirs(a?.skillsDirs ?? []), projectIds: [] }
  }
}
