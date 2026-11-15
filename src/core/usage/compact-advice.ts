// #146: suggest compacting BEFORE a usage-limit wait. The prompt cache dies long before the limit resets (Claude 1 h,
// Codex ~30 min), the first turn after the wait re-reads the whole context uncached (Claude 30–33×, Codex 8–21× a warm
// turn, out of the fresh limit), and at the wall nothing runs, not even a compaction. So the moment is the end of the
// turn before the wall. Research and numbers: ~/.mux/prototypes/146-compact-before-wall/report.md.

/** The 5-hour window as the agent reported it in its own stream: % used, when it resets (ms), whether it refused. */
export type LimitWindow = { usedPct: number; resetsAt: number; rejected: boolean }

/** Under this a cold re-read after the wait costs less than compacting first (Claude, measured break-even ≈60k). */
export const MIN_CONTEXT = 60_000
/** From here the opt-in automatic mode compacts on its own: one more turn could cross the wall. */
export const AUTO_AT = 95
/**
 * Claude's cache life when its requests haven't said yet: 1 hour, its default on a subscription within its limits
 * (2.1.308 `promptCacheTtl`: "Unset = automatic: 1 hour on a Claude subscription within its usage limits, 5 minutes
 * on an API key…"). Each request's cache writes then tell the real one (1h vs 5m), overage included.
 */
export const DEFAULT_CACHE_LIFE_MS = 60 * 60_000
/**
 * The next turn could cost this many times the costliest recent one. Backtested on 32 real Claude walls (2026-11-15,
 * /tmp/bt146): 3× caught 66% a median 25 min ahead (17% of suggestions not followed by a wall); 2× caught 53%.
 */
const TURN_MARGIN = 3
/** Without a measured turn yet, assume the next one could cost this many points (so: from 80%). */
const DEFAULT_TURN_COST = 10

const num = (v: unknown): number | undefined => (typeof v === "number" && Number.isFinite(v) ? v : undefined)
const rec = (v: unknown): Record<string, unknown> | undefined => (v && typeof v === "object" ? (v as Record<string, unknown>) : undefined)

/**
 * The 5-hour window from Claude's own `rate_limit_event` (five_hour utilization 0..1). Claude only for now (Ahmet,
 * 2026-11-15): Codex's windows keep changing (its 5-hour window was dropped and brought back on Plus, Pro has none)
 * and its backtest had 7 walls, so no advice is better than a guess there.
 */
export function limitWindow(agent: string, raw: unknown): LimitWindow | undefined {
  const r = rec(raw)
  if (!r) return undefined
  if (agent === "claude") {
    const info = rec(r.rate_limit_info) ?? r
    const five = rec(rec(info.unifiedWindows)?.five_hour)
    const utilization = num(five?.utilization)
    const resetsAt = num(five?.resetsAt) ?? num(info.resetsAt)
    if (utilization == null || resetsAt == null) return undefined
    return { usedPct: Math.round(utilization * 100), resetsAt: resetsAt * 1000, rejected: info.status === "rejected" }
  }
  return undefined
}

export type CompactAdvice = {
  /** Offer Compact now (the line above the composer). */
  suggest: boolean
  /** The opt-in automatic mode may compact now. */
  auto: boolean
  /** The % of the window from which it is suggested, for this session's pace. */
  threshold: number
}

/**
 * Whether to suggest compacting now, at the end of a turn. The threshold comes from the meter's own pace in this chat:
 * the next turn could cost as much as three times the costliest recent one, so suggest from 100 minus that, kept between
 * 70% and 95% (a slow Max chat gets ~95, a fast Pro chat 70).
 */
export function adviseCompact(input: {
  agent: string
  contextUsed: number
  window: LimitWindow | undefined
  now: number
  /** Points of the window each recent turn of this chat cost (newest last). */
  recentTurnCosts: number[]
  /** How long this chat's prompt cache lives (from its requests' cache writes). */
  cacheLifeMs: number
}): CompactAdvice {
  const costliest = input.recentTurnCosts.length ? Math.max(...input.recentTurnCosts) : DEFAULT_TURN_COST
  const threshold = Math.min(95, Math.max(70, Math.round(100 - TURN_MARGIN * costliest)))
  const no = { suggest: false, auto: false, threshold }
  const w = input.window
  if (!w || input.agent !== "claude" || w.rejected || w.usedPct >= 100) return no
  if (input.contextUsed < MIN_CONTEXT) return no
  if (w.resetsAt - input.now <= input.cacheLifeMs) return no
  if (w.usedPct < threshold) return no
  return { suggest: true, auto: w.usedPct >= AUTO_AT, threshold }
}
