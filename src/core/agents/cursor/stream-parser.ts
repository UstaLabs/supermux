export type CursorStreamEvent =
  | { kind: "init"; session_id: string; cwd?: string; model?: string }
  | { kind: "assistant-message"; text: string }
  | { kind: "tool-call"; phase: "started" | "completed"; call_id: string; tool: string; detail?: unknown }
  | { kind: "result"; is_error: boolean }

export function parseCursorStream(line: string): CursorStreamEvent[] {
  const t = line.trim()
  if (!t) return []
  let msg: any
  try { msg = JSON.parse(t) } catch { return [] }
  if (msg.type === "system" && msg.subtype === "init") {
    // Drop init events that lack session_id rather than corrupt the
    // registry with `agent_session_id: undefined` (which would later be
    // passed as `--resume undefined` and produce an unhelpful failure).
    if (typeof msg.session_id !== "string" || msg.session_id.length === 0) return []
    return [{ kind: "init", session_id: msg.session_id, cwd: msg.cwd, model: msg.model }]
  }
  if (msg.type === "assistant") {
    // cursor-agent normally emits content as a single-element array of text.
    // If a tool_use block precedes text (rare today, possible in future
    // versions), scan all content items for the first text string so we
    // don't silently swallow the reply.
    const blocks: any[] = msg?.message?.content ?? []
    for (const b of blocks) {
      if (typeof b?.text === "string") return [{ kind: "assistant-message", text: b.text }]
    }
    return []
  }
  if (msg.type === "tool_call") {
    // Only the started shape carries `tool_call.<name>`; the completed shape
    // ships `result` only. Consumers can correlate completed→started via
    // call_id when the tool name matters.
    const phase = msg.subtype === "completed" ? "completed" : "started"
    const tool = Object.keys(msg.tool_call ?? {})[0] ?? "unknown"
    return [{ kind: "tool-call", phase, call_id: String(msg.call_id ?? ""), tool, detail: msg }]
  }
  if (msg.type === "result") {
    return [{ kind: "result", is_error: !!msg.is_error }]
  }
  // Other event kinds (user echo, partial deltas, etc.) are intentionally
  // ignored — the adapter cares only about init/assistant/tool/result.
  return []
}
