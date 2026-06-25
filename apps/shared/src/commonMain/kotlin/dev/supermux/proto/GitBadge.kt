package dev.supermux.proto

/** Visual category of a session's git badge — selects the per-platform icon + tone. */
enum class GitBadgeKind { BASE, REMOTE, UNPUBLISHED, INSYNC }

enum class GitBadgeTone { ACTIVE, MUTED }

/**
 * Rendered git badge for a session. [text] is the glyph string; [kind] selects the
 * platform icon (branch glyph for BASE, none for REMOTE); [tone] selects styling;
 * [compareRef] is the ref the counts are relative to (used for the header label).
 */
data class GitBadge(
    val text: String,
    val kind: GitBadgeKind,
    val tone: GitBadgeTone,
    val compareRef: String,
)

/**
 * Formats a [GitLiteStatusDto] for display. Pure; shared by the iOS (SKIE) and Android
 * UIs so the local-vs-remote visual rules live in one place.
 *
 * - base mode (worktree vs base branch):  `+{ahead} −{behind}`  -> BASE (branch icon)
 * - remote mode (branch vs upstream):     `↑{ahead} ↓{behind}`  -> REMOTE (no icon)
 * - dirty (both modes):                   append `·{dirty}`
 * - all zero:                             `✓`            -> INSYNC (muted)
 * - remote with no upstream:              `unpublished`  -> UNPUBLISHED (muted)
 * - null status (non-repo session):       null (no badge)
 *
 * Note: `−` is U+2212 MINUS SIGN (not a hyphen); `·` is U+00B7 MIDDLE DOT.
 */
fun gitBadge(git: GitLiteStatusDto?): GitBadge? {
    if (git == null) return null
    val ref = git.compareRef
    if (git.mode == "remote" && git.unpublished == true)
        return GitBadge("unpublished", GitBadgeKind.UNPUBLISHED, GitBadgeTone.MUTED, ref)
    if (git.ahead == 0 && git.behind == 0 && git.dirty == 0)
        return GitBadge("✓", GitBadgeKind.INSYNC, GitBadgeTone.MUTED, ref)
    val parts = mutableListOf<String>()
    if (git.mode == "base") {
        if (git.ahead != 0) parts += "+${git.ahead}"
        if (git.behind != 0) parts += "−${git.behind}"
    } else {
        if (git.ahead != 0) parts += "↑${git.ahead}"
        if (git.behind != 0) parts += "↓${git.behind}"
    }
    if (git.dirty != 0) parts += "·${git.dirty}"
    val kind = if (git.mode == "base") GitBadgeKind.BASE else GitBadgeKind.REMOTE
    return GitBadge(parts.joinToString(" "), kind, GitBadgeTone.ACTIVE, ref)
}

/** Glanceable finished-vs-not state for the session list. */
enum class SessionDoneState { DONE, NOT_DONE }

/**
 * Two-state "is this session finished?" for the list. Worktree (base-mode) sessions only:
 * DONE when its commits are in the base branch (ahead == 0) and the tree is clean (dirty == 0);
 * NOT_DONE when there are unmerged commits (ahead > 0) or uncommitted changes (dirty > 0).
 * `behind` alone does NOT make it not-done. Returns null when no indicator applies
 * (non-repo session, or remote/plain-repo mode).
 */
fun sessionDoneState(git: GitLiteStatusDto?): SessionDoneState? {
    if (git == null || git.mode != "base") return null
    return if (git.ahead == 0 && git.dirty == 0) SessionDoneState.DONE else SessionDoneState.NOT_DONE
}
