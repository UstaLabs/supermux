// src/core/agents/claude/activity-event.ts
// Agent-agnostic activity shape for all adapters + Claude transcript.
import type { ActivityToolBody } from "../activity-body"

export type { ActivityToolBody }

export interface ActivityEvent {
  ts: string
  kind: "thinking" | "tool" | "tool_result" | "interrupt"
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
}
