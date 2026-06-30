// The Stop funnel, extracted so it is unit-testable. It dispatches the agent's
// own interrupt(); an optional onClear (when a caller supplies one) runs even if
// the interrupt fails. The broker-issued path supplies NO onClear — idle is
// reflected from the session itself (Claude's transcript interrupt marker).
export async function runInterrupt(deps: {
  adapter?: { interrupt: () => Promise<void> }
  onClear?: () => void
}): Promise<{ ok: boolean; reason?: string }> {
  if (!deps.adapter) {
    deps.onClear?.()
    return { ok: false, reason: "session not interruptible" }
  }
  try {
    await deps.adapter.interrupt()
    return { ok: true }
  } catch (err: any) {
    return { ok: false, reason: err?.message ?? String(err) }
  } finally {
    // Run onClear if a caller provided one — even on a failed interrupt. The
    // broker-issued path provides none; idle is reflected from the session.
    deps.onClear?.()
  }
}
