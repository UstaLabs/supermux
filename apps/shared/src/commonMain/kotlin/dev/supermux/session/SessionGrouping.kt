package dev.supermux.session

import dev.supermux.net.ArchivedDto
import dev.supermux.proto.SessionInfo

data class SessionGroup(
    val label: String,
    val workdir: String,
    val sessions: List<SessionInfo>,
    /** Task-list sections (in_progress / draft / settled). Empty for PA group. */
    val sections: List<TaskSection> = emptyList(),
)

/** One task-state bucket inside a path group or the flat task list. */
data class TaskSection(
    val key: SectionKey,
    val label: String,
    val sessions: List<SessionInfo>,
)

enum class SectionKey {
    IN_PROGRESS,
    DRAFT,
    SETTLED,
    ;

    val wire: String
        get() = when (this) {
            IN_PROGRESS -> "in_progress"
            DRAFT -> "draft"
            SETTLED -> "settled"
        }

    val title: String
        get() = when (this) {
            IN_PROGRESS -> "In Progress"
            DRAFT -> "Drafts"
            SETTLED -> "Settled"
        }

    companion object {
        fun fromUserStatus(userStatus: String?): SectionKey = when (userStatus) {
            "draft" -> DRAFT
            "settled" -> SETTLED
            else -> IN_PROGRESS
        }
    }
}

/** Sentinel key for the pinned Personal Assistants group (never a real path). */
const val PA_GROUP_KEY = "__pas__"

private val SECTION_ORDER = listOf(SectionKey.IN_PROGRESS, SectionKey.DRAFT, SectionKey.SETTLED)

/**
 * Effective task status for list placement.
 * Archived lifecycle always counts as settled even if userStatus is missing.
 */
fun SessionInfo.effectiveUserStatus(): String {
    if (status == "archived") return "settled"
    return when (val u = userStatus) {
        "draft", "settled", "in_progress" -> u
        else -> "in_progress"
    }
}

fun SessionInfo.sectionKey(): SectionKey = SectionKey.fromUserStatus(effectiveUserStatus())

/** Map an archived REST row into a SessionInfo that lands in Settled. */
fun ArchivedDto.asSettledSession(): SessionInfo = SessionInfo(
    id = id,
    name = name,
    workdir = workdir,
    agent = agent.ifEmpty { "claude" },
    status = "archived",
    mute = false,
    connected = false,
    repo_root = repo_root,
    userStatus = "settled",
    sortOrder = 0,
)

/**
 * Live + archived-as-settled, excluding personal assistants — source for the
 * flat task list and per-project groups (web usePathGroups.combinedSessions).
 */
fun combinedTaskSessions(
    live: List<SessionInfo>,
    archived: List<ArchivedDto> = emptyList(),
): List<SessionInfo> {
    val liveNonPa = live.filter { it.role != "personal_assistant" }
    val liveIds = liveNonPa.map { it.id }.toHashSet()
    val archivedAs = archived
        .filter { it.id !in liveIds }
        .map { it.asSettledSession() }
    return liveNonPa + archivedAs
}

/**
 * Build In Progress / Drafts / Settled sections for [list].
 * in_progress + draft: sort_order ascending, then recency; settled: recency only.
 */
fun buildTaskSections(
    list: List<SessionInfo>,
    lastTs: (SessionInfo) -> String = { "" },
): List<TaskSection> {
    val buckets = linkedMapOf(
        SectionKey.IN_PROGRESS to mutableListOf<SessionInfo>(),
        SectionKey.DRAFT to mutableListOf(),
        SectionKey.SETTLED to mutableListOf(),
    )
    for (s in list) buckets.getValue(s.sectionKey()).add(s)

    val byRecency = compareByDescending<SessionInfo> { lastTs(it) }
    val bySort = Comparator<SessionInfo> { a, b ->
        val c = a.sortOrder.compareTo(b.sortOrder)
        if (c != 0) c else lastTs(b).compareTo(lastTs(a))
    }

    buckets[SectionKey.IN_PROGRESS]!!.sortWith(bySort)
    buckets[SectionKey.DRAFT]!!.sortWith(bySort)
    buckets[SectionKey.SETTLED]!!.sortWith(byRecency)

    return SECTION_ORDER
        .filter { buckets.getValue(it).isNotEmpty() }
        .map { TaskSection(key = it, label = it.title, sessions = buckets.getValue(it)) }
}

/** Leaf project tag for a row in flat mode (web projectLabel). */
fun projectLabel(session: SessionInfo, home: String?): String {
    val path = session.repo_root ?: session.workdir
    val label = formatWorkdir(path, home)
    val leaf = label.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: label
    return if (leaf == "~" || leaf == "…") "home" else leaf.removePrefix("…")
}

/**
 * Group sessions by workdir, mirroring the web's usePathGroups composable:
 *  - sessions within a group are sorted by last-message timestamp, newest first
 *  - groups are ordered by their most-recent session timestamp, newest first
 *  - each project group also carries task [sections]
 *  - the label uses [formatWorkdir]
 *
 * [lastTs] returns an ISO-8601 timestamp string for a session's most recent
 * message (or "" when it has none); ISO-8601 strings sort lexicographically by time.
 *
 * Pass [archived] (or pre-merge via [combinedTaskSessions]) so settled rows
 * appear under Settled even after kill archives them.
 */
fun groupSessions(
    sessions: List<SessionInfo>,
    home: String,
    lastTs: (SessionInfo) -> String = { "" },
    archived: List<ArchivedDto> = emptyList(),
): List<SessionGroup> {
    // Personal assistants are not project work: a dedicated pinned group.
    // Only from live list — archived PAs stay out of the task list chrome.
    val pas = sessions.filter { it.role == "personal_assistant" }
    val rest = combinedTaskSessions(sessions, archived)

    val byPath = LinkedHashMap<String, MutableList<SessionInfo>>()
    for (s in rest) byPath.getOrPut(s.repo_root ?: s.workdir) { mutableListOf() }.add(s)
    val projectGroups = byPath.map { (key, list) ->
        val sorted = list.sortedWith(compareByDescending { lastTs(it) })
        SessionGroup(
            label = formatWorkdir(key, home),
            workdir = key,
            sessions = sorted,
            sections = buildTaskSections(list, lastTs),
        )
    }.sortedWith(compareByDescending { g -> g.sessions.maxOfOrNull { lastTs(it) } ?: "" })

    val result = ArrayList<SessionGroup>(projectGroups.size + 1)
    if (pas.isNotEmpty()) {
        result.add(
            SessionGroup(
                label = "Personal Assistants",
                workdir = PA_GROUP_KEY,
                sessions = pas.sortedWith(compareByDescending { lastTs(it) }),
                sections = emptyList(),
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
