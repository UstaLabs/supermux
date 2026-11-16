// #146: suggest compacting BEFORE a usage-limit wait (the prompt cache dies long before the reset, and at the wall
// nothing runs, not even a compaction). Research: ~/.mux/prototypes/146-compact-before-wall/report.md; backtest
// (2026-11-15) on 32 real Claude walls: /tmp/bt146.
import { expect, test } from "bun:test"
import { DEFAULT_CACHE_LIFE_MS, adviseCompact, limitWindow } from "./compact-advice"

const NOW = Date.parse("2026-11-14T10:00:00Z")
const inMin = (m: number) => NOW + m * 60_000

test("limitWindow reads Claude's rate_limit_event (five_hour utilization 0..1, resetsAt in seconds)", () => {
  // Real 2.1.308 shape.
  const info = { status: "allowed", resetsAt: 1791651000, rateLimitType: "five_hour", unifiedWindows: { five_hour: { utilization: 0.88, resetsAt: 1791651000 }, seven_day: { utilization: 0.49, resetsAt: 1791806400 } } }
  expect(limitWindow("claude", info)).toEqual({ usedPct: 88, resetsAt: 1791651000_000, rejected: false })
  expect(limitWindow("claude", { ...info, status: "rejected" })?.rejected).toBe(true)
  expect(limitWindow("claude", { status: "allowed" })).toBeUndefined()
})

test("Claude only for now: Codex's windows keep changing and its backtest had 7 walls", () => {
  const rl = { rateLimits: { primary: { usedPercent: 86, windowDurationMins: 300, resetsAt: 1791651000 }, planType: "plus" } }
  expect(limitWindow("codex", rl)).toBeUndefined()
  expect(limitWindow("grok", rl)).toBeUndefined()
  expect(adviseCompact({ agent: "codex", contextUsed: 400_000, now: NOW, recentTurnCosts: [], cacheLifeMs: DEFAULT_CACHE_LIFE_MS, window: { usedPct: 90, resetsAt: inMin(150), rejected: false } }).suggest).toBe(false)
})

const base = { agent: "claude", contextUsed: 400_000, now: NOW, recentTurnCosts: [] as number[], cacheLifeMs: DEFAULT_CACHE_LIFE_MS }
const at = (usedPct: number, resetsIn = 150) => ({ usedPct, resetsAt: inMin(resetsIn), rejected: false })

test("fires when one more turn like the recent ones, times three, could reach the wall (kept to 70–95%)", () => {
  // Nothing measured yet: assume a 10-point turn, ×3: from 70%.
  expect(adviseCompact({ ...base, window: at(69) }).suggest).toBe(false)
  expect(adviseCompact({ ...base, window: at(70) })).toMatchObject({ suggest: true, auto: false, threshold: 70 })
  // Costliest recent turn 5 points: 100 − 15 = 85%.
  expect(adviseCompact({ ...base, recentTurnCosts: [5, 3, 4], window: at(84) })).toMatchObject({ suggest: false, threshold: 85 })
  expect(adviseCompact({ ...base, recentTurnCosts: [5, 3, 4], window: at(85) })).toMatchObject({ suggest: true, threshold: 85 })
  // A slow chat (1-point turns) waits until 95%; a heavy one never earlier than 70%.
  expect(adviseCompact({ ...base, recentTurnCosts: [1, 1], window: at(94) })).toMatchObject({ suggest: false, threshold: 95 })
  expect(adviseCompact({ ...base, recentTurnCosts: [25], window: at(70) })).toMatchObject({ suggest: true, threshold: 70 })
  // From 95%: the automatic mode may act (the request that crosses 100% still finishes).
  expect(adviseCompact({ ...base, recentTurnCosts: [1], window: at(96) })).toMatchObject({ suggest: true, auto: true })
})

test("no suggestion when it can't pay: a small context, a reset sooner than the cache dies, already at the wall", () => {
  const near = at(90)
  // Under ~60k context a cold re-read costs less than compacting.
  expect(adviseCompact({ ...base, contextUsed: 59_000, window: near }).suggest).toBe(false)
  // Claude's cache lives an hour: a reset 50 min away finds it warm.
  expect(adviseCompact({ ...base, window: at(90, 50) }).suggest).toBe(false)
  // On 5-minute caching (what the requests said: an account on overage, an API key), 40 min away is already cold.
  expect(adviseCompact({ ...base, cacheLifeMs: 5 * 60_000, window: at(90, 40) }).suggest).toBe(true)
  expect(adviseCompact({ ...base, cacheLifeMs: 5 * 60_000, window: at(90, 4) }).suggest).toBe(false)
  // At the wall nothing runs, not even a compaction.
  expect(adviseCompact({ ...base, window: { ...near, usedPct: 100, rejected: true } }).suggest).toBe(false)
  // No window known, no advice.
  expect(adviseCompact({ ...base, window: undefined }).suggest).toBe(false)
})
