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
/** Sentinel key for the pinned Personal Assistants group (never a real path). */
const val PA_GROUP_KEY = "__pas__"

fun groupSessions(
    sessions: List<SessionInfo>,
    home: String,
    lastTs: (SessionInfo) -> String = { "" },
): List<SessionGroup> {
    // Personal assistants are not project work: a dedicated pinned group.
    val pas = sessions.filter { it.role == "personal_assistant" }
    val rest = sessions.filter { it.role != "personal_assistant" }

    // Worktree-backed sessions group under their project (repo_root), not the
    // internal worktree path.
    val byPath = LinkedHashMap<String, MutableList<SessionInfo>>()
    for (s in rest) byPath.getOrPut(s.repo_root ?: s.workdir) { mutableListOf() }.add(s)
    val projectGroups = byPath.map { (key, list) ->
        val sorted = list.sortedWith(compareByDescending { lastTs(it) })
        SessionGroup(label = formatWorkdir(key, home), workdir = key, sessions = sorted)
    }.sortedWith(compareByDescending { g -> g.sessions.maxOfOrNull { lastTs(it) } ?: "" })

    val result = ArrayList<SessionGroup>(projectGroups.size + 1)
    if (pas.isNotEmpty()) {
        result.add(
            SessionGroup(
                label = "Personal Assistants",
                workdir = PA_GROUP_KEY,
                sessions = pas.sortedWith(compareByDescending { lastTs(it) }),
            ),
        )
    }
    result.addAll(projectGroups)
    return result
}

/**
 * Format a workdir for display as its last two path segments (parent/folder):
 *  - `~` for home itself, `~/leaf` one level under home
 *  - `…/parent/leaf` when deeper (whether under home or not)
 *  - `parent/leaf` for a shallow two-segment absolute path; a single segment is unchanged
 */
fun formatWorkdir(workdir: String, home: String?): String {
    val h = if (!home.isNullOrEmpty()) home else inferHomeDir(workdir)
    if (!h.isNullOrEmpty() && workdir == h) return "~"
    val segments = workdir.split("/").filter { it.isNotEmpty() }
    if (segments.size <= 1) return workdir
    val leaf = segments[segments.size - 1]
    val parent = segments[segments.size - 2]
    val parentPath = "/" + segments.dropLast(1).joinToString("/")
    if (!h.isNullOrEmpty() && parentPath == h) return "~/$leaf"
    val base = "$parent/$leaf"
    return if (segments.size > 2) "…/$base" else base
}

/** Best-effort home dir (/home/<user> or /Users/<user>) when none was supplied. */
fun inferHomeDir(workdir: String?): String? {
    val probe = workdir ?: ""
    val m = Regex("^(/(?:home|Users)/[^/]+)").find(probe)
    return m?.groupValues?.getOrNull(1)
}
