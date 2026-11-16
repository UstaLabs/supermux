// #146: each session's limit readings, what its turns cost, and the advice at a turn's end (and the opt-in auto mode).
import { expect, test } from "bun:test"
import { CompactAdvisor } from "./compact-advisor"

const NOW = Date.parse("2026-11-14T10:00:00Z")
const resets = Math.floor((NOW + 150 * 60_000) / 1000)
const claude = (utilization: number) => ({ status: "allowed", unifiedWindows: { five_hour: { utilization, resetsAt: resets } } })

function setup(auto = false, teammate = false) {
  const advice: Array<[string, unknown]> = []
  const compacted: string[] = []
  const a = new CompactAdvisor({
    now: () => NOW, autoEnabled: () => auto, onAdvice: (id, x) => advice.push([id, x]), compact: (id) => { compacted.push(id) },
    teammateCanTakeOver: async () => teammate,
  })
  return { a, advice, compacted }
}

test("a turn's cost is the window's move across it; the advice comes at its end and is cleared when it no longer holds", async () => {
  const { a, advice } = setup()
  a.noteRateLimits("s1", "claude", claude(0.70))
  a.turnStarted("s1")
  a.noteRateLimits("s1", "claude", claude(0.85))
  await a.turnCompleted("s1", "claude", 400_000)
  // One turn cost 15 points: ×3 = 45 → threshold kept at 70%; at 85% → suggest.
  expect(advice).toEqual([["s1", { usedPct: 85, resetsAt: resets * 1000, auto: false }]])
  // After a compaction the context is small: the advice goes away (once).
  a.turnStarted("s1")
  await a.turnCompleted("s1", "claude", 20_000)
  a.turnStarted("s1")
  await a.turnCompleted("s1", "claude", 20_000)
  expect(advice).toEqual([["s1", { usedPct: 85, resetsAt: resets * 1000, auto: false }], ["s1", null]])
})

test("the automatic mode compacts once per window, only when it is on and at 95% or more", async () => {
  const off = setup(false)
  off.a.noteRateLimits("s1", "claude", claude(0.96))
  off.a.turnStarted("s1"); await off.a.turnCompleted("s1", "claude", 400_000)
  expect(off.compacted).toEqual([])

  const on = setup(true)
  on.a.noteRateLimits("s1", "claude", claude(0.96))
  on.a.turnStarted("s1"); await on.a.turnCompleted("s1", "claude", 400_000)
  on.a.turnStarted("s1"); await on.a.turnCompleted("s1", "claude", 400_000)
  expect(on.compacted).toEqual(["s1"])
  expect(on.advice[0]).toEqual(["s1", { usedPct: 96, resetsAt: resets * 1000, auto: true }])
})

test("an agent without an in-stream limit gets no advice; forget() drops a session", async () => {
  const { a, advice } = setup(true)
  a.noteRateLimits("g", "grok", { anything: 1 })
  a.turnStarted("g"); await a.turnCompleted("g", "grok", 400_000)
  expect(advice).toEqual([])
  a.noteRateLimits("s1", "claude", claude(0.9))
  a.forget("s1")
  a.turnStarted("s1"); await a.turnCompleted("s1", "claude", 400_000)
  expect(advice).toEqual([])
})

test("a teammate seat of the same org taking over warm means no advice and no automatic Compact", async () => {
  const { a, advice, compacted } = setup(true, true)
  a.noteRateLimits("s1", "claude", claude(0.97))
  a.turnStarted("s1"); await a.turnCompleted("s1", "claude", 400_000)
  expect(advice).toEqual([])
  expect(compacted).toEqual([])
})

test("the cache life the requests reported decides whether a reset is far enough away", async () => {
  const soon = Math.floor((NOW + 30 * 60_000) / 1000)
  const reading = (u: number) => ({ status: "allowed", unifiedWindows: { five_hour: { utilization: u, resetsAt: soon } } })
  // A 10-point turn (80% → 90%): suggest from 70%. 1-hour cache (the default): a reset 30 min away finds it warm.
  const one = setup()
  one.a.noteRateLimits("s1", "claude", reading(0.8))
  one.a.turnStarted("s1"); one.a.noteRateLimits("s1", "claude", reading(0.9)); await one.a.turnCompleted("s1", "claude", 400_000)
  expect(one.advice).toEqual([])
  // 5-minute cache (an account on overage): the same reset is past it.
  const five = setup()
  five.a.noteRateLimits("s1", "claude", reading(0.8))
  five.a.noteCacheLife("s1", "claude", 5 * 60_000)
  five.a.turnStarted("s1"); five.a.noteRateLimits("s1", "claude", reading(0.9)); await five.a.turnCompleted("s1", "claude", 400_000)
  expect(five.advice).toEqual([["s1", { usedPct: 90, resetsAt: soon * 1000, auto: false }]])
})

// Account-wide (Ahmet, 2026-11-16): the 5-hour meter is the account's. On Nov 14 the chats he was in had stale
// readings (#90: 36% at its last turn end, the wall at its next) while other chats burned the shared meter.
function teamOf(sessions: Record<string, string>, contexts: Record<string, number>, auto = false) {
  const advice: Array<[string, unknown, string]> = []
  const compacted: string[] = []
  const a = new CompactAdvisor({
    now: () => NOW, autoEnabled: () => auto,
    onAdvice: (id, x, change) => advice.push([id, x, change.reason]),
    compact: (id) => { compacted.push(id) },
    accountOf: (id) => sessions[id],
    sessionsOn: (account) => Object.keys(sessions).filter((id) => sessions[id] === account),
    contextOf: (id) => contexts[id],
  })
  return { a, advice, compacted }
}
const flushAsync = () => new Promise((r) => setTimeout(r, 0))

test("another chat's reading lights the advice in an idle chat on the same account, not on another account", async () => {
  const { a, advice } = teamOf({ busy: "acct-1", idle: "acct-1", other: "acct-2" }, { busy: 300_000, idle: 400_000, other: 500_000 })
  a.noteRateLimits("busy", "claude", claude(0.40))
  await flushAsync()
  expect(advice).toEqual([])
  // The busy chat pushes the shared meter to 85%: the idle chat (and the busy one) show it, the other account's doesn't.
  a.noteRateLimits("busy", "claude", claude(0.85))
  await flushAsync()
  const shown = advice.filter(([, x]) => x !== null).map(([id, , why]) => `${id}:${why}`).sort()
  expect(shown).toEqual(["busy:account-reading", "idle:account-reading"])
})

test("another chat's reading never compacts an idle chat; only a chat's own turn end may", async () => {
  const { a, compacted } = teamOf({ busy: "acct-1", idle: "acct-1" }, { busy: 300_000, idle: 400_000 }, true)
  a.noteRateLimits("busy", "claude", claude(0.97))
  await flushAsync()
  expect(compacted).toEqual([])
  a.turnStarted("idle"); await a.turnCompleted("idle", "claude", 400_000)
  expect(compacted).toEqual(["idle"])
})

test("a chat whose size isn't known yet is left alone; every change says why", async () => {
  const { a, advice } = teamOf({ busy: "acct-1", fresh: "acct-1" }, { busy: 300_000 })
  a.noteRateLimits("busy", "claude", claude(0.90))
  await flushAsync()
  expect(advice.map(([id]) => id)).toEqual(["busy"])
  a.compacted("busy")
  expect(advice.at(-1)).toEqual(["busy", null, "compacted"])
})

// Found in the by-hand e2e (2026-11-16): an idle chat whose own last turn was cheap kept a 95% threshold while the
// busy chat's turns were moving the shared meter 25 points each, so at 75% and 80% only the busy chat was advised.
test("the margin is the account's: an idle chat whose own turns were cheap is judged by what the account's turns cost", async () => {
  const { a, advice } = teamOf({ busy: "acct-1", idle: "acct-1" }, { busy: 300_000, idle: 400_000 })
  a.noteRateLimits("busy", "claude", claude(0.50))
  // The idle chat's own turn moved nothing.
  a.turnStarted("idle"); await a.turnCompleted("idle", "claude", 400_000)
  // The busy chat's turn costs 25 points: ×3 = 75 → threshold 70% for every chat on the account.
  a.turnStarted("busy")
  a.noteRateLimits("busy", "claude", claude(0.75))
  await a.turnCompleted("busy", "claude", 300_000)
  await flushAsync()
  const shown = advice.filter(([, x]) => x !== null).map(([id]) => id).sort()
  expect(shown).toEqual(["busy", "idle"])
})
