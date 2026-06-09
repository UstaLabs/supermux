import { EventEmitter } from "events"

export type AgentPhase = "idle" | "sending" | "thinking" | "running"

export interface AgentState {
  phase: AgentPhase
  tool?: string       // present when phase === "running"
  since: number       // epoch ms when the current phase began
  workingSince?: number // epoch ms when agent entered working state (thinking or running); preserved across thinking<->running transitions; cleared on idle/sending
}

// Lifecycle events that drive the phase. "deliver" is emitted by the broker the
// moment it hands an inbound message to a session (belt-and-braces start signal);
// the rest are Claude hook event names.
export type AgentEvent = "deliver" | "UserPromptSubmit" | "turn-start" | "PreToolUse" | "PostToolUse" | "Stop"

export class AgentStateStore extends EventEmitter {
  private readonly bySession = new Map<string, AgentState>()

  // Apply an event for a session. `tool` and `now` are optional;
  // `now` defaults to Date.now() for deterministic tests pass an explicit value.
  // Emits "change" (sessionId, state) only when the resulting state actually
  // changed (phase or tool differs).
  applyEvent(sessionId: string, event: AgentEvent, tool?: string, now: number = Date.now()): void {
    const prev = this.bySession.get(sessionId) ?? { phase: "idle" as AgentPhase, since: now }
    let next: AgentState = prev
    switch (event) {
      case "deliver":
        next = { phase: "sending", since: now }
        break
      case "UserPromptSubmit":
      case "turn-start":
      case "PostToolUse":
        next = { phase: "thinking", since: now }
        break
      case "PreToolUse":
        next = { phase: "running", tool, since: now }
        break
      case "Stop":
        next = { phase: "idle", since: now }
        break
    }
    // Stamp workingSince: enter working from non-working -> now; stay in working -> preserve; leave working -> undefined (next already is)
    const isWorking = (p: AgentPhase) => p === "thinking" || p === "running"
    if (isWorking(next.phase)) {
      next.workingSince = isWorking(prev.phase) ? (prev.workingSince ?? now) : now
    }
    // No-op if nothing meaningful changed (same phase AND same tool).
    // Within-working transitions that don't change phase also keep workingSince = prev.workingSince (same value), so no spurious emits.
    if (next.phase === prev.phase && next.tool === prev.tool) return
    this.bySession.set(sessionId, next)
    if (prev.phase === "thinking" && next.phase !== "thinking") {
      const durationMs = now - prev.since
      if (durationMs >= 1000) this.emit("thoughtComplete", sessionId, durationMs, now)
    }
    this.emit("change", sessionId, next)
  }

  get(sessionId: string): AgentState {
    return this.bySession.get(sessionId) ?? { phase: "idle", since: 0 }
  }

  clear(sessionId: string): void {
    this.bySession.delete(sessionId)
  }
}
