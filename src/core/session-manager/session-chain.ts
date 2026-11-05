// Card #75: one chain per session. It is the only place a session's agent is woken (a new
// session's first start, a dozing session's resume, a crashed one's revive) and the only queue its
// work waits in meanwhile, so a message never reaches an agent that isn't up, two messages never
// start it twice, and they arrive in the order they came. Nothing here is stored: after a restart
// nothing is queued or waking, which is the truth.
import { makeLogger } from "../../shared/log"

const log = makeLogger("session-chain")

/** Work dropped because Stop, archive or kill cancelled the session's queue. */
export class CancelledError extends Error {
  constructor(readonly sessionId: string) {
    super(`${sessionId}: cancelled`)
    this.name = "CancelledError"
  }
}

/** Work that was waiting behind a wake that failed; the work that ran the wake got `cause` itself. */
export class WakeFailedError extends Error {
  constructor(readonly sessionId: string, override readonly cause: unknown) {
    super(`${sessionId}: the wake it waited for failed`)
    this.name = "WakeFailedError"
  }
}

/** What a queued piece of work can ask while it runs: whether the session's queue was cancelled since. */
export type ChainToken = { readonly cancelled: boolean }

export type SessionChainDeps = {
  /** The session's agent is up and can take input now (no wake needed). */
  isLive(id: string): boolean
  /** Make the session deliverable: restore, resume or revive it. Throws when it can't. */
  ensureLive(id: string): Promise<void>
  /** A wake of a session that was not up started (true) or ended (false): the "starting" phase. */
  onWaking?(id: string, waking: boolean): void
  /** A wake that finished after Stop: put the session back to sleep. */
  putToSleep?(id: string): Promise<void>
}

export class SessionChain {
  private readonly tails = new Map<string, Promise<void>>()
  private readonly generations = new Map<string, number>()
  /** Why a generation ended (a cancel, or a failed wake), for the work still queued in it. */
  private readonly endings = new Map<string, Map<number, Error>>()
  private readonly wakingNow = new Set<string>()

  constructor(private readonly deps: SessionChainDeps) {}

  /** Runs `work` after everything already queued for session `id`. */
  run<T>(id: string, work: (token: ChainToken) => Promise<T>): Promise<T> {
    const generation = this.generation(id)
    const token: ChainToken = { get cancelled() { return chain.generation(id) !== generation } }
    const chain = this
    const previous = this.tails.get(id) ?? Promise.resolve()
    const result = previous.then(() => {
      if (token.cancelled) throw this.endingOf(id, generation)
      return work(token)
    })
    // Cleaned up in the first reaction to the result, so a caller awaiting it already sees
    // pending() false when nothing else is queued.
    const settle = () => {
      if (this.tails.get(id) !== tail) return
      this.tails.delete(id)
      this.endings.delete(id)
    }
    const tail: Promise<void> = result.then(settle, settle)
    this.tails.set(id, tail)
    return result
  }

  /** Wake the session if its agent isn't up, in order with everything queued for it. */
  wake(id: string): Promise<void> {
    return this.run(id, (token) => this.wakeNow(id, token))
  }

  /**
   * Inside `run`: wake the session now if its agent isn't up. A failure fails the work queued
   * behind it too (it would only fail again); the next new work wakes again.
   */
  async wakeNow(id: string, token: ChainToken): Promise<void> {
    if (this.deps.isLive(id)) return
    this.setWaking(id, true)
    try {
      await this.deps.ensureLive(id)
    } catch (err) {
      if (token.cancelled) throw new CancelledError(id)
      this.end(id, new WakeFailedError(id, err))
      throw err
    } finally {
      this.setWaking(id, false)
    }
    if (token.cancelled) {
      // Stop came while it was starting: what started goes back to sleep.
      try { await this.deps.putToSleep?.(id) } catch (err) { log.warn("chain_put_to_sleep_failed", { session: id, err: String(err) }) }
      throw new CancelledError(id)
    }
  }

  /** Stop, archive or kill: drop the queued work; the work running sees `token.cancelled`. */
  cancel(id: string): void {
    if (!this.tails.has(id)) return
    this.end(id, new CancelledError(id))
  }

  /** Anything queued or running for the session. */
  pending(id: string): boolean {
    return this.tails.has(id)
  }

  /** A session that was not up is being woken now. */
  waking(id: string): boolean {
    return this.wakingNow.has(id)
  }

  /** Resolves once nothing is queued for any session (tests, shutdown). */
  async idle(): Promise<void> {
    while (this.tails.size) await Promise.all([...this.tails.values()])
  }

  private generation(id: string): number {
    return this.generations.get(id) ?? 0
  }

  private end(id: string, reason: Error): void {
    const ended = this.generation(id)
    let reasons = this.endings.get(id)
    if (!reasons) { reasons = new Map(); this.endings.set(id, reasons) }
    reasons.set(ended, reason)
    this.generations.set(id, ended + 1)
  }

  private endingOf(id: string, generation: number): Error {
    return this.endings.get(id)?.get(generation) ?? new CancelledError(id)
  }

  private setWaking(id: string, waking: boolean): void {
    if (waking === this.wakingNow.has(id)) return
    if (waking) this.wakingNow.add(id)
    else this.wakingNow.delete(id)
    try { this.deps.onWaking?.(id, waking) } catch (err) { log.warn("chain_on_waking_failed", { session: id, err: String(err) }) }
  }
}

/** What deciding and making a session live needs (the inbox's deps satisfy it). */
export type LivenessDeps = {
  /** The session row, archived ones included. */
  session(id: string): { id: string; status?: string } | undefined
  resumeFromArchive(id: string): Promise<{ ok: boolean; error?: string }>
  resumeSuspended(session: any): Promise<boolean>
  /** "none": no adapter registered; "dead": its core session failed or closed. */
  adapterState(id: string): "none" | "alive" | "dead"
  resumeAdapter(id: string): Promise<void>
  waitDeliverable(id: string, timeoutMs: number): Promise<boolean>
  deliverableTimeoutMs?: number
  /** Takes input right now (SessionManager.isDeliverable). */
  isDeliverable?(id: string): boolean
}

/** Up and taking input: an active row whose adapter is alive and deliverable. */
export function isLiveWith(deps: LivenessDeps, id: string): boolean {
  const row = deps.session(id)
  return !!row && (row.status ?? "active") === "active" && deps.adapterState(id) === "alive" && (deps.isDeliverable?.(id) ?? false)
}

/**
 * Make a session deliverable: archived → restore it; asleep (suspended, or active with no agent at
 * all: born asleep, or not resumed at boot) → resume it; crashed → revive its adapter; then wait
 * until it takes input. Throws when it can't.
 */
export async function ensureLiveWith(deps: LivenessDeps, id: string): Promise<void> {
  const row = deps.session(id)
  if (!row) throw new Error(`session ${id} no longer exists`)
  if (row.status === "archived") {
    const restored = await deps.resumeFromArchive(id)
    if (!restored.ok) throw new Error(`restore from archive failed: ${restored.error ?? "unknown"}`)
  } else if (row.status === "suspended" || deps.adapterState(id) === "none") {
    if (!(await deps.resumeSuspended(row))) throw new Error("resuming the suspended session failed")
  }
  if (deps.adapterState(id) === "dead") await deps.resumeAdapter(id)
  if (!(await deps.waitDeliverable(id, deps.deliverableTimeoutMs ?? 60_000))) {
    throw new Error("session did not become deliverable")
  }
}
