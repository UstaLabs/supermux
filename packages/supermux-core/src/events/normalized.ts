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
        options?: { id: string; label: string }[]
        allowFreeText?: boolean
        secret?: boolean
      }[]
    }
  | {
      kind: "permission-request"
      requestId: string
      toolCall?: unknown
      options: { optionId: string; kind?: string; label?: string }[]
    }
  | { kind: "commands-update"; commands: { name: string; description?: string }[] }
  | { kind: "mode-update"; modeId?: string; model?: string }
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
