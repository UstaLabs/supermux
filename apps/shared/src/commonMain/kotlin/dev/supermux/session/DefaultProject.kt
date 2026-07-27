package dev.supermux.session

import dev.supermux.proto.SessionInfo

/**
 * Web parity for the New Session launcher's default project selection
 * (`src/web-app/src/lib/default-project.ts` + `recent-projects.ts`).
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
 * The working directory the launcher should show.
 *
 * @param current currently selected path
 * @param recent project paths, most-recently-active first
 * @param picked user explicitly chose a path via the project picker
 * @param composing user has started typing / attaching / recording
 */
fun chooseDefaultProject(
    current: String,
    recent: List<String>,
    picked: Boolean,
    composing: Boolean,
): String {
    if (picked || composing) return current
    return recent.firstOrNull() ?: current
}
