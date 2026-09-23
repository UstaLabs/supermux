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
export const TEST_ACP_PERMISSIONS = { kind: "acp" as const, policy: "ask" as const, nativeMode: null }
export const TEST_CLAUDE_PERMISSIONS = { kind: "claude" as const, permissionMode: "dontAsk" as const }
export const TEST_CODEX_PERMISSIONS = { kind: "codex" as const, approvalPolicy: "never" as const, sandbox: "read-only" as const }

export function nextId(prefix = "s"): string {
  seq += 1
  return `${prefix}${seq}`
}
