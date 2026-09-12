import { CoreError } from "./errors.js"
import type { ActivityNotice } from "./types.js"

/** Distinct outstanding native activity ids tracked per session (open buffer and live map). */
export const MAX_OUTSTANDING_ACTIVITY = 256

export const ACTIVITY_OVERFLOW = new CoreError(
  "activity_overflow",
  "Too many outstanding native activities",
)

/** Copy validated fields so callers cannot mutate a notice after onActivity returns. */
export function copyActivityNotice(notice: ActivityNotice | null | undefined): ActivityNotice | undefined {
  if (!notice || typeof notice !== "object") return
  const id = notice.id
  const phase = notice.phase
  if (typeof id !== "string" || !id) return
  if (phase !== "started" && phase !== "completed") return
  return { id, phase }
}

/**
 * Compact outstanding-id map used while Session is not constructed yet.
 * Start+complete cycles reuse slots; unknown completes are no-ops.
 * Overflow means a new distinct id at the cap — callers must fail open, not drop.
 */
export function applyBufferedActivity(
  outstanding: Map<string, ActivityNotice>,
  notice: ActivityNotice,
): "ok" | "overflow" {
  if (notice.phase === "started") {
    if (outstanding.has(notice.id)) return "ok"
    if (outstanding.size >= MAX_OUTSTANDING_ACTIVITY) return "overflow"
    outstanding.set(notice.id, { id: notice.id, phase: "started" })
    return "ok"
  }
  outstanding.delete(notice.id)
  return "ok"
}
