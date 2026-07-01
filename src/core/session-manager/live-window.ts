/**
 * The tmux window id to use for a session that may not be registered yet.
 * During spawn, the id is captured into `pendingTmuxWindowId` BEFORE the
 * registry row exists (onRegister drains it later) — so liveness checks during
 * the spawn wait must consult the pending map too, not just the registry.
 */
export function liveWindowId(
  sessionId: string,
  getRegistered: (id: string) => string | undefined,
  getPending: (id: string) => string | undefined,
): string | undefined {
  return getRegistered(sessionId) ?? getPending(sessionId)
}
