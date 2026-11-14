// #143 (team rule: record the actor, not only the change): who answered a prompt. The web channel notes the answering
// device when it hands an answer to the agent; the request's close takes it, so request_closed and the broker log say
// "allowed by <device>" (a user, once there are users). A note nobody takes (the agent never closed the prompt)
// expires.
export class AnsweredBy {
  private readonly notes = new Map<string, { by: string; at: number }>()
  private readonly now: () => number
  private readonly ttlMs: number

  constructor(options: { now?: () => number; ttlMs?: number } = {}) {
    this.now = options.now ?? Date.now
    this.ttlMs = options.ttlMs ?? 10 * 60_000
  }

  note(sessionId: string, requestId: string, by: string): void {
    const now = this.now()
    for (const [key, value] of this.notes) if (now - value.at > this.ttlMs) this.notes.delete(key)
    this.notes.set(`${sessionId}\0${requestId}`, { by, at: now })
  }

  take(sessionId: string, requestId: string): string | undefined {
    const key = `${sessionId}\0${requestId}`
    const value = this.notes.get(key)
    this.notes.delete(key)
    return value && this.now() - value.at <= this.ttlMs ? value.by : undefined
  }
}
