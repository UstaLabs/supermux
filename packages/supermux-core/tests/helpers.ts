import type { CoreLimits } from "../src/types.js"

/** Explicit test limits. There are no library defaults. */
export const TEST_LIMITS: CoreLimits = {
  interruptTimeoutMs: 30,
  maxPending: 128,
  outstandingActivity: 256,
}

export const BROKER_LIMITS: CoreLimits = {
  interruptTimeoutMs: 10_000,
  maxPending: 128,
  outstandingActivity: 256,
}

let seq = 0
export function nextId(prefix = "s"): string {
  seq += 1
  return `${prefix}${seq}`
}
