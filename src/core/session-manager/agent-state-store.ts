import { EventEmitter } from "events"

export type AgentPhase = "idle" | "thinking" | "running" | "dead"

export interface AgentState {
  phase: AgentPhase
  tool?: string         // present when phase === "running"
  since: number         // epoch ms when the current phase began
  workingSince?: number // epoch ms when the current working stretch began (thinking/running); cleared on idle/dead
}

// Lifecycle events the broker REFLECTS (it never sets state optimistically):
//   - hooks: UserPromptSubmit/turn-start (start), PreToolUse/PostToolUse (tools), Stop (normal end)
//   - transcript: interrupt (the one transition no hook fires)
//   - liveness: dead (crash), connected (revive a dead session on reconnect)
export type AgentEvent =
  | "UserPromptSubmit" | "turn-start" | "PreToolUse" | "PostToolUse" | "Stop"
  | "interrupt" | "dead" | "connected"

export class AgentStateStore extends EventEmitter {
  private readonly bySession = new Map<string, AgentState>()

  applyEvent(sessionId: string, event: AgentEvent, tool?: string, now: number = Date.now()): void {
    const prev = this.bySession.get(sessionId) ?? { phase: "idle" as AgentPhase, since: now }
    let next: AgentState = prev
    switch (event) {
      case "UserPromptSubmit":
      case "turn-start":
      case "PostToolUse":
        next = { phase: "thinking", since: now }
        break
      case "PreToolUse":
        next = { phase: "running", tool, since: now }
        break
      case "Stop":
      case "interrupt":
        next = { phase: "idle", since: now }
        break
      case "dead":
        next = { phase: "dead", since: now }
        break
      case "connected":
        if (prev.phase !== "dead") return  // a pong/reconnect on a live session must not touch state
        next = { phase: "idle", since: now }
        break
    }
    const isWorking = (p: AgentPhase) => p === "thinking" || p === "running"
    if (isWorking(next.phase)) {
      next.workingSince = isWorking(prev.phase) ? (prev.workingSince ?? now) : now
    }
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
