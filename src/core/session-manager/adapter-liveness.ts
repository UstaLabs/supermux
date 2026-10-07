/**
 * C3b: the "connected" source for sessions whose agent runs through supermux-core. With mux-shim a
 * host MCP server no shim connects to the session socket any more, so the socket can no longer
 * drive `connected` (the UI dot) or the crash → "dead" state. The adapter's own liveness
 * (CoreAdapter.isAlive: open, restarting or being reopened by the core) does: main.ts sweeps it
 * and feeds the edges into the same applyConnectionStatus the socket used.
 */
export function sweepAdapterLiveness(
  rows: Iterable<{ id: string; connected: boolean }>,
  alive: (id: string) => boolean,
  apply: (id: string, connected: boolean) => void,
): void {
  for (const row of rows) {
    const now = alive(row.id)
    if (now !== !!row.connected) apply(row.id, now)
  }
}
