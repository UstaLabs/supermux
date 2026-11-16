// #146: the advice to compact before a usage-limit wall, per chat; with the opt-in setting on, the Compact itself.
// The 5-hour meter belongs to the ACCOUNT, shared by every chat on it: each new Claude reading updates the account's
// window and re-evaluates the advice SHOWN in every chat on that account (broker arithmetic and a client update
// only, never a message to an agent, never a wake). The automatic Compact stays strictly at that chat's own turn end.
// Why (2026-11-16, real data): the chats Ahmet was in when the wall came had stale readings, because other chats on
// the account burned the meter while they sat idle, so per-chat evaluation only ever lit the chats doing the burning.
import { DEFAULT_CACHE_LIFE_MS, adviseCompact, limitWindow, type LimitWindow } from "./compact-advice"

export type CompactAdviceView = { usedPct: number; resetsAt: number; auto: boolean }

/** Why the advice for a chat changed: its own turn ended, another reading on its account came in, or it compacted. */
export type AdviceReason = "turn-end" | "account-reading" | "compacted"
export type AdviceChange = { account: string; usedPct?: number; threshold?: number; reason: AdviceReason }

type State = {
  account?: string
  turnStartPct?: number
  advice?: CompactAdviceView
  autoDone?: number
  cacheLifeMs?: number
  /** The chat's context at its last turn end (an idle chat is judged on it when its account's meter moves). */
  contextUsed?: number
}

export class CompactAdvisor {
  private readonly sessions = new Map<string, State>()
  /** Each account's 5-hour window as its chats last read it. */
  private readonly windows = new Map<string, LimitWindow>()
  /**
   * What the account's last turns cost (any chat's), the margin every chat on it is judged by: the meter is shared,
   * so an idle chat whose own turns were cheap still hits the wall when a busy one burns it (the by-hand e2e).
   */
  private readonly costs = new Map<string, number[]>()

  constructor(private readonly deps: {
    now: () => number
    /** The host setting "Compact before waiting for a reset". */
    autoEnabled: () => boolean
    /** The advice for a session changed (null: none now), and why. */
    onAdvice: (sessionId: string, advice: CompactAdviceView | null, change: AdviceChange) => void
    /** Run a Compact (the automatic mode). */
    compact: (sessionId: string) => void
    /** The Claude account a chat runs on (its id); undefined: unknown (the chat then has a window of its own). */
    accountOf?: (sessionId: string) => string | undefined
    /** The Claude chats on an account (to re-judge when its meter moves). */
    sessionsOn?: (account: string) => string[]
    /** A chat's context size now, when known. */
    contextOf?: (sessionId: string) => number | undefined
    /**
     * Team seats: whether auto-switch would carry this chat to another seat of its Anthropic org with room below
     * `threshold`. The prompt cache is per org, so the chat would stay warm there: no compacting needed.
     */
    teammateCanTakeOver?: (sessionId: string, threshold: number) => Promise<boolean>
  }) {}

  /** How long this chat's prompt cache lives, as its latest request's cache writes said (1h, or 5m on overage). */
  noteCacheLife(sessionId: string, _agent: string, ms: number): void {
    this.state(sessionId).cacheLifeMs = ms
  }

  /**
   * A Claude rate-limit frame from one chat: its account's window moves, and every chat on that account has its
   * shown advice re-judged (never a Compact: that waits for a chat's own turn end).
   */
  noteRateLimits(sessionId: string, agent: string, raw: unknown): void {
    const window = limitWindow(agent, raw)
    if (!window) return
    const account = this.accountKey(sessionId)
    this.state(sessionId).account = account
    this.windows.set(account, window)
    const others = this.deps.sessionsOn?.(account) ?? []
    for (const id of new Set([sessionId, ...others])) {
      this.state(id).account = account
      void this.evaluate(id, "account-reading", false)
    }
  }

  turnStarted(sessionId: string): void {
    const s = this.sessions.get(sessionId)
    if (s) s.turnStartPct = this.windowOf(s)?.usedPct
  }

  /**
   * At a chat's own turn end: what the turn cost (the account's margin moves, so the other chats on it are re-judged
   * for display), its advice, and (only here, only for this chat) the automatic Compact.
   */
  async turnCompleted(sessionId: string, _agent: string, contextUsed: number | undefined): Promise<void> {
    const s = this.sessions.get(sessionId)
    if (!s) return
    const w = this.windowOf(s)
    let costMoved = false
    if (s.account && s.turnStartPct != null && w && w.usedPct >= s.turnStartPct) {
      const costs = this.costs.get(s.account) ?? []
      costs.push(w.usedPct - s.turnStartPct)
      if (costs.length > 3) costs.shift()
      this.costs.set(s.account, costs)
      costMoved = true
    }
    s.turnStartPct = undefined
    if (contextUsed != null) s.contextUsed = contextUsed
    await this.evaluate(sessionId, "turn-end", true)
    if (!costMoved || !s.account) return
    for (const id of this.deps.sessionsOn?.(s.account) ?? []) {
      if (id === sessionId) continue
      this.state(id).account = s.account
      void this.evaluate(id, "account-reading", false)
    }
  }

  /** A compaction ended: whatever was advised is done (the next reading or turn end advises again if it must). */
  compacted(sessionId: string): void {
    const s = this.sessions.get(sessionId)
    if (!s?.advice) return
    s.advice = undefined
    this.deps.onAdvice(sessionId, null, { account: s.account ?? this.accountKey(sessionId), reason: "compacted" })
  }

  /** The advice now shown for a session (the snapshot). */
  current(sessionId: string): CompactAdviceView | undefined {
    return this.sessions.get(sessionId)?.advice
  }

  forget(sessionId: string): void {
    this.sessions.delete(sessionId)
  }

  private async evaluate(sessionId: string, reason: AdviceReason, mayCompact: boolean): Promise<void> {
    const s = this.state(sessionId)
    const contextUsed = s.contextUsed ?? this.deps.contextOf?.(sessionId)
    // An idle chat whose size isn't known (nothing reported since the broker started) is left as it is.
    if (contextUsed == null) return
    s.contextUsed = contextUsed
    const window = this.windowOf(s)
    const advice = adviseCompact({
      agent: "claude", contextUsed, window, now: this.deps.now(), recentTurnCosts: (s.account && this.costs.get(s.account)) || [],
      cacheLifeMs: s.cacheLifeMs ?? DEFAULT_CACHE_LIFE_MS,
    })
    // A teammate seat in the same org would take the chat over warm: then the wall costs nothing to wait out.
    const covered = advice.suggest && (await this.deps.teammateCanTakeOver?.(sessionId, advice.threshold)) === true
    const next = advice.suggest && !covered && window ? { usedPct: window.usedPct, resetsAt: window.resetsAt, auto: advice.auto } : undefined
    if (JSON.stringify(next) !== JSON.stringify(s.advice)) {
      s.advice = next
      this.deps.onAdvice(sessionId, next ?? null, {
        account: s.account ?? this.accountKey(sessionId), reason, threshold: advice.threshold,
        ...(window ? { usedPct: window.usedPct } : {}),
      })
    }
    if (mayCompact && next?.auto && this.deps.autoEnabled() && s.autoDone !== next.resetsAt) {
      s.autoDone = next.resetsAt
      this.deps.compact(sessionId)
    }
  }

  private accountKey(sessionId: string): string {
    return this.deps.accountOf?.(sessionId) ?? `session:${sessionId}`
  }

  private windowOf(s: State): LimitWindow | undefined {
    return s.account ? this.windows.get(s.account) : undefined
  }

  private state(sessionId: string): State {
    let s = this.sessions.get(sessionId)
    if (!s) this.sessions.set(sessionId, (s = {}))
    return s
  }
}
