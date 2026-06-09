package dev.supermux.session

import dev.supermux.proto.SessionInfo

data class SessionGroup(val label: String, val workdir: String, val sessions: List<SessionInfo>)

/**
 * Group sessions by workdir, mirroring the web's usePathGroups composable:
 *  - sessions within a group are sorted by last-message timestamp, newest first
 *  - groups are ordered by their most-recent session timestamp, newest first
 *  - the label uses [formatWorkdir]
 *
 * [lastTs] returns an ISO-8601 timestamp string for a session's most recent
 * message (or "" when it has none); ISO-8601 strings sort lexicographically by time.
 */
fun groupSessions(
    sessions: List<SessionInfo>,
    home: String,
    lastTs: (SessionInfo) -> String = { "" },
): List<SessionGroup> {
    val byPath = LinkedHashMap<String, MutableList<SessionInfo>>()
    for (s in sessions) byPath.getOrPut(s.workdir) { mutableListOf() }.add(s)
    val groups = byPath.map { (workdir, list) ->
        val sorted = list.sortedWith(compareByDescending { lastTs(it) })
        SessionGroup(label = formatWorkdir(workdir, home), workdir = workdir, sessions = sorted)
    }
    return groups.sortedWith(compareByDescending { g -> g.sessions.maxOfOrNull { lastTs(it) } ?: "" })
}

/** Format a workdir for display: ~/… when under home, otherwise a shortened absolute path. */
fun formatWorkdir(workdir: String, home: String?): String {
    val h = if (!home.isNullOrEmpty()) home else inferHomeDir(workdir)
    if (!h.isNullOrEmpty() && (workdir == h || workdir.startsWith("$h/"))) {
        val rest = workdir.substring(h.length)
        return if (rest.isNotEmpty()) "~$rest" else "~"
    }
    return shortenAbsolute(workdir)
}

/** Best-effort home dir (/home/<user> or /Users/<user>) when none was supplied. */
fun inferHomeDir(workdir: String?): String? {
    val probe = workdir ?: ""
    val m = Regex("^(/(?:home|Users)/[^/]+)").find(probe)
    return m?.groupValues?.getOrNull(1)
}

private fun shortenAbsolute(path: String): String {
    val parts = path.split("/").filter { it.isNotEmpty() }
    if (parts.size <= 2) return path
    return ".../" + parts.takeLast(2).joinToString("/")
}
