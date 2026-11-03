import { AgentKind } from "../../../shared/agents"
import { agentCommand, type AgentCommandEntry, type AgentCommandList, type AgentCommandProvider, type ProviderCtx, type ReportedCommandsCtx, type SlashCommand } from "../types"

/** Skill scopes that do not depend on the folder (1.0.46: the user's and Grok's bundled skills). */
const GLOBAL_SCOPES = new Set(["user", "bundled"])

/**
 * Keep only skill-backed entries: those whose `_meta.path` points at a
 * SKILL.md. Grok's list also carries TUI built-ins (compact, always-approve,
 * context, …) with no `_meta`, and workflows — client-side features of grok's
 * own UI; sent as prompt text through the broker they would just be words. A
 * `/skill-name` prompt, by contrast, injects the SKILL.md server-side
 * (live-verified against grok 0.2.101).
 */
export function mapGrokCommands(entries: AgentCommandEntry[]): AgentCommandList {
  const commands: SlashCommand[] = []
  const projectIds: string[] = []
  for (const entry of entries) {
    const path = entry._meta?.path
    if (typeof path !== "string" || !path.endsWith("SKILL.md")) continue
    const cmd = agentCommand({ name: entry.name, sigil: "/", description: entry.description })
    commands.push(cmd)
    if (!GLOBAL_SCOPES.has(String(entry._meta?.scope ?? ""))) projectIds.push(cmd.id)
  }
  return { commands, projectIds }
}

// Grok skills are text-insert only — the user submits `/name ` as a normal
// prompt and grok injects the SKILL.md server-side. The list is the one the
// session's grok reported (initialize `_meta.availableCommands`, then every
// `available_commands_update`), kept on its adapter.
export class GrokCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.Grok

  async list(ctx: ProviderCtx): Promise<AgentCommandList | undefined> {
    const reported = (ctx.agentContext as ReportedCommandsCtx | undefined)?.commands
    // The initialize-time list is built-ins only, none with _meta; the session's push marks skills and
    // workflows. Until it arrives the session has reported nothing (an empty map would overwrite the store).
    if (!reported?.some((entry) => entry._meta)) return undefined
    return mapGrokCommands(reported)
  }
}
