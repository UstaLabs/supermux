import type { Session } from "./types"
import { normalizeName } from "../../shared/slug"

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
 *
 * Tries the display name first, then its slug (normalizeName), because PA
 * windows are named with the slug so a non-slug display name needs the fallback.
 */
export async function ensureWindowId(
  session: Pick<Session, "id" | "name" | "tmux_window_id">,
  deps: EnsureWindowIdDeps,
): Promise<string | null> {
  if (session.tmux_window_id) return session.tmux_window_id
  // Heal by name: try the display name, then its slug — PA windows are named
  // by normalizeName(name), so a non-slug display name needs the slug variant.
  const slug = normalizeName(session.name)
  const candidates = slug && slug !== session.name ? [session.name, slug] : [session.name]
  for (const candidate of candidates) {
    const resolved = await deps.resolve(deps.tmuxSession, candidate)
    if (resolved) {
      deps.persist(session.id, resolved)
      return resolved
    }
  }
  return null
}
