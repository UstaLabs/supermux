import type { Session } from "./types"

export type EnsureWindowIdDeps = {
  tmuxSession: string
  resolve: (session: string, name: string) => Promise<string | null>
  persist: (sessionId: string, windowId: string) => void
}

/**
 * The addressable tmux window id for a claude session. Heals a missing id once
 * via a name->id lookup (legacy/pre-migration-014 rows, or a spawn whose id
 * capture failed), persists it, then returns it. Never returns a name-string
 * target: callers address tmux strictly by id. Returns null when no live window
 * can be found, so callers no-op + log instead of routing by name.
 *
 * Concurrent callers on the same missing-id session may both resolve+persist;
 * that is idempotent (same window -> same id; persist just overwrites).
 */
export async function ensureWindowId(
  session: Pick<Session, "id" | "name" | "tmux_window_id">,
  deps: EnsureWindowIdDeps,
): Promise<string | null> {
  if (session.tmux_window_id) return session.tmux_window_id
  const resolved = await deps.resolve(deps.tmuxSession, session.name)
  if (resolved) {
    deps.persist(session.id, resolved)
    return resolved
  }
  return null
}
