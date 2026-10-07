import type { Account, UsageWindow } from "./types.js"

const MIN_HOURS = 1 / 60

/** A window whose reset time has passed no longer counts as used. */
function effectiveUsed(window: UsageWindow, now: number): number {
  return window.resetsAt && window.resetsAt.getTime() <= now ? 0 : window.usedPercent
}

/** A window at or above 100% that has not reset yet. */
export function limited(windows: readonly UsageWindow[], now = Date.now()): boolean {
  return windows.some(window => effectiveUsed(window, now) >= 100)
}

/**
 * Ghostex "Auto": min over windows of remaining% / hours until reset (at least one minute;
 * a window without a reset time counts as one hour). Higher is better. undefined: no data.
 */
export function score(windows: readonly UsageWindow[], now = Date.now()): number | undefined {
  if (!windows.length) return undefined
  let best = Infinity
  for (const window of windows) {
    const remaining = Math.max(0, 100 - effectiveUsed(window, now))
    const hours = window.resetsAt ? Math.max((window.resetsAt.getTime() - now) / 3_600_000, MIN_HOURS) : 1
    best = Math.min(best, remaining / Math.max(hours, MIN_HOURS))
  }
  return best
}

/** Accounts the policy may move a session to: not isolated, not Cursor, not excluded. */
export function switchable(account: Account): boolean {
  return !account.isolated && account.agent !== "cursor"
}

/**
 * Picks the best candidate: limited accounts are skipped, accounts with usage data rank by score
 * (highest first), accounts without data follow in their given order.
 */
export function pickAccount(candidates: readonly Account[], usage: (id: string) => readonly UsageWindow[] | undefined, now = Date.now()): Account | undefined {
  let best: { account: Account; score: number } | undefined
  let fallback: Account | undefined
  for (const account of candidates) {
    if (!switchable(account)) continue
    const windows = usage(account.id) ?? []
    if (limited(windows, now)) continue
    const value = score(windows, now)
    if (value === undefined) { fallback ??= account; continue }
    if (!best || value > best.score) best = { account, score: value }
  }
  return best?.account ?? fallback
}
