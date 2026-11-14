// #146: suggest compacting BEFORE a usage-limit wait (the prompt cache dies long before the reset, and at the wall
// nothing runs, not even a compaction). Research: ~/.mux/prototypes/146-compact-before-wall/report.md.
import { expect, test } from "bun:test"
import { adviseCompact, limitWindow } from "./compact-advice"

const NOW = Date.parse("2026-11-14T10:00:00Z")
const inMin = (m: number) => NOW + m * 60_000

test("limitWindow reads Claude's rate_limit_event (five_hour utilization 0..1, resetsAt in seconds)", () => {
  // Real 2.1.308 shape.
  const info = { status: "allowed", resetsAt: 1791651000, rateLimitType: "five_hour", unifiedWindows: { five_hour: { utilization: 0.88, resetsAt: 1791651000 }, seven_day: { utilization: 0.49, resetsAt: 1791806400 } } }
  expect(limitWindow("claude", info)).toEqual({ usedPct: 88, resetsAt: 1791651000_000, rejected: false })
  expect(limitWindow("claude", { ...info, status: "rejected" })?.rejected).toBe(true)
  expect(limitWindow("claude", { status: "allowed" })).toBeUndefined()
})

test("limitWindow reads Codex's account/rateLimits/updated (primary usedPercent, resetsAt in seconds)", () => {
  const rl = { rateLimits: { primary: { usedPercent: 86, windowDurationMins: 300, resetsAt: 1791651000 }, secondary: { usedPercent: 40 }, planType: "plus" } }
  expect(limitWindow("codex", rl)).toEqual({ usedPct: 86, resetsAt: 1791651000_000, rejected: false })
  // At the wall Codex sends primary: null: nothing to read here.
  expect(limitWindow("codex", { rateLimits: { primary: null, credits: { hasCredits: false } } })).toBeUndefined()
  expect(limitWindow("grok", rl)).toBeUndefined()
})

const base = { agent: "claude" as const, contextUsed: 400_000, now: NOW, recentTurnCosts: [] as number[] }

test("suggests when the next turn could hit the wall, the context is big, and the reset is past the cache's life", () => {
  // No turn measured yet: assume a 10-point turn, so from 80% on.
  expect(adviseCompact({ ...base, window: { usedPct: 79, resetsAt: inMin(150), rejected: false } }).suggest).toBe(false)
  expect(adviseCompact({ ...base, window: { usedPct: 80, resetsAt: inMin(150), rejected: false } })).toMatchObject({ suggest: true, auto: false, threshold: 80 })
  // Measured turns: twice the costliest recent one, clamped to 70–95%.
  expect(adviseCompact({ ...base, recentTurnCosts: [1, 2], window: { usedPct: 94, resetsAt: inMin(150), rejected: false } })).toMatchObject({ suggest: false, threshold: 95 })
  expect(adviseCompact({ ...base, recentTurnCosts: [9, 3], window: { usedPct: 82, resetsAt: inMin(150), rejected: false } })).toMatchObject({ suggest: true, threshold: 82 })
  expect(adviseCompact({ ...base, recentTurnCosts: [40], window: { usedPct: 70, resetsAt: inMin(150), rejected: false } })).toMatchObject({ suggest: true, threshold: 70 })
  // From 95%: the automatic mode may act (the request that crosses 100% still finishes).
  expect(adviseCompact({ ...base, recentTurnCosts: [2], window: { usedPct: 96, resetsAt: inMin(150), rejected: false } })).toMatchObject({ suggest: true, auto: true })
})

test("no suggestion when it can't pay: a small context, a reset sooner than the cache dies, already at the wall", () => {
  const near = { usedPct: 90, resetsAt: inMin(150), rejected: false }
  // Under ~60k context a cold re-read costs less than compacting.
  expect(adviseCompact({ ...base, contextUsed: 59_000, window: near }).suggest).toBe(false)
  // Claude's cache lives an hour: a reset 50 min away finds it warm.
  expect(adviseCompact({ ...base, window: { ...near, resetsAt: inMin(50) } }).suggest).toBe(false)
  // Codex's ~30 min: 40 min away is already cold.
  expect(adviseCompact({ ...base, agent: "codex", window: { ...near, resetsAt: inMin(40) } }).suggest).toBe(true)
  expect(adviseCompact({ ...base, agent: "codex", window: { ...near, resetsAt: inMin(20) } }).suggest).toBe(false)
  // At the wall nothing runs, not even a compaction.
  expect(adviseCompact({ ...base, window: { ...near, usedPct: 100, rejected: true } }).suggest).toBe(false)
  // No window known, no advice.
  expect(adviseCompact({ ...base, window: undefined }).suggest).toBe(false)
})
