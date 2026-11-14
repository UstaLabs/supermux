// #146: each session's limit readings, what its turns cost, and the advice at a turn's end (and the opt-in auto mode).
import { expect, test } from "bun:test"
import { CompactAdvisor } from "./compact-advisor"

const NOW = Date.parse("2026-11-14T10:00:00Z")
const resets = Math.floor((NOW + 150 * 60_000) / 1000)
const claude = (utilization: number) => ({ status: "allowed", unifiedWindows: { five_hour: { utilization, resetsAt: resets } } })

function setup(auto = false) {
  const advice: Array<[string, unknown]> = []
  const compacted: string[] = []
  const a = new CompactAdvisor({ now: () => NOW, autoEnabled: () => auto, onAdvice: (id, x) => advice.push([id, x]), compact: (id) => { compacted.push(id) } })
  return { a, advice, compacted }
}

test("a turn's cost is the window's move across it; the advice comes at its end and is cleared when it no longer holds", () => {
  const { a, advice } = setup()
  a.noteRateLimits("s1", "claude", claude(0.70))
  a.turnStarted("s1")
  a.noteRateLimits("s1", "claude", claude(0.85))
  a.turnCompleted("s1", "claude", 400_000)
  // One turn cost 15 points: suggest from 70%; at 85% → suggest.
  expect(advice).toEqual([["s1", { usedPct: 85, resetsAt: resets * 1000, auto: false }]])
  // After a compaction the context is small: the advice goes away (once).
  a.turnStarted("s1")
  a.turnCompleted("s1", "claude", 20_000)
  a.turnStarted("s1")
  a.turnCompleted("s1", "claude", 20_000)
  expect(advice).toEqual([["s1", { usedPct: 85, resetsAt: resets * 1000, auto: false }], ["s1", null]])
})

test("the automatic mode compacts once per window, only when it is on and at 95% or more", () => {
  const off = setup(false)
  off.a.noteRateLimits("s1", "claude", claude(0.96))
  off.a.turnStarted("s1"); off.a.turnCompleted("s1", "claude", 400_000)
  expect(off.compacted).toEqual([])

  const on = setup(true)
  on.a.noteRateLimits("s1", "claude", claude(0.96))
  on.a.turnStarted("s1"); on.a.turnCompleted("s1", "claude", 400_000)
  on.a.turnStarted("s1"); on.a.turnCompleted("s1", "claude", 400_000)
  expect(on.compacted).toEqual(["s1"])
  expect(on.advice[0]).toEqual(["s1", { usedPct: 96, resetsAt: resets * 1000, auto: true }])
})

test("an agent without an in-stream limit gets no advice; forget() drops a session", () => {
  const { a, advice } = setup(true)
  a.noteRateLimits("g", "grok", { anything: 1 })
  a.turnStarted("g"); a.turnCompleted("g", "grok", 400_000)
  expect(advice).toEqual([])
  a.noteRateLimits("s1", "claude", claude(0.9))
  a.forget("s1")
  a.turnStarted("s1"); a.turnCompleted("s1", "claude", 400_000)
  expect(advice).toEqual([])
})
