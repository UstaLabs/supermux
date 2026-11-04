import type { AgentKind } from "../agents/types"

/** Minimal JSON-RPC surface the Codex provider needs (structural; satisfied by the codex app-server client). */
export interface CodexRpc {
  request<T = any>(method: string, params: any): Promise<T>
}

/**
 * One command as an agent reported it (Core's normalized `commands-update` entry): Claude's initialize reply and
 * commands_changed push, an ACP agent's `available_commands_update`, or Grok's `initialize._meta.availableCommands`.
 * Grok marks skill-backed entries with `_meta.path` → SKILL.md; built-ins have no `_meta`.
 */
export type { AgentCommandEntry } from "../../../packages/supermux-core/src/events/normalized.js"
import type { AgentCommandEntry } from "../../../packages/supermux-core/src/events/normalized.js"

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

/**
 * Where an agent command comes from. Set only when the agent says so (Claude's built-in flag, a plugin's
 * "<plugin>:" name prefix, Codex's skill scope, Grok's skill scope) or the folder it was read from does
 * (Antigravity). Nothing is guessed: a command no one vouches for has no source.
 */
export type CommandSource = "builtin" | "plugin" | "project" | "user"

export interface CommandOrigin {
  source: CommandSource
  /** With source "plugin": the plugin's name. */
  plugin?: string
}

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
  /** agent only: the agent's own hint for the arguments, e.g. "[interval] [prompt]". */
  argumentHint?: string
  /** agent only: other names the agent accepts for the command, e.g. "review" for "code-review". */
  aliases?: string[]
  /** agent only: where the command comes from, when something says so (see CommandSource). */
  source?: CommandSource
  /** agent only, with source "plugin": the plugin's name. */
  plugin?: string
  /** control only: the web action to dispatch. */
  action?: ControlAction
}

export function agentCommand(opts: {
  name: string
  sigil: "/" | "$"
  description?: string
  argumentHint?: string
  aliases?: string[]
  origin?: CommandOrigin
}): SlashCommand {
  const aliases = opts.aliases?.filter((a) => a && a !== opts.name)
  return {
    id: `agent:${opts.name}`,
    family: "agent",
    name: opts.name,
    sigil: opts.sigil,
    description: opts.description,
    insertText: `${opts.sigil}${opts.name} `,
    ...(opts.argumentHint ? { argumentHint: opts.argumentHint } : {}),
    ...(aliases?.length ? { aliases } : {}),
    ...(opts.origin ? { source: opts.origin.source } : {}),
    ...(opts.origin?.plugin ? { plugin: opts.origin.plugin } : {}),
  }
}

/** The plugin a namespaced name belongs to: "superpowers:brainstorming" → "superpowers". */
export function pluginOfName(name: string): string | undefined {
  const i = name.indexOf(":")
  return i > 0 ? name.slice(0, i) : undefined
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
