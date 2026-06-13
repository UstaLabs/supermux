import type { SessionStore } from "./session-store"
import type { MessageStore } from "./messages"

export type SessionReadFrame = { type: "session_read"; session: string; last_read_at: string }

export interface ReadAdvancerDeps {
  sessions: Pick<SessionStore, "getLastReadAt" | "setLastReadAt">
  messages: Pick<MessageStore, "newestTs">
  broadcast: (frame: SessionReadFrame) => void
}

/**
 * Build `advanceRead(sessionId)`: mark a session read up to its newest message
 * and broadcast `session_read` to every client — but only when that actually
 * moves the read pointer forward. Idempotent, so the 60s viewing heartbeat and
 * repeat triggers never spam redundant broadcasts.
 */
export function makeReadAdvancer(deps: ReadAdvancerDeps): (sessionId: string) => void {
  const { sessions, messages, broadcast } = deps
  return (sessionId: string) => {
    const target = messages.newestTs(sessionId)
    if (target == null) return // empty session — nothing to read
    const current = sessions.getLastReadAt(sessionId)
    if (current != null && target <= current) return // already read; no change
    sessions.setLastReadAt(sessionId, target)
    broadcast({ type: "session_read", session: sessionId, last_read_at: target })
  }
}
