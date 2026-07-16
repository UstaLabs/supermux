export type GrokStreamEvent =
  | { kind: "assistant-message"; text: string }
  | { kind: "thought"; text: string }
  | { kind: "tool-call"; phase: "started" | "completed" | "failed"; call_id: string; tool: string; detail?: unknown }

/** One ACP `session/update` params object -> zero or more supermux stream events.
 * Frame shapes captured live from `grok agent stdio` v0.2.99 (2026-07-13). */
export function parseGrokUpdate(params: unknown): GrokStreamEvent[] {
  const p = params as { update?: Record<string, unknown> } | undefined
  const u = p?.update
  if (!u || typeof u !== "object") return []
  const kind = u.sessionUpdate as string | undefined

  if (kind === "agent_message_chunk") {
    const text = textOf(u.content)
    return text ? [{ kind: "assistant-message", text }] : []
  }
  if (kind === "agent_thought_chunk") {
    const text = textOf(u.content)
    return text ? [{ kind: "thought", text }] : []
  }
  if (kind === "tool_call") {
    const call_id = String(u.toolCallId ?? "")
    const tool = typeof u.title === "string" ? u.title : "tool"
    const { sessionUpdate: _s, toolCallId: _t, ...detail } = u
    return [{ kind: "tool-call", phase: "started", call_id, tool, detail }]
  }
  if (kind === "tool_call_update") {
    const call_id = String(u.toolCallId ?? "")
    const status = u.status as string | undefined
    const tool = typeof u.kind === "string" ? u.kind : "tool"
    const phase = status === "failed" ? "failed" : status === "completed" ? "completed" : "started"
    const { sessionUpdate: _s, toolCallId: _t, ...detail } = u
    if (phase === "started") return []
    return [{ kind: "tool-call", phase, call_id, tool, detail }]
  }
  return []
}

function textOf(content: unknown): string {
  const c = content as { type?: string; text?: string } | undefined
  return c && c.type === "text" && typeof c.text === "string" ? c.text : ""
}
