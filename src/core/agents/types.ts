import type { EventEmitter } from "events"
import type { AgentKind as SharedAgentKind } from "../../shared/agents"
import type { ActivityEvent } from "./claude/activity-event"
import type { NormalizedBody } from "../../../packages/supermux-core/src/events/normalized.js"

export { AgentKind } from "../../shared/agents"

// An adapter says WHAT to send, never WHERE. The destination is the broker's:
// core/routing/reply-target resolves it from the chat the session last heard
// from. Do not add a chat_id here.
export type AssistantMessageEvent = {
  kind: "assistant-message"
  text: string
  // Channel-specific extras — only the Claude path supplies these via the
  // shim's reply() arguments. Codex/Cursor stream-derived events omit them.
  reply_to?: string
  files?: string[]
  format?: "text" | "markdownv2"
  keyboard?: string[]
}

export type ToolCallEvent = {
  kind: "tool-call"
  tool: string
  phase: "started" | "completed" | "failed"
  call_id: string
  detail?: unknown
}

export type TurnStartEvent    = { kind: "turn-start" }
export type TurnCompleteEvent = { kind: "turn-complete" }
// errorType is the agent's own classification (e.g. Claude's StopFailure error_type);
// omitted by stream-derived adapters, which fall back to a generic "error".
export type AgentErrorEvent   = { kind: "error"; error: Error; errorType?: string }

export type ActivityCardsEvent = { kind: "activity"; events: ActivityEvent[] }

export type BrokerRequestOption = { id: string; label: string; kind?: string }

/** Which subagent is asking (absent for the parent's own requests). */
export type RequestSubagent = {
  subagentId?: string
  /** subagent_type / nickname, when the agent reported one. */
  subagentName?: string
  /** Short human label ("Inspect work dir"), when known. */
  subagentDescription?: string
}

export type BrokerRequest = {
  requestId: string
  kind: "permission" | "question"
  title: string
  body: string
  options: BrokerRequestOption[]
  allowFreeText: boolean
  blocking: boolean
} & RequestSubagent

export type RequestOpenEvent = {
  kind: "request-open"
  requestId: string
  requestKind: "permission" | "question"
  title: string
  body: string
  options: BrokerRequestOption[]
  allowFreeText: boolean
  blocking: boolean
} & RequestSubagent

export type RequestClosedEvent = {
  kind: "request-closed"
  requestId: string
  outcome: "answered" | "expired" | "cancelled"
  /** What was chosen, already human-readable ("Allow always", "Reject — too risky"). */
  answerLabel?: string
}

export type RequestAnswerInput =
  | { optionId: string; message?: string }
  | { answers: Record<string, string | string[]> }
  | { decline: true }

/** A normalized subagent lifecycle body (started/progress/terminal/resumed), envelope stripped. */
export type SubagentEvent = { kind: "subagent"; body: Extract<NormalizedBody, { kind: "subagent" }> }

/** A subagent's own tool call started: the one-line activity to show when the agent sends none. */
export type SubagentActivityEvent = { kind: "subagent-activity"; subagentId: string; activity: string }

/** Background shell / workflow / monitor tasks (Claude background Bash, …). */
export type TaskEvent = {
  kind: "task"
  taskId: string
  taskKind: Extract<NormalizedBody, { kind: "task" }>["taskKind"]
  phase: Extract<NormalizedBody, { kind: "task" }>["phase"]
  label?: string
  parentCallId?: string
}

export type AgentEvent =
  | AssistantMessageEvent
  | ToolCallEvent
  | TurnStartEvent
  | TurnCompleteEvent
  | AgentErrorEvent
  | ActivityCardsEvent
  | RequestOpenEvent
  | RequestClosedEvent
  | SubagentEvent
  | SubagentActivityEvent
  | TaskEvent

export type InboundMeta = {
  chat_id?: string
  message_id?: string
  user?: string
  user_id?: string
  ts?: string
  attachment_kind?: string
  attachment_file_id?: string
  attachment_size?: string
  attachment_mime?: string
  attachment_name?: string
  system_generated?: string
}

export interface AgentAdapter extends EventEmitter {
  readonly kind: SharedAgentKind
  readonly sessionName: string
  readonly workdir: string

  start(): Promise<void>
  resume(): Promise<void>
  stop(): Promise<void>

  send(text: string, meta?: InboundMeta): Promise<void>
  interrupt(): Promise<void>
  respondRequest?(requestId: string, answer: RequestAnswerInput): Promise<void>
  openRequests?(): BrokerRequest[]
  /**
   * Send the user's text to one subagent. `direct`: delivered to the child now. `relay`: queued
   * as a parent turn asking the parent model to forward it. Throws `unsupported_operation` when
   * the runtime cannot reach subagents.
   */
  messageSubagent?(subagentId: string, text: string): Promise<{ via: "direct" | "relay" }>
  /** Stop one subagent without interrupting the parent's turn. */
  stopSubagent?(subagentId: string): Promise<void>
}
