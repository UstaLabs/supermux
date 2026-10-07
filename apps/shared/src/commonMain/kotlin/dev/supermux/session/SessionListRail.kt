package dev.supermux.session

/**
 * Leading status mark on a session-list row (native rail / web left column / watch).
 *
 * Priority matches the product rule:
 *  1. [Working] — agent busy; spinner, no unread mark
 *  2. [Unread] — idle with last message newer than last_read_at
 *  3. [Other] — platform-specific (gray neutral, git icon, settled check, …)
 *
 * Callers should compute [unread] as `!active && isSessionUnread(lastTs, lastReadAt)`.
 */
enum class SessionListRailIndicator {
    Working,
    Unread,
    Other,
}

/**
 * Pure decision for the leading list indicator. Unit-tested so every client UI can assert the
 * same priority without re-encoding the matrix in each platform test harness.
 */
fun sessionListRailIndicator(working: Boolean, unread: Boolean): SessionListRailIndicator = when {
    working -> SessionListRailIndicator.Working
    unread -> SessionListRailIndicator.Unread
    else -> SessionListRailIndicator.Other
}

/**
 * Whether a list row should paint the unread mark (green rail). Active/selected and working
 * sessions never show it — the selected chat is being viewed, and a working agent keeps the
 * spinner. [lastMessageTs]/[lastReadAt] use the same ISO string compare as [isSessionUnread].
 */
fun sessionListShowsUnread(
    active: Boolean,
    working: Boolean,
    lastMessageTs: String?,
    lastReadAt: String?,
): Boolean {
    if (active || working) return false
    return isSessionUnread(lastMessageTs, lastReadAt)
}
