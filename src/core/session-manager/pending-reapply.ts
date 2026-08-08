import type { AgentPhase } from "./agent-state-store"

// A session owes a deferred apply (SessionManager.reapplyAgentConfig) when its
// model/effort changed mid-turn. The desired config is already persisted to the
// registry; this only records that an apply is owed, plus the pre-change values
// captured at the FIRST deferral (for rollback if the deferred apply fails).
export type PreChangeConfig = { oldModel?: string; oldReasoningLevel?: string }

export class PendingReapply {
  private readonly bySession = new Map<string, PreChangeConfig>()

  has(sessionId: string): boolean {
    return this.bySession.has(sessionId)
  }

  // Record a pending respawn. Keeps the olds from the first deferral so a failed
  // apply rolls all the way back to the pre-change state.
  mark(sessionId: string, olds: PreChangeConfig): void {
    if (!this.bySession.has(sessionId)) this.bySession.set(sessionId, olds)
  }

  // Remove and return the pending entry (the pre-change values), or undefined.
  take(sessionId: string): PreChangeConfig | undefined {
    const olds = this.bySession.get(sessionId)
    this.bySession.delete(sessionId)
    return olds
  }

  clear(sessionId: string): void {
    this.bySession.delete(sessionId)
  }
}

// A model/effort respawn should be deferred only when the session is mid-turn
// (any non-idle phase) and the caller didn't ask to apply immediately.
export function shouldDeferReapply(phase: AgentPhase, applyNow: boolean): boolean {
  return phase !== "idle" && !applyNow
}

// Diff the pre-change values against the session's CURRENT stored values so the
// apply path can touch only what the user actually changed. Stored (not
// effective/resolved) values on both sides — consistent comparison.
export function changedSince(
  olds: PreChangeConfig,
  current: { model?: string; reasoningLevel?: string },
): { model: boolean; effort: boolean } {
  return {
    model: olds.oldModel !== current.model,
    effort: olds.oldReasoningLevel !== current.reasoningLevel,
  }
}
