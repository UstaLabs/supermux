import { AgentKind } from "../../../shared/agents"
import { agentCommand, type AgentCommandEntry, type AgentCommandList, type AgentCommandProvider, type ProviderCtx, type ReportedCommandsCtx, type SlashCommand } from "../types"

/** Claude shows MCP prompts as "server:prompt (MCP)"; the command itself is "server:prompt". */
const MCP_SUFFIX = / \(MCP\)$/
/** Claude ends the description of a folder's own commands and skills with "(project)". */
const PROJECT_SUFFIX = /\(project\)\s*$/

/** Claude's own list (initialize reply, commands_changed push) as composer entries. */
export function mapClaudeCommands(entries: AgentCommandEntry[]): AgentCommandList {
  const commands: SlashCommand[] = []
  const projectIds: string[] = []
  const seen = new Set<string>()
  for (const entry of entries) {
    // "__remote-workflow" and the like are Claude's own plumbing.
    if (!entry.name || entry.name.startsWith("_")) continue
    const cmd = agentCommand({ name: entry.name.replace(MCP_SUFFIX, ""), sigil: "/", description: entry.description })
    if (seen.has(cmd.id)) continue
    seen.add(cmd.id)
    commands.push(cmd)
    if (entry.description && PROJECT_SUFFIX.test(entry.description)) projectIds.push(cmd.id)
  }
  return { commands, projectIds }
}

// Reads the list the session's own Claude reported over the stream-json connection Core holds (its initialize
// reply, then commands_changed pushes). Nothing is spawned and nothing is prompted: the old `claude -p "hi"`
// probe reached a billed Opus turn in 544 of 548 runs (card #13).
export class ClaudeCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.Claude

  async list(ctx: ProviderCtx): Promise<AgentCommandList | undefined> {
    const reported = (ctx.agentContext as ReportedCommandsCtx | undefined)?.commands
    return reported ? mapClaudeCommands(reported) : undefined
  }
}
