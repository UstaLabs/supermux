// The Stop funnel, extracted so it is unit-testable and so the live status is
// ALWAYS cleared — including when the interrupt fails. Previously a failed Stop
// left the UI stuck on "Working…" because the state-clear ran only on success.
export async function runInterrupt(deps: {
  adapter?: { interrupt: () => Promise<void> }
  onClear: () => void
}): Promise<{ ok: boolean; reason?: string }> {
  if (!deps.adapter) {
    deps.onClear()
    return { ok: false, reason: "session not interruptible" }
  }
  try {
    await deps.adapter.interrupt()
    return { ok: true }
  } catch (err: any) {
    return { ok: false, reason: err?.message ?? String(err) }
  } finally {
    // Always clear the live status — even on a failed interrupt — so the UI
    // never stays stuck "Working…". The watchdog is the backstop; the agent's
    // real turn-end reconverges on idle.
    deps.onClear()
  }
}
