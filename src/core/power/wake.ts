// Sleep/wake detection by clock gaps (spec 2026-09-30-desktop-hosting-lifecycle-design, "Wake
// reconnect, sleep ≠ idle"). A 5 s interval notes the wall clock and the monotonic clock
// (`performance.now()`) at every tick.
//   - Linux: CLOCK_MONOTONIC pauses during suspend, so sleep is the part of the wall-clock delta the
//     monotonic clock did not see: `wallDelta - monoDelta > 30 s`. A stall (a frozen event loop, a
//     SIGSTOP) moves both clocks and is NOT a wake.
//   - macOS / Windows: whether the monotonic clock pauses isn't known, so a wall-clock delta over
//     30 s between ticks counts as a sleep (a long stall counts too).

export const WAKE_TICK_MS = 5_000
/** A wall-clock gap between ticks above this is a sleep. */
export const SLEEP_GAP_MS = 30_000

export interface WakeDetectorDeps {
  /** Which rule applies (default: process.platform). */
  platform?: NodeJS.Platform
  /** The wall clock (default Date.now). */
  now?: () => number
  /** The monotonic clock (default performance.now). */
  mono?: () => number
  setInterval?: (fn: () => void, ms: number) => unknown
  clearInterval?: (handle: unknown) => void
  intervalMs?: number
  thresholdMs?: number
  log?: (event: string, data: Record<string, unknown>) => void
}

/**
 * A wall-clock gap tracker for one periodic loop: [gap] returns how long the computer slept since
 * the previous call (the elapsed time beyond [expectedMs]) when that elapsed time exceeds
 * `expectedMs + thresholdMs`, else 0. Loops that judge liveness or idleness by timestamps use it
 * to shift their baselines forward instead of treating sleep as silence.
 */
export class TickGap {
  private last: number | undefined
  constructor(private readonly expectedMs: number, private readonly thresholdMs = SLEEP_GAP_MS) {}

  gap(now: number): number {
    const prev = this.last
    this.last = now
    if (prev === undefined) return 0
    const elapsed = now - prev
    if (elapsed <= this.expectedMs + this.thresholdMs) return 0
    return elapsed - this.expectedMs
  }
}

export class WakeDetector {
  private last: number | undefined
  private lastMono: number | undefined
  private timer: unknown = undefined
  private readonly listeners = new Set<(sleptMs: number) => void>()

  constructor(private readonly deps: WakeDetectorDeps = {}) {}

  private now(): number {
    return this.deps.now?.() ?? Date.now()
  }

  private monoNow(): number {
    return this.deps.mono?.() ?? performance.now()
  }

  private get intervalMs(): number {
    return this.deps.intervalMs ?? WAKE_TICK_MS
  }

  start(): void {
    if (this.timer !== undefined) return
    this.last = this.now()
    this.lastMono = this.monoNow()
    const set = this.deps.setInterval ?? ((fn: () => void, ms: number) => setInterval(fn, ms))
    const h = set(() => { this.check() }, this.intervalMs)
    ;(h as { unref?: () => void } | undefined)?.unref?.()
    this.timer = h
  }

  stop(): void {
    if (this.timer === undefined) return
    const clear = this.deps.clearInterval ?? ((h: unknown) => clearInterval(h as ReturnType<typeof setInterval>))
    clear(this.timer)
    this.timer = undefined
  }

  onWake(fn: (sleptMs: number) => void): () => void {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  /**
   * One check, also callable outside the tick: a loop that is about to judge idleness can call it
   * first, so a wake is handled before that loop's own (possibly earlier-firing) timer acts on the
   * gap. Returns the slept ms when it detected a wake, else undefined.
   */
  check(): number | undefined {
    const now = this.now()
    const mono = this.monoNow()
    const prev = this.last
    const prevMono = this.lastMono
    this.last = now
    this.lastMono = mono
    if (prev === undefined || prevMono === undefined) return undefined
    const wallDeltaMs = now - prev
    const monoDeltaMs = mono - prevMono
    const threshold = this.deps.thresholdMs ?? SLEEP_GAP_MS
    let sleptMs: number
    if ((this.deps.platform ?? process.platform) === "linux") {
      sleptMs = wallDeltaMs - monoDeltaMs
      if (sleptMs <= threshold) return undefined
    } else {
      if (wallDeltaMs <= threshold) return undefined
      sleptMs = Math.max(0, wallDeltaMs - this.intervalMs)
    }
    try {
      this.deps.log?.("wake", { sleptMs, wallDeltaMs, monoDeltaMs: Math.round(monoDeltaMs) })
    } catch { /* logging never blocks wake handling */ }
    for (const fn of this.listeners) {
      try { fn(sleptMs) } catch (err) {
        try { this.deps.log?.("wake_listener_failed", { err: String(err) }) } catch {}
      }
    }
    return sleptMs
  }
}

export interface WakeActions {
  /** Sleep is not idleness: move idle baselines forward. */
  shiftIdle(sleptMs: number): void
  /** Reconnect the relay now (new lease, fresh frpc). */
  refreshRelay(): Promise<void>
  /** The power source and git may have changed while asleep. */
  recheckGit(): Promise<unknown>
  refreshKeepAwake(): Promise<void>
  /** Frames for clients that stay connected. */
  broadcast(frames: object[]): void
  /** Ask every main client to resubscribe (a fresh snapshot). Returns how many. */
  resyncClients(): number
  log(event: string, data: Record<string, unknown>): void
}

/** What the broker does after a wake. Never throws; async parts only log their failures. */
export function runWakeActions(a: WakeActions, sleptMs: number, frames: () => object[]): void {
  const guard = (name: string, fn: () => unknown) => {
    try {
      const r = fn()
      if (r && typeof (r as Promise<unknown>).catch === "function") {
        ;(r as Promise<unknown>).catch((err) => a.log("wake_action_failed", { action: name, err: String(err) }))
      }
    } catch (err) {
      a.log("wake_action_failed", { action: name, err: String(err) })
    }
  }
  guard("shift_idle", () => a.shiftIdle(sleptMs))
  guard("relay", () => a.refreshRelay())
  guard("git", () => a.recheckGit())
  guard("keep_awake", () => a.refreshKeepAwake())
  guard("broadcast", () => a.broadcast(frames()))
  guard("resync", () => a.log("wake_clients_resynced", { sleptMs, clients: a.resyncClients() }))
}
