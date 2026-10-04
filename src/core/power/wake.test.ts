import { describe, expect, test } from "bun:test"
import { SLEEP_GAP_MS, TickGap, WAKE_TICK_MS, WakeDetector, runWakeActions } from "./wake"

function harness(platform: NodeJS.Platform = "darwin") {
  let t = 1_000_000
  let m = 50_000
  let tick: (() => void) | undefined
  let interval = 0
  let cleared = false
  const logs: Array<[string, Record<string, unknown>]> = []
  const d = new WakeDetector({
    platform,
    now: () => t,
    mono: () => m,
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
    /** Move the wall clock forward [ms] (the monotonic clock by [mono], default the same) and tick. */
    step(ms: number, mono = ms) { t += ms; m += mono; tick!() },
    advance(ms: number, mono = ms) { t += ms; m += mono },
  }
}

describe("WakeDetector (macOS / Windows: wall clock only)", () => {
  test("ticks every 5 s; regular ticks are not wakes", () => {
    const h = harness()
    h.d.start()
    expect(h.interval).toBe(WAKE_TICK_MS)
    for (let i = 0; i < 20; i++) h.step(5_000)
    h.step(29_000) // a busy event loop, not a sleep
    expect(h.woke).toEqual([])
  })

  test("a wall-clock gap over 30 s is a wake: logs `wake` with both deltas, then calls listeners", () => {
    const h = harness()
    h.d.start()
    h.step(5_000)
    h.step(3_600_000, 3_600_000) // the monotonic clock may or may not have paused: wall decides
    expect(h.woke).toEqual([3_600_000 - WAKE_TICK_MS])
    expect(h.logs).toEqual([["wake", { sleptMs: 3_600_000 - WAKE_TICK_MS, wallDeltaMs: 3_600_000, monoDeltaMs: 3_600_000 }]])
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
    h.step(-60_000, 5_000)
    expect(h.woke).toEqual([])
    h.d.stop()
    expect(h.cleared).toBe(true)
  })
})

describe("WakeDetector (Linux: wall minus monotonic)", () => {
  test("a suspend (wall jumps, CLOCK_MONOTONIC paused) is a wake of exactly the unseen time", () => {
    const h = harness("linux")
    h.d.start()
    h.step(3_600_000 + 5_000, 5_000)
    expect(h.woke).toEqual([3_600_000])
    expect(h.logs).toEqual([["wake", { sleptMs: 3_600_000, wallDeltaMs: 3_605_000, monoDeltaMs: 5_000 }]])
  })

  test("a stall (wall and monotonic both jump) is NOT a wake", () => {
    const h = harness("linux")
    h.d.start()
    h.step(120_000, 120_000)
    h.step(5_000)
    expect(h.woke).toEqual([])
    expect(h.logs).toEqual([])
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

describe("runWakeActions", () => {
  test("shifts idle, refreshes relay/git/keep-awake, broadcasts, then resyncs clients", async () => {
    const calls: string[] = []
    const logs: Array<[string, Record<string, unknown>]> = []
    runWakeActions({
      shiftIdle: (ms) => { calls.push(`shift:${ms}`) },
      refreshRelay: async () => { calls.push("relay") },
      recheckGit: async () => { calls.push("git") },
      refreshKeepAwake: async () => { calls.push("keep_awake") },
      broadcast: (frames) => { calls.push(`broadcast:${frames.map((f: any) => f.type).join(",")}`) },
      resyncClients: () => { calls.push("resync"); return 2 },
      log: (e, d) => logs.push([e, d]),
    }, 60_000, () => [{ type: "host_requirements" }, { type: "keep_awake" }])
    expect(calls).toEqual(["shift:60000", "relay", "git", "keep_awake", "broadcast:host_requirements,keep_awake", "resync"])
    expect(logs).toContainEqual(["wake_clients_resynced", { sleptMs: 60_000, clients: 2 }])
  })

  test("a failing action (sync throw or rejection) is logged and the rest still run", async () => {
    const calls: string[] = []
    const logs: string[] = []
    runWakeActions({
      shiftIdle: () => { throw new Error("x") },
      refreshRelay: async () => { throw new Error("y") },
      recheckGit: async () => { calls.push("git") },
      refreshKeepAwake: async () => { calls.push("keep_awake") },
      broadcast: () => { calls.push("broadcast") },
      resyncClients: () => { calls.push("resync"); return 0 },
      log: (e, d) => logs.push(`${e}:${d.action ?? ""}`),
    }, 1, () => [])
    await Promise.resolve(); await Promise.resolve()
    expect(calls).toEqual(["git", "keep_awake", "broadcast", "resync"])
    expect(logs).toContain("wake_action_failed:shift_idle")
    expect(logs).toContain("wake_action_failed:relay")
  })
})
