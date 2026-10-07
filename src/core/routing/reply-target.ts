/**
 * Where does a session's reply go?
 *
 * The agent must not answer that question — it has no business knowing which
 * channel the user is on. The broker owns the destination: every inbound turn
 * passes through SessionManager.deliver, which notes the chat it came from
 * here, and every outbound reply reads it back.
 *
 * Two layers:
 *   - ReplyTargets — in-memory, written by THE inbound funnel. Covers turns
 *     that never touch the message log (curator wakes, agent-rpc calls).
 *   - lastInboundChatId — the durable fallback, read off the message log after
 *     a broker restart drops the in-memory map.
 */

/** Only the fields this module needs, so callers can pass MessageStore rows. */
type InboundRow = { direction: "inbound" | "outbound"; chat_id?: string }

export class ReplyTargets {
  private bySession = new Map<string, string>()

  /**
   * Record the chat an inbound turn arrived on. Sticky on purpose: a turn with
   * no chat_id (agent-rpc, curator) leaves the previous destination in place,
   * because a system-generated turn is not the user moving to another chat.
   */
  note(sessionId: string, chat_id: unknown): void {
    if (typeof chat_id !== "string" || chat_id.length === 0) return
    this.bySession.set(sessionId, chat_id)
  }

  get(sessionId: string): string | undefined {
    return this.bySession.get(sessionId)
  }

  forget(sessionId: string): void {
    this.bySession.delete(sessionId)
  }
}

/** The chat of the newest inbound row, or undefined if the session never received one. */
export function lastInboundChatId(entries: InboundRow[]): string | undefined {
  for (let i = entries.length - 1; i >= 0; i--) {
    const e = entries[i]
    if (e?.direction === "inbound" && typeof e.chat_id === "string" && e.chat_id.length > 0) return e.chat_id
  }
  return undefined
}
