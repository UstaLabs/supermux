// Sleep/wake detection by wall-clock gaps (spec 2026-09-30-desktop-hosting-lifecycle-design, "Wake
// reconnect, sleep ≠ idle"). A 5 s interval notes the wall clock at every tick; a tick that finds
// more than 30 s since the previous one means the computer slept (or the process was frozen) in
// between. The wall clock is what counts: on macOS the monotonic clock can stop during sleep, so
// `performance.now()` deltas would hide the gap.

export const WAKE_TICK_MS = 5_000
/** A wall-clock gap between ticks above this is a sleep. */
export const SLEEP_GAP_MS = 30_000

export interface WakeDetectorDeps {
  now?: () => number
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
  private timer: unknown = undefined
  private readonly listeners = new Set<(sleptMs: number) => void>()

  constructor(private readonly deps: WakeDetectorDeps = {}) {}

  private now(): number {
    return this.deps.now?.() ?? Date.now()
  }

  private get intervalMs(): number {
    return this.deps.intervalMs ?? WAKE_TICK_MS
  }

  start(): void {
    if (this.timer !== undefined) return
    this.last = this.now()
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
    const prev = this.last
    this.last = now
    if (prev === undefined) return undefined
    const elapsed = now - prev
    if (elapsed <= (this.deps.thresholdMs ?? SLEEP_GAP_MS)) return undefined
    const sleptMs = Math.max(0, elapsed - this.intervalMs)
    try { this.deps.log?.("wake", { sleptMs }) } catch { /* logging never blocks wake handling */ }
    for (const fn of this.listeners) {
      try { fn(sleptMs) } catch (err) {
        try { this.deps.log?.("wake_listener_failed", { err: String(err) }) } catch {}
      }
    }
    return sleptMs
  }
}
