import type { AgentAdapter } from "./types"
import type { SessionBackend } from "../runtime/session-backend"

/** Shared types for the per-agent session modules (spawn/resume/applyConfig
 * dialects).
 *
 * The layer rule: DIALECT (how to rebuild an agent's adapter + child process)
 * lives in `src/core/agents/<kind>/session.ts`; STATE (runtime registration +
 * adapter event wiring) stays in the SessionManager component. `ResumeCtx` is
 * the dialect's narrow view of the component. */
export type ResumeCtx = {
  /** Resolved CLI effort for a session (model cache lives in main.ts). */
  sessionEffort(s: { agent?: string; model?: string; reasoningLevel?: string }): string | undefined
  resolveAttachment(file_id: string): Promise<string>
  /** Persist the agent-native session id onto this session's row. */
  persistAgentSessionId(sid: string): void
  /** Optional Grok core host (tests inject a fake; production uses the process provider). */
  grokHost?: import("./grok/core-host").GrokCoreHost
  /** Optional Codex core host (tests inject a fake; production uses the process provider). */
  codexHost?: import("./codex/core-host").CodexCoreHost
  /** Optional OpenCode core host (tests inject a fake; production uses the process provider). */
  opencodeHost?: import("./opencode/core-host").OpenCodeCoreHost
  /** Optional Cursor core host (tests inject a fake; production uses the process provider). */
  cursorHost?: import("./cursor/core-host").CursorCoreHost
  claudeHost?: import("./claude/core-host").ClaudeCoreHost
}

/** Input for the optional `commandContext` leaf: build the opaque per-kind
 * slash-command discovery context (`ProviderCtx.agentContext`). The leaf and
 * its provider share the context shape; the service passes it through blind. */
export type CommandContextCtx = {
  sessionName: string
  /** The session's own live adapter, when the session is spawned. */
  adapter?: unknown
  /** Live same-kind adapters (launcher preview: the probe has no session of its own). */
  kindAdapters?: () => unknown[]
}

/** The session-row slice a resume dialect reads (superset across kinds). */
export type ResumeRow = {
  id: string
  workdir: string
  agent_home: string
  model?: string
  agent_session_id?: string
  agent?: string
  reasoningLevel?: string
  prompts?: boolean
  role?: string
}

/** The session-row slice an applyConfig dialect reads. `agent_home` is
 *  optional here — only restart-style kinds (codex) require it, and the
 *  SessionManager checks it before dispatching to them. */
export type ApplyConfigRow = Omit<ResumeRow, "agent_home"> & { agent_home?: string }

/** `ResumeCtx` plus the live-session handles an applyConfig dialect may need.
 *  Which member a dialect reads is part of its dialect: claude types into the
 *  TUI window; cursor/opencode/grok mutate the live adapter. */
export type ApplyConfigCtx = ResumeCtx & {
  /** claude: resolved tmux window id of the live TUI (the component heals/resolves it). */
  windowId?: string
  /** claude: persistent-terminal backend the type-in goes through. */
  backend?: SessionBackend
  /** cursor/opencode/grok/codex: the session's live adapter. */
  adapter?: AgentAdapter
}

/** What the user asked for. `model`/`effort` are the DESIRED values (already
 *  persisted to the registry by the component); `changed` narrows to what the
 *  user actually touched (false = do not re-apply that half). */
export type ApplyConfigChange = {
  model?: string
  effort?: string
  changed?: { model: boolean; effort: boolean }
}

/** Dialect outcome. Core-backed kinds (grok, codex) apply model/effort live through
 *  Session.configure and report a typed `busy` when the native session is mid-turn so
 *  the SessionManager can queue the change until idle instead of killing the turn.
 *  `runtime` is a legacy field for restart-style dialects; no production kind returns it. */
export type ApplyConfigResult =
  | { ok: true; runtime?: { adapter: AgentAdapter; handle?: unknown } }
  | { ok: false; busy: true }
  | { ok: false; error: string }
