import { AgentKind } from "../../../shared/agents"
import { agentCommand, type AgentCommandEntry, type AgentCommandList, type AgentCommandProvider, type ProviderCtx, type ReportedCommandsCtx } from "../types"

/** Cursor's pushed list (names and descriptions only: it does not say which are the folder's own). */
export function mapCursorCommands(entries: AgentCommandEntry[]): AgentCommandList {
  return { commands: entries.filter((e) => e.name).map((e) => agentCommand({ name: e.name, sigil: "/", description: e.description, argumentHint: e.argumentHint })) }
}

// Cursor speaks ACP and pushes `available_commands_update` (built-ins, the
// folder's commands and skills, the user's skills) a few seconds after the
// session starts; the session's adapter keeps the last push.
export class CursorCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.Cursor

  async list(ctx: ProviderCtx): Promise<AgentCommandList | undefined> {
    const reported = (ctx.agentContext as ReportedCommandsCtx | undefined)?.commands
    return reported ? mapCursorCommands(reported) : undefined
  }
}
