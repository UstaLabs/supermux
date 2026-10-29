// A delegated child's or council member's turn that ended in an error (decision #17, 2026-10-30). Its
// parent (or the council's caller) hears about it, unless the agent died with it (the crash notice
// covers that) or an account switch moves the session on and it carries on there.

/** How long to wait before deciding: when the agent dies mid-turn, its failed turn comes before session-failed. */
export const FAILED_TURN_GRACE_MS = 1000

export type FailedTurnDeps = {
  /** True while the session's agent runs (the adapter's isAlive). */
  isAlive(): boolean
  /** True when an account switch moves the session on; read when the turn ended (the adapter's willSwitchAccount). */
  continues: Promise<boolean>
  /** The parent or the council's caller hears about it. */
  report(error: string): void
  wait(ms: number): Promise<void>
}

export async function failedTurn(error: string, deps: FailedTurnDeps, graceMs = FAILED_TURN_GRACE_MS): Promise<void> {
  const continues = deps.continues.catch(() => false)
  await deps.wait(graceMs)
  if (!deps.isAlive()) return
  if (await continues) return
  deps.report(error)
}
