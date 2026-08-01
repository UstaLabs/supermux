/**
 * Leading session-list indicator priority — mirrors KMP
 * `sessionListRailIndicator` / `sessionListShowsUnread`
 * (apps/shared/.../session/SessionListRail.kt).
 *
 *  1. working → spinner (no unread)
 *  2. idle + unread → larger green leading dot
 *  3. else → settled check / quiet gray
 */

export type SessionListRailIndicator = "working" | "unread" | "other"

export function sessionListRailIndicator(opts: {
  working: boolean
  unread: boolean
}): SessionListRailIndicator {
  if (opts.working) return "working"
  if (opts.unread) return "unread"
  return "other"
}

/** Whether the row should request the unread mark (before working overrides it). */
export function sessionListShowsUnread(opts: {
  active?: boolean
  working: boolean
  unread: boolean
}): boolean {
  if (opts.active || opts.working) return false
  return !!opts.unread
}

/** Final rail kind after active/working overrides (what SessionRow must paint). */
export function sessionListRailKind(opts: {
  active?: boolean
  working: boolean
  unread: boolean
}): SessionListRailIndicator {
  const showUnread = sessionListShowsUnread(opts)
  return sessionListRailIndicator({ working: opts.working, unread: showUnread })
}
