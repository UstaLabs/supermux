import type { AgentPhase } from "./agent-state-store"

// A session owes a deferred apply (SessionManager.reapplyAgentConfig) when its
// model/effort changed mid-turn. The desired config is already persisted to the
// registry; this only records that an apply is owed, plus the pre-change values
// captured at the FIRST deferral (for rollback if the deferred apply fails).
export type PreChangeConfig = { oldModel?: string; oldReasoningLevel?: string }

type PendingEntry = {
  olds: PreChangeConfig
  /** Latest desired revision covered by this pending entry. */
  revision: number
}

export class PendingReapply {
  private readonly bySession = new Map<string, PendingEntry>()
  /** Monotonic desired-request revision per session (survives take/clear of the apply-owed flag). */
  private readonly desiredRevision = new Map<string, number>()

  has(sessionId: string): boolean {
    return this.bySession.has(sessionId)
  }

  /** Count a new desired config write. Returns the new revision. */
  bump(sessionId: string): number {
    const n = (this.desiredRevision.get(sessionId) ?? 0) + 1
    this.desiredRevision.set(sessionId, n)
    return n
  }

  currentRevision(sessionId: string): number {
    return this.desiredRevision.get(sessionId) ?? 0
  }

  // Record a pending respawn. Keeps the olds from the first deferral so a failed
  // apply rolls all the way back to the pre-change state. Tracks the latest
  // desired revision so an older success cannot clear a newer request.
  mark(sessionId: string, olds: PreChangeConfig): void {
    const revision = this.currentRevision(sessionId)
    const existing = this.bySession.get(sessionId)
    if (!existing) {
      this.bySession.set(sessionId, { olds, revision })
      return
    }
    if (revision > existing.revision) existing.revision = revision
  }

  peek(sessionId: string): PreChangeConfig | undefined {
    return this.bySession.get(sessionId)?.olds
  }

  peekRevision(sessionId: string): number | undefined {
    return this.bySession.get(sessionId)?.revision
  }

  /**
   * After a native apply actually succeeds, later failures should roll back to
   * that native reality — not the original M0.
   */
  advanceBaseline(sessionId: string, applied: PreChangeConfig): void {
    const existing = this.bySession.get(sessionId)
    if (existing) existing.olds = applied
  }

  /**
   * Drop pending only if it is fully covered by the successful attempt's snapshot.
   * A newer revision stays queued.
   */
  takeIfCovered(sessionId: string, attemptRevision: number): PreChangeConfig | undefined {
    const existing = this.bySession.get(sessionId)
    if (!existing) return undefined
    if (existing.revision > attemptRevision) return undefined
    this.bySession.delete(sessionId)
    return existing.olds
  }

  // Remove and return the pending entry (the pre-change values), or undefined.
  take(sessionId: string): PreChangeConfig | undefined {
    const olds = this.bySession.get(sessionId)?.olds
    this.bySession.delete(sessionId)
    return olds
  }

  clear(sessionId: string): void {
    this.bySession.delete(sessionId)
    this.desiredRevision.delete(sessionId)
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
