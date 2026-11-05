// Bounded per-session set of recently-delivered inbound message ids, used to make
// inbound delivery idempotent (a WS reconnect re-flushing the same client message,
// or any retry of the same message_id, must not re-fire the turn). FIFO eviction
// past `max` keeps memory bounded; dedup only needs to span a short retry window.
export class RecentInboundIds {
  private readonly bySession = new Map<string, string[]>()
  constructor(private readonly max: number = 200) {}

  has(sessionId: string, messageId: string): boolean {
    return this.bySession.get(sessionId)?.includes(messageId) ?? false
  }

  mark(sessionId: string, messageId: string): void {
    let ids = this.bySession.get(sessionId)
    if (!ids) { ids = []; this.bySession.set(sessionId, ids) }
    if (ids.includes(messageId)) return
    ids.push(messageId)
    if (ids.length > this.max) ids.splice(0, ids.length - this.max)
  }

  /** A hand-over that failed: the id may be delivered again. */
  unmark(sessionId: string, messageId: string): void {
    const ids = this.bySession.get(sessionId)
    const at = ids?.indexOf(messageId) ?? -1
    if (at >= 0) ids!.splice(at, 1)
  }

  clear(sessionId: string): void {
    this.bySession.delete(sessionId)
  }
}
