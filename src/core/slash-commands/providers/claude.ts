import { AgentKind } from "../../../shared/agents"
import { agentCommand, pluginOfName, type AgentCommandEntry, type AgentCommandList, type AgentCommandProvider, type CommandOrigin, type ProviderCtx, type ReportedCommandsCtx, type SlashCommand } from "../types"

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
    const mcp = MCP_SUFFIX.test(entry.name)
    const name = entry.name.replace(MCP_SUFFIX, "")
    // A plugin's skills are "<plugin>:<skill>"; an MCP prompt's "<server>:" is no plugin.
    const plugin = mcp ? undefined : pluginOfName(name)
    const origin: CommandOrigin | undefined = entry.builtin ? { source: "builtin" } : plugin ? { source: "plugin", plugin } : undefined
    // Claude opens a plugin skill's description with "(<plugin>) ", which the source now says.
    const description = plugin && entry.description?.startsWith(`(${plugin}) `) ? entry.description.slice(plugin.length + 3) : entry.description
    const cmd = agentCommand({ name, sigil: "/", description, argumentHint: entry.argumentHint, aliases: entry.aliases, origin })
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
