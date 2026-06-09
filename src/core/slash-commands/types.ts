import type { AgentKind } from "../agents/types"

/** Minimal JSON-RPC surface the Codex provider needs (structural; satisfied by the codex app-server client). */
export interface CodexRpc {
  request<T = any>(method: string, params: any): Promise<T>
}

export interface OpenCodeCommandEntry {
  name: string
  description?: string
  source?: string
}

/** Minimal OpenCode client slice for slash-command discovery. */
export interface OpenCodeCommandClient {
  listCommands(workdir: string): Promise<OpenCodeCommandEntry[]>
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

/** Minimal session view a provider needs. */
export interface ProviderCtx {
  sessionName: string
  workdir: string
  /** Spawn flags that mirror the real session (e.g. --plugin-dir pairs) so the probe's list matches. */
  pluginSpawnArgs: string[]
  /** Live codex app-server connection, when the session is codex. */
  codexClient?: CodexRpc
  /** Live opencode serve client, when the session is opencode. */
  opencodeClient?: OpenCodeCommandClient
  /** Enabled plugin roots for opencode disk-scan preview / client fallback. */
  opencodePluginDirs?: string[]
}

export interface AgentCommandProvider {
  readonly kind: AgentKind
  list(ctx: ProviderCtx): Promise<SlashCommand[]>
}
