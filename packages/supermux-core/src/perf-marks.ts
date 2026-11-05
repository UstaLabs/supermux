/**
 * Startup timing marks (supermux card #75): named points inside a session's launch and first turn
 * (keeper ready, agent initialized, prompt written, first frames back), so a host can time where a
 * new session's wait goes. A host that wants them sets one sink; without a sink every mark is a
 * no-op. Internal: not exported from the package entry, and may change or go away.
 */
export type PerfMarkSink = (sessionId: string, event: string, fields?: Record<string, unknown>) => void

let sink: PerfMarkSink | undefined

/** Sets (or, with undefined, clears) the one sink every mark goes to. */
export function setPerfMarkSink(next: PerfMarkSink | undefined): void {
  sink = next
}

/** True while a sink is set: lets a call site skip work that only a mark needs. */
export function perfMarksOn(): boolean {
  return sink !== undefined
}

/** One mark for session `sessionId`. A sink that throws never breaks the launch it times. */
export function perfMark(sessionId: string, event: string, fields?: Record<string, unknown>): void {
  if (!sink) return
  try { sink(sessionId, event, fields) } catch { /* timing must never break a launch */ }
}

/** Milliseconds since `start` (a `performance.now()` reading), to one decimal. */
export function msSince(start: number): number {
  return Math.round((performance.now() - start) * 10) / 10
}
