// src/core/session-manager/activity-store.ts
import { EventEmitter } from "events"
import type { ActivityEvent } from "../agents/claude/activity-event"

// Per-session bounded ring buffer of live activity. Ephemeral: in-memory only,
// not persisted, dropped on session exit / broker restart.
export class ActivityStore extends EventEmitter {
  private readonly cap: number
  private readonly bySession = new Map<string, ActivityEvent[]>()
  private seq = 0

  constructor(cap = 500) {
    super()
    this.cap = cap
  }

  append(sessionId: string, event: ActivityEvent): void {
    event.seq = this.seq++
    const list = this.bySession.get(sessionId) ?? []
    list.push(event)
    if (list.length > this.cap) list.splice(0, list.length - this.cap)
    this.bySession.set(sessionId, list)
    this.emit("append", sessionId, event)
  }

  get(sessionId: string): ActivityEvent[] {
    return this.bySession.get(sessionId)?.slice() ?? []
  }

  clear(sessionId: string): void {
    this.bySession.delete(sessionId)
  }
}
