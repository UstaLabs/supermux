import { AgentKind } from "../../../shared/agents"
import { agentCommand, type AgentCommandEntry, type AgentCommandList, type AgentCommandProvider, type ProviderCtx, type ReportedCommandsCtx } from "../types"

/**
 * OpenCode's pushed list, all of it: its ACP prompt handler (1.18.34, read from the binary) runs
 * `session.command` for any "/name" in this list — init, review, custom commands, skills and MCP prompts.
 * It does not say which entries are the folder's own.
 */
export function mapOpenCodeCommands(entries: AgentCommandEntry[]): AgentCommandList {
  return { commands: entries.filter((e) => e.name).map((e) => agentCommand({ name: e.name, sigil: "/", description: e.description })) }
}

// OpenCode pushes `available_commands_update` over ACP when a session starts;
// the session's adapter keeps the last push.
export class OpenCodeCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.OpenCode

  async list(ctx: ProviderCtx): Promise<AgentCommandList | undefined> {
    const reported = (ctx.agentContext as ReportedCommandsCtx | undefined)?.commands
    return reported ? mapOpenCodeCommands(reported) : undefined
  }
}
