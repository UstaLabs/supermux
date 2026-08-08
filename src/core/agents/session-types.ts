/** Shared types for the per-agent session modules (spawn/resume dialects).
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
}
