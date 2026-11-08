import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import type { CoreLimits } from "../src/types.js"

/**
 * A working directory for test sessions: a folder of its own beside their state directories (made under the OS temp
 * dir too), never one that holds a state directory, which the core refuses (invalid_workdir).
 */
export const WORKDIR = mkdtempSync(join(tmpdir(), "supermux-core-cwd-"))
process.once("exit", () => rmSync(WORKDIR, { recursive: true, force: true }))

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

/**
 * The setup budget a fixture agent gets in tests that are not about setup timeouts. A cold CI runner took 7.2 s to start
 * one (Windows lane); a tighter budget is a clock those tests race. It stays under every file's test timeout (20 s and
 * up), so a fixture that really hangs still fails as a named setup timeout.
 */
export const FIXTURE_SETUP_MS = 15_000

let seq = 0
export const TEST_ACP_PERMISSIONS = { kind: "acp" as const, policy: "ask" as const, nativeMode: null }
export const TEST_CLAUDE_PERMISSIONS = { kind: "claude" as const, permissionMode: "dontAsk" as const }
export const TEST_CODEX_PERMISSIONS = { kind: "codex" as const, approvalPolicy: "never" as const, sandbox: "read-only" as const }

export function nextId(prefix = "s"): string {
  seq += 1
  return `${prefix}${seq}`
}
