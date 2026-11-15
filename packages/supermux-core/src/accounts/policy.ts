import type { Account, UsageWindow } from "./types.js"

const MIN_HOURS = 1 / 60

/**
 * `error` body `errorType` a driver sets when the agent's API refused the account itself (401/403:
 * login revoked, subscription access disabled, ...), as opposed to a failed request.
 */
export const ACCOUNT_UNAVAILABLE_ERROR = "account_unavailable"

/** `error` body `errorType` for a turn the agent refused because it has no sign-in at all (no credential to refuse). */
export const NOT_SIGNED_IN_ERROR = "not_signed_in"

/** The window Core records as full on an account the agent's API refused for lack of credits (HTTP 402). */
export const BALANCE_WINDOW = "balance"

/**
 * An HTTP status the agent's API answered a turn with that is about the account itself, read from the
 * turn's error: an ACP JSON-RPC error carries it in `data.http_status` (Grok: 402 "usage balance
 * exhausted"), also through `cause`. 402: out of credits; 401/403: the account refused. Undefined otherwise.
 */
export function accountHttpStatus(error: unknown): 401 | 402 | 403 | undefined {
  const seen = new Set<unknown>()
  for (let e = error; typeof e === "object" && e !== null && !seen.has(e); e = (e as { cause?: unknown }).cause) {
    seen.add(e)
    const status = (e as { data?: { http_status?: unknown } | null }).data?.http_status
    if (status === 401 || status === 402 || status === 403) return status
  }
  return undefined
}

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
 * Picks the best candidate: limited and unavailable accounts are skipped, accounts with usage data
 * rank by score (highest first), accounts without data follow in their given order.
 */
export function pickAccount(
  candidates: readonly Account[],
  usage: (id: string) => readonly UsageWindow[] | undefined,
  now = Date.now(),
  unavailable: (id: string) => boolean = () => false,
): Account | undefined {
  let best: { account: Account; score: number } | undefined
  let fallback: Account | undefined
  for (const account of candidates) {
    if (!switchable(account) || unavailable(account.id)) continue
    const windows = usage(account.id) ?? []
    if (limited(windows, now)) continue
    const value = score(windows, now)
    if (value === undefined) { fallback ??= account; continue }
    if (!best || value > best.score) best = { account, score: value }
  }
  return best?.account ?? fallback
}

/**
 * The organization an account belongs to, as data from its identity (Claude: `oauthAccount.organizationUuid` in its
 * `.claude.json`, read by the registry). Undefined when unknown (API keys, tokens added without an identity). Seats of
 * one org share the prompt cache (#61: a chat continued on another seat of the same org stayed warm 83 of 112 times),
 * so the auto-switch (#159) and the compact advice (#146) both ask it; the one place the org is read.
 */
export function accountOrg(account: Account): string | undefined {
  return account.identity?.org
}

/** Why the switch policy picked its target: a seat in the session's own org with room, or the most room anywhere. */
export type SwitchWhy = "same_org" | "most_room"

/**
 * The auto-switch's target (#159): the prompt cache is per organization, so a seat in the current account's org
 * (`org`) that has room keeps the chat warm and wins, ranked among the org's seats as pickAccount ranks; "room" is
 * what pickAccount already requires (not full, not refused, switchable). Without one: pickAccount over all.
 */
export function pickSwitchTarget(
  candidates: readonly Account[],
  usage: (id: string) => readonly UsageWindow[] | undefined,
  org: string | undefined,
  now = Date.now(),
  unavailable: (id: string) => boolean = () => false,
): { account: Account; why: SwitchWhy } | undefined {
  const sameOrg = org === undefined ? undefined : pickAccount(candidates.filter(account => accountOrg(account) === org), usage, now, unavailable)
  if (sameOrg) return { account: sameOrg, why: "same_org" }
  const best = pickAccount(candidates, usage, now, unavailable)
  return best && { account: best, why: "most_room" }
}

/** When full windows free up again: the latest reset among them. Undefined when none is full or one has no reset time. */
export function freesAt(windows: readonly UsageWindow[], now = Date.now()): Date | undefined {
  const full = windows.filter(window => effectiveUsed(window, now) >= 100)
  if (!full.length || full.some(window => !window.resetsAt)) return undefined
  return new Date(Math.max(...full.map(window => window.resetsAt!.getTime())))
}

/**
 * When every candidate is full: the account that frees up first and when, i.e. the minimum over
 * accounts of the latest reset among its full windows. Unavailable (refused or dead) accounts, the
 * ones never switched to, accounts without full windows and full windows without a reset time are
 * no candidates. Undefined when no reset time is known.
 */
export function earliestReset(
  candidates: readonly Account[],
  usage: (id: string) => readonly UsageWindow[] | undefined,
  now = Date.now(),
  unavailable: (id: string) => boolean = () => false,
): { account: string; at: Date } | undefined {
  let best: { account: string; at: Date } | undefined
  for (const account of candidates) {
    if (!switchable(account) || unavailable(account.id)) continue
    const at = freesAt(usage(account.id) ?? [], now)
    if (at && (!best || at < best.at)) best = { account: account.id, at }
  }
  return best
}
