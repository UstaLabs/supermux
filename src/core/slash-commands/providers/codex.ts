import { AgentKind } from "../../../shared/agents"
import { agentCommand, type AgentCommandProvider, type ProviderCtx, type SlashCommand } from "../types"

interface CodexSkill { name: string; description?: string; enabled?: boolean }
interface CodexSkillsList { data?: { skills?: CodexSkill[] }[] }

// Codex skills are invoked with `$name`, not `/name` (verified against the
// codex app-server docs + a live skills/list call 2026-05-30).
export function mapCodexSkills(res: CodexSkillsList): SlashCommand[] {
  const out: SlashCommand[] = []
  for (const group of res.data ?? []) {
    for (const s of group.skills ?? []) {
      if (s.enabled === false) continue
      out.push(agentCommand({ name: s.name, sigil: "$", description: s.description }))
    }
  }
  return out
}

// Calls `skills/list` on the live codex app-server JSON-RPC connection the
// broker already holds for the session — no extra process.
export class CodexCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.Codex
  async list(ctx: ProviderCtx): Promise<SlashCommand[]> {
    if (!ctx.codexClient) return []
    try {
      const res = await ctx.codexClient.request<CodexSkillsList>("skills/list", {})
      return mapCodexSkills(res)
    } catch {
      return []
    }
  }
}
