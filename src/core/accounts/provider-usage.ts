// Provider usage (the Usage screen's fetches) as the account policy's windows (decision #18): Auto and
// the limit switch pick by them. Live Claude and Codex sessions report their windows themselves; Grok
// reports none, so its weekly credits reach the policy only this way.
import type { UsageStore as AccountUsageStore } from "../../../packages/supermux-core/src/accounts/index.js"
import type { UsageWindow } from "../../../packages/supermux-core/src/accounts/types.js"
import type { ClaudeUsage, CodexUsage, GrokUsage, UsageWindow as ProviderWindow } from "../usage/index"

/** The provider's usage as policy windows, named like the agents' own rate-limit events; undefined when there is none to record. */
export function policyWindows(provider: string, data: unknown): UsageWindow[] | undefined {
  if (!data || typeof data !== "object") return undefined
  if (provider === "claude") {
    const usage = data as ClaudeUsage
    return nonEmpty([toWindow("five_hour", usage.fiveHour), toWindow("seven_day", usage.sevenDay)])
  }
  if (provider === "codex") {
    const usage = data as CodexUsage
    return nonEmpty((usage.windows ?? []).map((w) => toWindow(w.windowSeconds ? `${Math.round(w.windowSeconds / 60)}m` : w.id, w)))
  }
  if (provider === "grok") {
    const usage = data as GrokUsage
    // Without the credits data percentUsed is the legacy figure (0% on unified billing), not the account's usage.
    if (usage.creditsKnown !== true) return undefined
    if (typeof usage.percentUsed !== "number" || !Number.isFinite(usage.percentUsed)) return undefined
    const resetsAt = isoDate(usage.billingPeriodEndIso)
    return [{ name: usage.periodType === "unknown" ? "period" : usage.periodType, usedPercent: usage.percentUsed, ...(resetsAt ? { resetsAt } : {}) }]
  }
  return undefined
}

/** Records one provider fetch for `account` (an added account's id, or `<agent>:system`) where the policy reads it. */
export function recordProviderUsage(usage: AccountUsageStore, provider: string, account: string, data: unknown): void {
  const windows = policyWindows(provider, data)
  if (windows) usage.set(account, windows)
}

function toWindow(name: string, window: ProviderWindow | null | undefined): UsageWindow | undefined {
  if (!window || typeof window.used !== "number" || !Number.isFinite(window.used)) return undefined
  const resetsAt = isoDate(window.resetsAtIso)
  return { name, usedPercent: window.used, ...(resetsAt ? { resetsAt } : {}) }
}

function nonEmpty(windows: Array<UsageWindow | undefined>): UsageWindow[] | undefined {
  const kept = windows.filter((w): w is UsageWindow => w !== undefined)
  return kept.length ? kept : undefined
}

function isoDate(value: string | null | undefined): Date | undefined {
  if (!value) return undefined
  const at = new Date(value)
  return Number.isFinite(at.getTime()) ? at : undefined
}
