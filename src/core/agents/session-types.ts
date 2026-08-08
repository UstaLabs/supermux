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
  /** cursor/opencode/grok: the session's live adapter. */
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

/** Dialect outcome. Restart-style kinds (codex) return the freshly built
 *  runtime — the SessionManager swaps it in and rewires events (state half),
 *  so callers never hold a half-dead adapter. */
export type ApplyConfigResult =
  | { ok: true; runtime?: { adapter: AgentAdapter; handle?: unknown } }
  | { ok: false; error: string }
