export type NativeProtocol =
  | "codex-app-server"
  | "acp"
  | "core"

export type EventOrigin = "live" | "replay"

export type ToolCallPhase = "started" | "updated" | "completed" | "failed"
export type ToolCategory =
  | "read"
  | "edit"
  | "delete"
  | "move"
  | "search"
  | "execute"
  | "fetch"
  | "mcp"
  | "web-search"
  | "think"
  | "other"

export type TurnCompleteReason = "ok" | "interrupt" | "error" | "stall"

export type PlanEntryStatus = "pending" | "in_progress" | "completed"
export type PlanEntryPriority = "high" | "medium" | "low"

export type TaskKind = "shell" | "agent" | "workflow" | "monitor" | "subagent" | "collab"
export type TaskPhase = "started" | "interacted" | "interrupted" | "completed" | "failed" | "wake"

export type CompactionStatus = "in_progress" | "completed" | "failed" | "cancelled"

export type NativeRef = {
  protocol: NativeProtocol
  method?: string
  payload: unknown
}

export type EventEnvelope = {
  sessionId: string
  agent: string
  seq: number
  ts: string
  turnId?: string
  replay: boolean
  origin: EventOrigin
  native: NativeRef
}

export type NormalizedBody =
  | { kind: "turn-start" }
  | { kind: "turn-complete"; reason: TurnCompleteReason }
  | { kind: "assistant-delta"; messageId: string; text: string }
  | { kind: "assistant-message"; messageId: string; text: string }
  | { kind: "reasoning-delta"; reasoningId: string; text: string }
  | { kind: "reasoning"; reasoningId: string; text?: string; redacted: boolean; summary?: string[] }
  | {
      kind: "tool-call"
      callId: string
      tool: string
      title?: string
      phase: ToolCallPhase
      category?: ToolCategory
      locations?: { path: string }[]
      input?: unknown
      output?: unknown
      exitCode?: number
      /** Agent-provided "why" label (Bash description, Grok rawInput.description, …). */
      description?: string
    }
  | { kind: "command-output"; callId: string; stream: "stdout" | "stderr" | "merged"; delta: string }
  | { kind: "file-diff"; callId?: string; path: string; diff: string; changeKind?: string }
  | { kind: "web-search"; callId: string; phase: "started" | "completed" | "failed"; query?: string; results?: unknown }
  | {
      kind: "mcp-tool"
      callId: string
      server: string
      tool: string
      phase: "started" | "progress" | "completed" | "failed"
      arguments?: unknown
      result?: unknown
    }
  | {
      kind: "plan"
      entries: { content: string; status: PlanEntryStatus; priority?: PlanEntryPriority }[]
      explanation?: string
    }
  | {
      kind: "task"
      taskId: string
      taskKind: TaskKind
      phase: TaskPhase
      label?: string
      parentCallId?: string
    }
  | {
      kind: "user-question"
      requestId: string
      blocking: boolean
      questions: {
        id: string
        prompt: string
        header?: string
        multiSelect: boolean
        allowFreeText: boolean
        options: { id: string; label: string; description?: string }[]
      }[]
    }
  | {
      kind: "permission-request"
      requestId: string
      toolCall: {
        callId: string
        tool: string
        title: string
        input?: unknown
        category?: ToolCategory
      }
      options: {
        id: string
        kind: "allow_once" | "allow_always" | "reject_once" | "reject_always"
        label: string
      }[]
      detail?: { command?: string; cwd?: string; blockedPath?: string }
    }
  | {
      kind: "request-resolved"
      requestId: string
      outcome: "answered" | "expired" | "cancelled"
      /**
       * What the user actually chose. Present only for `outcome: "answered"` — an expired or
       * cancelled request was never answered, so there is nothing truthful to put here.
       * Question answers are the OPTION LABELS (or the free text), the same values handed to
       * the agent; a permission answer keeps the option id, which only the request's own
       * option list can turn into a label. Shaped like `RequestAnswer` in ../types.ts, spelled
       * out here so the event vocabulary does not depend on the API surface.
       */
      answer?:
        | { optionId: string; message?: string }
        | { answers: Record<string, string | string[]> }
        | { decline: true }
    }
  | { kind: "commands-update"; commands: { name: string; description?: string }[] }
  | { kind: "mode-update"; modeId?: string; model?: string }
  | { kind: "permissions-update"; spec: import("../types.js").PermissionsSpec; applied: "now" | "next-turn" }
  | {
      kind: "permission-auto"
      toolCall: { callId: string; tool: string; title: string; input?: unknown; category?: ToolCategory }
      optionId: string
    }
  | { kind: "session-info"; title?: string | null; cwd?: string; model?: string }
  | {
      kind: "usage"
      context?: { used: number; size: number }
      tokens?: { input: number; output: number; total: number }
      cost?: { amount: number; currency: string }
      rateLimits?: unknown
    }
  | { kind: "compaction"; compactionId: string; status: CompactionStatus; summary?: string; error?: string }
  | { kind: "warning"; message: string; source?: string }
  | { kind: "error"; message: string; errorType?: string; recoverable?: boolean }

export type NormalizedEvent = EventEnvelope & { event: NormalizedBody }

export type CoreNormalizedEvent = {
  type: "session.event"
  sessionId: string
  event: EventEnvelope & NormalizedBody
}
