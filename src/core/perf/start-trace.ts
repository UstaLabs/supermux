// Card #75: one trace per session start, from the launcher's send (or an agent's spawn_session /
// delegate call) to the agent's first reply, in the same perf.jsonl as card #54's load-time trace.
// The app names its start ("S…", sent on `x-mux-trace`); a start without one (an agent's tool call,
// an older app) gets a trace minted here. Marks before the session has an id carry the trace
// explicitly; once `bind` ties the id to it, marks by session id find it: the core's perf marks
// (keeper, initialize, first frames) and the first turn's events. A trace stops taking marks after
// the first turn ends or START_TRACE_TTL_MS, so a session's later turns are never timed as its start.
// Off (the default) begin() returns undefined and every mark is a no-op.
import { randomInt } from "crypto"
import type { PerfLog } from "./perf-log"

export const START_TRACE_TTL_MS = 10 * 60_000

/** Every start event's name begins with this, so the timeline can tell them apart. */
export const START_EVENT_PREFIX = "start."

const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
const APP_TRACE = /^S[A-Za-z0-9._-]{1,39}$/

type Open = { trace: string; until: number; seen: Set<string> }

export class StartTraces {
  private log: PerfLog | undefined
  private readonly open = new Map<string, Open>()

  constructor(private readonly now: () => number = () => Date.now()) {}

  /** The broker's perf log (main.ts); without one nothing is ever traced. */
  setLog(log: PerfLog | undefined): void {
    this.log = log
  }

  /** A new start's trace id while tracing is on: the app's own ("S…") or a fresh one. Undefined while off. */
  begin(fromApp?: string): string | undefined {
    if (!this.log?.enabled()) return undefined
    if (fromApp && APP_TRACE.test(fromApp)) return fromApp
    let id = "S"
    for (let i = 0; i < 7; i++) id += ALPHABET[randomInt(ALPHABET.length)]
    return id
  }

  /** A mark under [trace] (a no-op without one): `start.<event>`. */
  mark(trace: string | undefined, event: string, fields?: Record<string, unknown>): void {
    if (!trace || !this.log) return
    this.log.record({ ...fields, event: START_EVENT_PREFIX + event, trace })
  }

  /** From now on, marks for session [sessionId] go to [trace]. */
  bind(sessionId: string, trace: string | undefined): void {
    if (!trace) return
    const now = this.now()
    for (const [id, o] of this.open) if (now > o.until) this.open.delete(id)
    this.open.set(sessionId, { trace, until: now + START_TRACE_TTL_MS, seen: new Set() })
  }

  /** The open start trace of [sessionId], if any. */
  traceOf(sessionId: string): string | undefined {
    const o = this.open.get(sessionId)
    if (!o) return undefined
    if (this.now() > o.until) {
      this.open.delete(sessionId)
      return undefined
    }
    return o.trace
  }

  /** A mark for a bound session; with [once], only the first mark of that name counts. */
  markSession(sessionId: string, event: string, fields?: Record<string, unknown>, once = false): void {
    const trace = this.traceOf(sessionId)
    if (!trace) return
    if (once) {
      const seen = this.open.get(sessionId)!.seen
      if (seen.has(event)) return
      seen.add(event)
    }
    this.mark(trace, event, { ...fields, session: sessionId })
  }

  /** The start is over (its first turn ended, or it failed): later marks for the session are dropped. */
  close(sessionId: string): void {
    this.open.delete(sessionId)
  }
}

/** The broker's one registry (main.ts sets its log); the spawn path and the core's perf marks use it. */
export const startTraces = new StartTraces()

/** Milliseconds since [start] (a `performance.now()` reading), to one decimal. */
export function msSince(start: number): number {
  return Math.round((performance.now() - start) * 10) / 10
}
