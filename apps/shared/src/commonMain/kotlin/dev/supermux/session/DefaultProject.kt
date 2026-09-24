package dev.supermux.session

import dev.supermux.proto.SessionInfo

/**
 * Web parity for the New Session launcher's default project selection, ported from the
 * retired Vue PWA's default-project + recent-projects helpers
 * (retired Vue PWA; see git history before 2026-09-12).
 *
 * Before the user engages we follow the most-recently-used project (by session
 * activity). Once they pick a path or start composing, the selection freezes so
 * a late recency reshuffle can't swap the project out from under them.
 */

/** Project path for recency: prefer the real repo root over a worktree workdir. */
fun sessionProjectPath(session: SessionInfo): String? {
    val path = (session.repo_root ?: session.workdir).trim()
    return path.takeIf { it.isNotEmpty() }
}

/**
 * Distinct project paths from sessions already ordered newest-first, preserving
 * that order. The first entry is the most-recently-active project.
 */
fun recentWorkdirs(sessionsNewestFirst: List<SessionInfo>): List<String> {
    val seen = LinkedHashSet<String>()
    val out = ArrayList<String>()
    for (s in sessionsNewestFirst) {
        val w = sessionProjectPath(s) ?: continue
        if (!seen.add(w)) continue
        out.add(w)
    }
    return out
}

/**
 * Sort [sessions] by last-message timestamp (ISO-8601, lexicographic), newest first.
 * Sessions with no timestamp sort last.
 */
fun sessionsByRecency(
    sessions: List<SessionInfo>,
    lastTs: (SessionInfo) -> String,
): List<SessionInfo> =
    sessions.sortedWith(compareByDescending { lastTs(it).ifEmpty { "" } })

/**
 * Project options for the picker: recently-used projects first, then any other
 * known projects not used recently (stable relative order among the latter).
 */
fun orderProjectsByRecency(recent: List<String>, known: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    val out = ArrayList<String>(known.size + recent.size)
    for (path in recent) {
        if (path.isEmpty() || !seen.add(path)) continue
        out.add(path)
    }
    for (path in known) {
        if (path.isEmpty() || !seen.add(path)) continue
        out.add(path)
    }
    return out
}

/**
 * The working directory the launcher should show, or null when there is nothing to pick from yet
 * ("Choose a project" — never a silent `~`).
 *
 * @param current currently selected path (null = none yet)
 * @param recent project paths, most-recently-active first
 * @param picked user explicitly chose a path via the project picker (or a draft restored one)
 * @param composing user has started typing / attaching / recording
 * @param fallback used only when nothing else names a project (the catalog's first project)
 */
fun chooseDefaultProject(
    current: String?,
    recent: List<String>,
    picked: Boolean,
    composing: Boolean,
    fallback: String? = null,
): String? {
    if (picked) return current
    // Typing freezes a project that is SHOWING; with none yet there is nothing to freeze.
    if (composing && current != null) return current
    return recent.firstOrNull() ?: current ?: fallback
}

/** What the project picker shows under a project: how many sessions live there, and when one last spoke. */
data class ProjectActivity(val sessions: Int, val lastActiveMs: Long?)

/**
 * Per-project activity keyed by the project path ([sessionProjectPath]) — the same key the picker's
 * project list uses. [lastTsMs] is a session's last message time in epoch millis, null if unknown.
 */
fun projectActivity(sessions: List<SessionInfo>, lastTsMs: (SessionInfo) -> Long?): Map<String, ProjectActivity> =
    sessions
        .mapNotNull { s -> sessionProjectPath(s)?.let { it to s } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, list) -> ProjectActivity(list.size, list.mapNotNull(lastTsMs).maxOrNull()) }

/** Compact age for a tile or row: "now", "5m", "3h", "2d", "4w". */
fun formatAgoShort(nowMs: Long, thenMs: Long): String {
    val sec = ((nowMs - thenMs) / 1000L).coerceAtLeast(0L)
    return when {
        sec < 60L -> "now"
        sec < 3_600L -> "${sec / 60}m"
        sec < 86_400L -> "${sec / 3_600}h"
        sec < 86_400L * 14 -> "${sec / 86_400}d"
        else -> "${sec / (86_400L * 7)}w"
    }
}
