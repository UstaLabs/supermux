// src/core/agents/claude/activity-event.ts
// Agent-agnostic shape so Cursor/Codex tool-call events can map in later.
export interface ActivityEvent {
  ts: string
  kind: "thinking" | "tool" | "tool_result"
  tool?: string
  title: string
  detail?: string
  phase?: "started" | "completed"
  truncated?: boolean
  seq?: number       // monotonic id stamped by ActivityStore on append (for stable client keys)
  callId?: string    // tool_use id (and matching tool_result tool_use_id) for pairing
}
