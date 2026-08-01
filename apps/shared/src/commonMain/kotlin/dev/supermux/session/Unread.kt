package dev.supermux.session

/**
 * Server-authoritative unread: a session is unread when it has a last-message
 * timestamp newer than the server's `last_read_at` pointer (or no pointer yet).
 *
 * Mirrors the web store (`src/web-app/src/stores/unread.ts`) and watch helper
 * (`src/channels/web/watch-session-row.ts`): ISO timestamps compare as strings
 * because the broker always emits UTC `…Z` form.
 *
 * Do **not** use message `direction` as a proxy for unread — `inbound` is the
 * user's own message, `outbound` is the agent reply; either can be "new" relative
 * to the read pointer.
 */
fun isSessionUnread(lastMessageTs: String?, lastReadAt: String?): Boolean {
    if (lastMessageTs.isNullOrEmpty()) return false
    if (lastReadAt.isNullOrEmpty()) return true
    return lastMessageTs > lastReadAt
}

/**
 * Monotonic advance of a read pointer (optimistic local mark or server confirm).
 * Never moves a pointer backwards so a slightly-older server timestamp cannot undo
 * an optimistic `now()` mark.
 */
fun advanceLastRead(current: String?, candidate: String): String {
    if (current != null && current >= candidate) return current
    return candidate
}
