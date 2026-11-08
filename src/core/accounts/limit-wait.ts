// Card #92: a session whose every account is out waits for the first reset, then carries on by itself.
//
// One wait per session, saved to a file so a broker restart keeps it. The timer only decides WHEN;
// the broker's `resume` decides how (account, wake, continuation prompt). Sessions waiting on the same
// account do not all start at the reset: each has its own stagger, and they are kept SPACING_MS apart.
import { readJsonOr, writeJsonAtomic } from "../../shared/atomic-json"

/** After the reset, so the provider has really reset the window. */
export const RESUME_MARGIN_MS = 2 * 60_000
/** Each session's own offset after the margin, from a hash of its id. */
export const STAGGER_MS = 4 * 60_000
/** At least this far apart on one account. */
export const SPACING_MS = 20_000
/** No reset time was reported: try again after this long. */
export const UNKNOWN_RESET_RETRY_MS = 60 * 60_000
const MAX_TIMER_MS = 2 ** 31 - 1

export type LimitWait = {
  sessionId: string
  /** The account that frees up first (the session resumes on it). */
  account: string
  /** ISO; absent when no reset time was reported. */
  resetsAt?: string
  /** ISO. */
  resumeAt: string
  /** The limit hit background work (subagents), not the main turn. */
  background: boolean
}

export type LimitWaitDeps = {
  file: string
  now: () => number
  setTimer: (fn: () => void, ms: number) => unknown
  clearTimer: (timer: unknown) => void
  /** Carries the session on. Called once per wait, after it is forgotten. */
  resume: (wait: LimitWait) => Promise<void>
  /** A session's wait was made, replaced or dropped (undefined). */
  changed: (sessionId: string, wait: LimitWait | undefined) => void
  /** The host setting ("Resume after a limit reset"). */
  enabled: () => boolean
  onError?: (sessionId: string, error: unknown) => void
}

export class LimitWaits {
  private readonly waits = new Map<string, LimitWait>()
  private readonly timers = new Map<string, unknown>()

  constructor(private readonly deps: LimitWaitDeps) {}

  /** Reads the saved waits and arms them. Waits already due are spread out from now. Returns how many of each. */
  load(): { armed: number; due: number } {
    const saved = readJsonOr<{ version?: unknown; waits?: unknown }>(this.deps.file, {})
    const list = saved?.version === 1 && Array.isArray(saved.waits) ? saved.waits.filter(isWait) : []
    const now = this.deps.now()
    const due: LimitWait[] = []
    for (const wait of list) {
      if (Date.parse(wait.resumeAt) > now) this.put(wait)
      else due.push(wait)
    }
    due.sort((a, b) => Date.parse(a.resumeAt) - Date.parse(b.resumeAt))
    for (const wait of due) this.put({ ...wait, resumeAt: new Date(this.spaced(wait.account, now, wait.sessionId)).toISOString() })
    if (due.length) this.save()
    return { armed: list.length - due.length, due: due.length }
  }

  /** Makes (or replaces) the session's wait. Undefined when auto-resume is off. */
  wait(sessionId: string, at: { account: string; resetsAt?: Date; background: boolean }): LimitWait | undefined {
    if (!this.deps.enabled()) return undefined
    this.forget(sessionId)
    const base = at.resetsAt ? at.resetsAt.getTime() : this.deps.now() + UNKNOWN_RESET_RETRY_MS
    const wanted = base + RESUME_MARGIN_MS + stagger(sessionId)
    const wait: LimitWait = {
      sessionId,
      account: at.account,
      ...(at.resetsAt ? { resetsAt: at.resetsAt.toISOString() } : {}),
      resumeAt: new Date(this.spaced(at.account, wanted, sessionId)).toISOString(),
      background: at.background,
    }
    this.put(wait)
    this.save()
    this.deps.changed(sessionId, wait)
    return wait
  }

  get(sessionId: string): LimitWait | undefined { return this.waits.get(sessionId) }

  list(): LimitWait[] { return [...this.waits.values()] }

  /** Drops the session's wait (Cancel, a message from the user, the session gone). False when it had none. */
  cancel(sessionId: string): boolean {
    if (!this.forget(sessionId)) return false
    this.save()
    this.deps.changed(sessionId, undefined)
    return true
  }

  /** Resumes the session now. False when it had no wait. */
  async resumeNow(sessionId: string): Promise<boolean> {
    const wait = this.waits.get(sessionId)
    if (!wait) return false
    await this.fire(wait)
    return true
  }

  /** Drops every wait on an account that was removed; returns their session ids. */
  dropAccount(account: string): string[] {
    const ids = this.list().filter((wait) => wait.account === account).map((wait) => wait.sessionId)
    for (const id of ids) this.cancel(id)
    return ids
  }

  private put(wait: LimitWait): void {
    this.waits.set(wait.sessionId, wait)
    this.arm(wait)
  }

  private forget(sessionId: string): boolean {
    const timer = this.timers.get(sessionId)
    if (timer !== undefined) this.deps.clearTimer(timer)
    this.timers.delete(sessionId)
    return this.waits.delete(sessionId)
  }

  private arm(wait: LimitWait): void {
    const delay = Math.min(Math.max(Date.parse(wait.resumeAt) - this.deps.now(), 0), MAX_TIMER_MS)
    this.timers.set(wait.sessionId, this.deps.setTimer(() => {
      this.timers.delete(wait.sessionId)
      if (this.waits.get(wait.sessionId) !== wait) return
      if (this.deps.now() < Date.parse(wait.resumeAt)) this.arm(wait)
      else void this.fire(wait)
    }, delay))
  }

  private async fire(wait: LimitWait): Promise<void> {
    this.cancel(wait.sessionId)
    try {
      await this.deps.resume(wait)
    } catch (error) {
      this.deps.onError?.(wait.sessionId, error)
    }
  }

  /** The first moment at or after `wanted` that is SPACING_MS from every other wait on the account. */
  private spaced(account: string, wanted: number, sessionId: string): number {
    const taken = this.list()
      .filter((wait) => wait.account === account && wait.sessionId !== sessionId)
      .map((wait) => Date.parse(wait.resumeAt))
      .sort((a, b) => a - b)
    let at = wanted
    for (const other of taken) if (Math.abs(other - at) < SPACING_MS) at = other + SPACING_MS
    return at
  }

  private save(): void {
    try {
      writeJsonAtomic(this.deps.file, { version: 1, waits: this.list() })
    } catch (error) {
      this.deps.onError?.("", error)
    }
  }
}

/** The session's own offset in [0, STAGGER_MS), whole seconds. */
function stagger(sessionId: string): number {
  let hash = 2166136261
  for (let i = 0; i < sessionId.length; i++) hash = Math.imul(hash ^ sessionId.charCodeAt(i), 16777619)
  return ((hash >>> 0) % (STAGGER_MS / 1000)) * 1000
}

function isWait(value: unknown): value is LimitWait {
  if (!value || typeof value !== "object") return false
  const w = value as Record<string, unknown>
  return typeof w.sessionId === "string" && typeof w.account === "string" && typeof w.resumeAt === "string" && Number.isFinite(Date.parse(w.resumeAt))
    && (w.resetsAt === undefined || typeof w.resetsAt === "string") && typeof w.background === "boolean"
}

export const LIMIT_RESET_CONTINUE_PROMPT = "Continue from where you stopped. Your previous turn hit a usage limit, which has now reset."
export const LIMIT_RESET_BACKGROUND_SUFFIX = "Background subagents may have stopped on the limit: check them and resume or restart any unfinished work."

export type ResumeDeps = {
  exists: (sessionId: string) => boolean
  /** A turn is running: the user (or something else) carried the session on already. */
  busy: (sessionId: string) => boolean
  /** The session's account now (its system account when it has none). */
  account: (sessionId: string) => string
  registered: (account: string) => boolean | Promise<boolean>
  switchAccount: (sessionId: string, account: string) => Promise<void>
  label: (account: string) => string
  /** A broker notice in the chat. */
  notify: (sessionId: string, text: string) => Promise<void>
  /** The continuation, to the agent, waking the session when it sleeps. */
  deliver: (sessionId: string, text: string) => Promise<void>
}

/** A due wait: on the account that reset when it can, the session carries on with the continuation prompt. */
export async function resumeAfterLimit(wait: LimitWait, deps: ResumeDeps): Promise<void> {
  const id = wait.sessionId
  if (!deps.exists(id) || deps.busy(id)) return
  let account = deps.account(id)
  if (wait.account !== account && await deps.registered(wait.account)) {
    try {
      await deps.switchAccount(id, wait.account)
      account = wait.account
    } catch { /* it carries on where it is; a limit there makes a new wait */ }
  }
  await deps.notify(id, wait.resetsAt
    ? `Resumed on ${deps.label(account)} — the usage limit reset`
    : `Trying again on ${deps.label(account)} — the usage limit may have reset`)
  await deps.deliver(id, wait.background ? `${LIMIT_RESET_CONTINUE_PROMPT} ${LIMIT_RESET_BACKGROUND_SUFFIX}` : LIMIT_RESET_CONTINUE_PROMPT)
}

/** "18:13": a wait's time on the host's clock, for the chat notice (the apps show it in their own time zone). */
export function clockTime(iso: string): string {
  const at = new Date(iso)
  return `${String(at.getHours()).padStart(2, "0")}:${String(at.getMinutes()).padStart(2, "0")}`
}
