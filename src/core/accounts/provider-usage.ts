// Provider usage (the Usage screen's fetches) as the account policy's windows (decision #18): Auto and
// the limit switch pick by them. Live Claude and Codex sessions report their windows themselves; Grok
// reports none, so its weekly credits reach the policy only this way.
import { BALANCE_WINDOW, type UsageStore as AccountUsageStore } from "../../../packages/supermux-core/src/accounts/index.js"
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
    // Shapes are checked, not trusted: this runs inside the usage store's fetch.
    const windows: unknown[] = Array.isArray((data as CodexUsage).windows) ? (data as CodexUsage).windows : []
    return nonEmpty(windows.map((raw) => {
      if (!raw || typeof raw !== "object") return undefined
      const w = raw as CodexUsage["windows"][number]
      const name = typeof w.windowSeconds === "number" && w.windowSeconds > 0 ? `${Math.round(w.windowSeconds / 60)}m` : w.id
      return typeof name === "string" && name ? toWindow(name, w) : undefined
    }))
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

/**
 * Records one provider fetch for `account` (an added account's id, or `<agent>:system`) where the policy
 * reads it. A Grok reading is the account's whole picture (its window is named after the billing period,
 * which changes) and replaces every window, except Core's 402 `balance` mark while the reading is under
 * 100% (the API still refuses the account until the mark expires). Claude and Codex readings merge: their windows replace those
 * of the same name and Core's 402 `balance` mark (the provider's figure is the real one); every other
 * window already recorded stays, e.g. a live session's rejection of a window the endpoint does not report.
 */
export function recordProviderUsage(usage: AccountUsageStore, provider: string, account: string, data: unknown): void {
  const windows = policyWindows(provider, data)
  if (!windows) return
  if (provider === "grok") {
    // Core's 402 mark stays while the reading is under 100% (the API still refuses the account); an
    // exhausted reading replaces it with its own window and real reset.
    const exhausted = windows.some((w) => w.usedPercent >= 100)
    const balance = exhausted ? [] : (usage.get(account) ?? []).filter((w) => w.name === BALANCE_WINDOW && (!w.resetsAt || w.resetsAt.getTime() > Date.now()))
    usage.set(account, [...balance, ...windows])
    return
  }
  const fresh = new Set(windows.map((w) => w.name))
  const kept = (usage.get(account) ?? []).filter((w) => !fresh.has(w.name) && w.name !== BALANCE_WINDOW)
  usage.set(account, [...kept, ...windows])
}

/**
 * The usage store's "fetched" listener: records the fetch once `usage` holds its saved windows. A merge
 * before that load would make the load skip the account, and its saved windows would be lost.
 */
export async function recordFetched(usage: AccountUsageStore, fetched: { provider: string; account: string; data: unknown }): Promise<void> {
  await usage.load()
  recordProviderUsage(usage, fetched.provider, fetched.account, fetched.data)
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
