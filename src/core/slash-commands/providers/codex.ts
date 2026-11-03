import { AgentKind } from "../../../shared/agents"
import { agentCommand, type AgentCommandList, type AgentCommandProvider, type CodexRpc, type ProviderCtx, type SlashCommand } from "../types"

interface CodexSkill { name: string; description?: string; enabled?: boolean; scope?: string }
interface CodexSkillsList { data?: { skills?: CodexSkill[] }[] }

// Codex skills are invoked with `$name`, not `/name` (verified against the
// codex app-server docs + a live skills/list call 2026-05-30). Scope "repo" is
// the folder's own .agents/.codex skills (0.164.0).
export function mapCodexSkills(res: CodexSkillsList): AgentCommandList {
  const commands: SlashCommand[] = []
  const projectIds: string[] = []
  for (const group of res.data ?? []) {
    for (const s of group.skills ?? []) {
      if (s.enabled === false) continue
      const cmd = agentCommand({ name: s.name, sigil: "$", description: s.description })
      commands.push(cmd)
      if (s.scope === "repo") projectIds.push(cmd.id)
    }
  }
  return { commands, projectIds }
}

// Calls `skills/list` for the session's folder on a live codex app-server the
// broker already holds — no extra process, no thread, no turn. Codex announces
// `skills/changed` but it never fired in tests (0.164.0), so the broker also
// asks again after every turn.
export class CodexCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.Codex
  async list(ctx: ProviderCtx): Promise<AgentCommandList | undefined> {
    // agentContext is codex's commandContext leaf output: a live app-server client.
    const client = ctx.agentContext as CodexRpc | undefined
    if (!client) return undefined
    try {
      return mapCodexSkills(await client.request<CodexSkillsList>("skills/list", { cwds: [ctx.workdir] }))
    } catch {
      return undefined
    }
  }
}
