import { describe, expect, test } from "bun:test"
import { SLEEP_GAP_MS, TickGap, WAKE_TICK_MS, WakeDetector } from "./wake"

function harness() {
  let t = 1_000_000
  let tick: (() => void) | undefined
  let interval = 0
  let cleared = false
  const logs: Array<[string, Record<string, unknown>]> = []
  const d = new WakeDetector({
    now: () => t,
    setInterval: (fn, ms) => { tick = fn; interval = ms; return 7 },
    clearInterval: (h) => { if (h === 7) cleared = true },
    log: (e, data) => logs.push([e, data]),
  })
  const woke: number[] = []
  d.onWake((ms) => woke.push(ms))
  return {
    d, woke, logs,
    get interval() { return interval },
    get cleared() { return cleared },
    /** Move the wall clock forward [ms] and fire one tick. */
    step(ms: number) { t += ms; tick!() },
    advance(ms: number) { t += ms },
  }
}

describe("WakeDetector", () => {
  test("ticks every 5 s; regular ticks are not wakes", () => {
    const h = harness()
    h.d.start()
    expect(h.interval).toBe(WAKE_TICK_MS)
    for (let i = 0; i < 20; i++) h.step(5_000)
    h.step(29_000) // a busy event loop, not a sleep
    expect(h.woke).toEqual([])
  })

  test("a wall-clock gap over 30 s is a wake: logs `wake {sleptMs}` then calls listeners", () => {
    const h = harness()
    h.d.start()
    h.step(5_000)
    h.step(3_600_000)
    expect(h.woke).toEqual([3_600_000 - WAKE_TICK_MS])
    expect(h.logs).toEqual([["wake", { sleptMs: 3_600_000 - WAKE_TICK_MS }]])
    h.step(5_000)
    expect(h.woke).toHaveLength(1)
  })

  test("check() outside the tick detects the gap first; the next tick does not fire again", () => {
    const h = harness()
    h.d.start()
    h.advance(10 * 60_000)
    expect(h.d.check()).toBe(10 * 60_000 - WAKE_TICK_MS)
    h.step(1_000)
    expect(h.woke).toHaveLength(1)
  })

  test("a throwing listener does not stop the others", () => {
    const h = harness()
    const seen: number[] = []
    h.d.onWake(() => { throw new Error("x") })
    h.d.onWake((ms) => seen.push(ms))
    h.d.start()
    h.step(SLEEP_GAP_MS + 1)
    expect(seen).toHaveLength(1)
    expect(h.logs.map(([e]) => e)).toContain("wake_listener_failed")
  })

  test("a backwards clock jump is not a wake; stop clears the interval", () => {
    const h = harness()
    h.d.start()
    h.step(-60_000)
    expect(h.woke).toEqual([])
    h.d.stop()
    expect(h.cleared).toBe(true)
  })
})

describe("TickGap", () => {
  test("0 for regular ticks; the slept time beyond the interval for a gap", () => {
    const g = new TickGap(15_000)
    expect(g.gap(0)).toBe(0)
    expect(g.gap(15_000)).toBe(0)
    expect(g.gap(15_000 + 45_000)).toBe(0) // exactly interval + 30 s
    expect(g.gap(60_000 + 15_000 + 600_000)).toBe(600_000)
    expect(g.gap(60_000 + 15_000 + 600_000 + 15_000)).toBe(0)
  })
})
