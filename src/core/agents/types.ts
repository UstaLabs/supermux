import type { EventEmitter } from "events"
import type { AgentKind as SharedAgentKind } from "../../shared/agents"

export { AgentKind } from "../../shared/agents"

export type AssistantMessageEvent = {
  kind: "assistant-message"
  text: string
  // Channel-specific extras — only the Claude path supplies these via the
  // shim's reply() arguments. Codex/Cursor stream-derived events omit them.
  chat_id?: string
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

export type AgentEvent =
  | AssistantMessageEvent
  | ToolCallEvent
  | TurnStartEvent
  | TurnCompleteEvent
  | AgentErrorEvent

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
}
