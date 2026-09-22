import type { EventEmitter } from "events"
import type { AgentKind as SharedAgentKind } from "../../shared/agents"
import type { ActivityEvent } from "./claude/activity-event"

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

export type BrokerRequest = {
  requestId: string
  kind: "permission" | "question"
  title: string
  body: string
  options: BrokerRequestOption[]
  allowFreeText: boolean
  blocking: boolean
}

export type RequestOpenEvent = {
  kind: "request-open"
  requestId: string
  requestKind: "permission" | "question"
  title: string
  body: string
  options: BrokerRequestOption[]
  allowFreeText: boolean
  blocking: boolean
}

export type RequestClosedEvent = {
  kind: "request-closed"
  requestId: string
  outcome: "answered" | "expired" | "cancelled"
  answer?: string
}

export type RequestAnswerInput =
  | { optionId: string; message?: string }
  | { answers: Record<string, string | string[]> }
  | { decline: true }

export type AgentEvent =
  | AssistantMessageEvent
  | ToolCallEvent
  | TurnStartEvent
  | TurnCompleteEvent
  | AgentErrorEvent
  | ActivityCardsEvent
  | RequestOpenEvent
  | RequestClosedEvent

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
}
