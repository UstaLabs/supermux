// src/core/agents/claude/activity-event.ts
// Agent-agnostic activity shape for all adapters + Claude transcript.
import type { ActivityToolBody } from "../activity-body"

export type { ActivityToolBody }

export interface ActivityEvent {
  ts: string
  kind: "thinking" | "tool" | "tool_result" | "interrupt" | "reasoning" | "plan" | "task" | "subagent_message"
  tool?: string
  title: string
  /** Medium-mode / expand preview (may be truncated). */
  detail?: string
  /**
   * Human "why" label when the agent provides one (Claude Bash `description`,
   * Cursor `args.description`, OpenCode state title, Grok title, …).
   * Independent of title (which stays tool + primary arg).
   */
  description?: string
  phase?: "started" | "completed"
  truncated?: boolean
  seq?: number       // monotonic id stamped by ActivityStore on append (for stable client keys)
  callId?: string    // tool_use id (and matching tool_result tool_use_id) for pairing
  /** Structured payload for High-detail terminal / diff rendering. */
  body?: ActivityToolBody
  /**
   * Set on every row a SUBAGENT produced (its tool calls, results, reasoning), so clients nest
   * it under that subagent instead of the parent's timeline. Parent rows never carry it.
   */
  subagentId?: string
  /**
   * `subagent_message` rows: one message of a subagent's conversation, in order with its tool
   * rows. `from` = the subagent wrote it; `to` = its prompt or a follow-up, `sender` says whose.
   * `text` is the full message (clipped to 8000 chars, then `truncated`); `title` its first line.
   */
  text?: string
  direction?: "to" | "from"
  sender?: "user" | "parent"
}
