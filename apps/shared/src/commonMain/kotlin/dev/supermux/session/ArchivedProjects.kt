package dev.supermux.session

import dev.supermux.net.ArchivedDto

/** A distinct project across archived sessions. [key] is repo_root ?: workdir. */
data class ArchivedProject(val key: String, val label: String, val count: Int)

/** A session's project key: its repo (for worktrees) else its workdir — matches groupSessions. */
private fun projectKey(s: ArchivedDto): String = s.repo_root ?: s.workdir

/**
 * Distinct projects across archived sessions, most-recently-archived first.
 * Label uses [formatWorkdir]; ties broken alphabetically by label.
 */
fun archivedProjects(sessions: List<ArchivedDto>, home: String?): List<ArchivedProject> {
    data class Acc(val key: String, val label: String, var count: Int, var latest: String)
    val byKey = LinkedHashMap<String, Acc>()
    for (s in sessions) {
        val key = projectKey(s)
        val killed = s.killed_at ?: ""
        val acc = byKey[key]
        if (acc != null) {
            acc.count += 1
            if (killed > acc.latest) acc.latest = killed
        } else {
            byKey[key] = Acc(key, formatWorkdir(key, home), 1, killed)
        }
    }
    return byKey.values
        .sortedWith(compareByDescending<Acc> { it.latest }.thenBy { it.label })
        .map { ArchivedProject(it.key, it.label, it.count) }
}

/** Sessions in the given project (by key). A null key returns all sessions. */
fun filterArchivedByProject(sessions: List<ArchivedDto>, key: String?): List<ArchivedDto> =
    if (key == null) sessions else sessions.filter { projectKey(it) == key }
