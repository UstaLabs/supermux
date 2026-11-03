import type { AgentKind } from "../agents/types"

/** Minimal JSON-RPC surface the Codex provider needs (structural; satisfied by the codex app-server client). */
export interface CodexRpc {
  request<T = any>(method: string, params: any): Promise<T>
}

/**
 * One command as an agent reported it: Claude's initialize reply and commands_changed push, an ACP agent's
 * `available_commands_update` (Core's normalized `commands-update`), or Grok's `initialize._meta.availableCommands`.
 * Grok marks skill-backed entries with `_meta.path` → SKILL.md; built-ins have no `_meta`.
 */
export interface AgentCommandEntry {
  name: string
  description?: string
  argumentHint?: string
  builtin?: boolean
  _meta?: Record<string, unknown>
}

/** The discovery context of an agent whose session reports its list itself (Claude, Cursor, OpenCode, Grok). */
export interface ReportedCommandsCtx {
  /** What the session's agent last reported; undefined until it reports. */
  commands?: AgentCommandEntry[]
}

/** The `commandContext` of an agent whose live adapter holds the list its agent reported. */
export function reportedCommands(adapter: unknown): ReportedCommandsCtx {
  return { commands: (adapter as { availableCommands?: AgentCommandEntry[] } | undefined)?.availableCommands }
}

export type CommandFamily = "agent" | "control"

export type ControlAction =
  | { kind: "spawn" }
  | { kind: "model" }
  | { kind: "rename" }
  | { kind: "mute"; muted: boolean }
  | { kind: "stop" }
  | { kind: "kill" }

export interface SlashCommand {
  /** Unique within a session's list, e.g. "agent:superpowers:brainstorming" or "control:kill". */
  id: string
  family: CommandFamily
  /** Name without the leading sigil, e.g. "code-review", "superpowers:brainstorming", "spawn". */
  name: string
  /** Sigil inserted before the name for agent commands. "/" for Claude/Cursor, "$" for Codex skills. */
  sigil: "/" | "$"
  description?: string
  /** agent only: the literal text inserted into the composer. */
  insertText?: string
  /** control only: the web action to dispatch. */
  action?: ControlAction
}

export function agentCommand(opts: { name: string; sigil: "/" | "$"; description?: string }): SlashCommand {
  return {
    id: `agent:${opts.name}`,
    family: "agent",
    name: opts.name,
    sigil: opts.sigil,
    description: opts.description,
    insertText: `${opts.sigil}${opts.name} `,
  }
}

/**
 * What a provider read from an agent: the composer entries plus, when the agent says so, which of them exist
 * only because of the folder they were reported in (project commands and skills).
 */
export interface AgentCommandList {
  commands: SlashCommand[]
  /** Ids of folder-only entries. Absent: the agent does not mark them, so the list can't stand in for another folder. */
  projectIds?: string[]
}

/** Minimal session view a provider needs. */
export interface ProviderCtx {
  sessionName: string
  workdir: string
  /** Opaque per-kind discovery context, built by the agent module's
   * `commandContext` leaf. Each provider casts its own kind's shape. */
  agentContext?: unknown
}

export interface AgentCommandProvider {
  readonly kind: AgentKind
  /** The list the agent reported, mapped for the composer. Undefined while it has reported nothing. */
  list(ctx: ProviderCtx): Promise<AgentCommandList | undefined>
}
