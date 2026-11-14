// #146: per session, the agent's latest in-stream limit reading, what each turn cost of the window, and the advice
// at each turn's end ("compact before the wall"); with the opt-in setting on, the Compact itself at 95%+.
import { adviseCompact, limitWindow, type LimitWindow } from "./compact-advice"

export type CompactAdviceView = { usedPct: number; resetsAt: number; auto: boolean }

type State = { agent: string; window?: LimitWindow; turnStartPct?: number; costs: number[]; advice?: CompactAdviceView; autoDone?: number }

export class CompactAdvisor {
  private readonly sessions = new Map<string, State>()

  constructor(private readonly deps: {
    now: () => number
    /** The host setting "Compact before waiting for a reset". */
    autoEnabled: () => boolean
    /** The advice for a session changed (null: none now). */
    onAdvice: (sessionId: string, advice: CompactAdviceView | null) => void
    /** Run a Compact (the automatic mode). */
    compact: (sessionId: string) => void
  }) {}

  /** The agent's own rate-limit frame (Claude's rate_limit_event, Codex's rateLimits). */
  noteRateLimits(sessionId: string, agent: string, raw: unknown): void {
    const window = limitWindow(agent, raw)
    if (!window) return
    const s = this.state(sessionId, agent)
    s.window = window
  }

  turnStarted(sessionId: string): void {
    const s = this.sessions.get(sessionId)
    if (s) s.turnStartPct = s.window?.usedPct
  }

  /** At a turn's end: how much it cost, and whether to suggest (or, when allowed, run) a Compact. */
  turnCompleted(sessionId: string, agent: string, contextUsed: number | undefined): void {
    const s = this.sessions.get(sessionId)
    if (!s) return
    if (s.turnStartPct != null && s.window && s.window.usedPct >= s.turnStartPct) {
      s.costs.push(s.window.usedPct - s.turnStartPct)
      if (s.costs.length > 3) s.costs.shift()
    }
    s.turnStartPct = undefined
    const advice = adviseCompact({ agent, contextUsed: contextUsed ?? 0, window: s.window, now: this.deps.now(), recentTurnCosts: s.costs })
    const next = advice.suggest && s.window ? { usedPct: s.window.usedPct, resetsAt: s.window.resetsAt, auto: advice.auto } : undefined
    if (JSON.stringify(next) !== JSON.stringify(s.advice)) {
      s.advice = next
      this.deps.onAdvice(sessionId, next ?? null)
    }
    if (next?.auto && this.deps.autoEnabled() && s.autoDone !== next.resetsAt) {
      s.autoDone = next.resetsAt
      this.deps.compact(sessionId)
    }
  }

  /** A compaction ended: whatever was advised is done (the next turn's end advises again if it must). */
  compacted(sessionId: string): void {
    const s = this.sessions.get(sessionId)
    if (!s?.advice) return
    s.advice = undefined
    this.deps.onAdvice(sessionId, null)
  }

  /** The advice now shown for a session (the snapshot). */
  current(sessionId: string): CompactAdviceView | undefined {
    return this.sessions.get(sessionId)?.advice
  }

  forget(sessionId: string): void {
    this.sessions.delete(sessionId)
  }

  private state(sessionId: string, agent: string): State {
    let s = this.sessions.get(sessionId)
    if (!s) this.sessions.set(sessionId, (s = { agent, costs: [] }))
    return s
  }
}
