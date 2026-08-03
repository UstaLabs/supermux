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
 * User-controlled session order (sortOrder ascending, then id).
 * Use for rails, flat PA pins, and any list that must not jump on new messages.
 * Message recency belongs only in [sessionsByRecency] (launcher recent-projects).
 */
fun sessionsByUserOrder(sessions: List<SessionInfo>): List<SessionInfo> =
    sessions.sortedWith(compareBy<SessionInfo> { it.sortOrder }.thenBy { it.id })

/**
 * Build In Progress / Drafts / Settled sections for [list].
 * in_progress + draft: user sort_order only (no message-recency reshuffle);
 * settled: recency only (not user-reorderable).
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

    // Message arrival must not jump rows — only explicit user reorder updates sortOrder.
    val bySort = compareBy<SessionInfo> { it.sortOrder }.thenBy { it.id }

    buckets[SectionKey.IN_PROGRESS]!!.sortWith(bySort)
    buckets[SectionKey.DRAFT]!!.sortWith(bySort)
    // Settled is not drag-reorderable; newest-message-first for findability only.
    //
    // Do NOT write this as `sortWith(compareByDescending { lastTs(it) })`: a Comparator
    // re-evaluates its selector on EVERY comparison (twice per compare), so an N-row bucket
    // calls [lastTs] ~2·N·log2(N) times. [lastTs] is host-resolving and, on Apple, a
    // Kotlin/Native → Swift callback (Function1 trampoline + String bridging). With hundreds of
    // archived rows folded in by [combinedTaskSessions] that turned ONE sidebar render into
    // ~13k bridge crossings and made the sessions list the single most expensive view in the
    // macOS app. Decorate-sort-undecorate evaluates the key exactly once per session; the sort
    // is stable, so equal keys keep input order (the server's `killed_at DESC` for archived).
    val settled = buckets.getValue(SectionKey.SETTLED)
    val byRecency = settled.map { lastTs(it) to it }.sortedByDescending { it.first }
    settled.clear()
    for ((_, s) in byRecency) settled.add(s)

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
 *  - sessions within a group follow user sortOrder (stable; no message reshuffle)
 *  - project groups are ordered by label (stable)
 *  - each project group also carries task [sections]
 *  - the label uses [formatWorkdir]
 *
 * [lastTs] is used only for Settled section ordering (newest first).
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
    val projectGroups = byPath.mapNotNull { (key, list) ->
        val sorted = sessionsByUserOrder(list)
        val sections = buildTaskSections(list, lastTs)
        // Hide projects with no live work (in_progress / draft). Settled-only
        // projects still appear in flat Settled; no need for an empty group card.
        val hasActive = sections.any { it.key != SectionKey.SETTLED && it.sessions.isNotEmpty() }
        if (!hasActive) return@mapNotNull null
        SessionGroup(
            label = formatWorkdir(key, home),
            workdir = key,
            sessions = sorted,
            sections = sections,
        )
    }.sortedBy { it.label }

    val result = ArrayList<SessionGroup>(projectGroups.size + 1)
    if (pas.isNotEmpty()) {
        result.add(
            SessionGroup(
                label = "Personal Assistants",
                workdir = PA_GROUP_KEY,
                sessions = sessionsByUserOrder(pas),
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

/**
 * Compiled once. [inferHomeDir] runs per session row per sidebar render (via [projectLabel] and
 * [formatWorkdir]); compiling the pattern inside the function made every one of those pay for a
 * fresh regex.
 */
private val HOME_DIR_RE = Regex("^(/(?:home|Users)/[^/]+)")

/** Best-effort home dir (/home/<user> or /Users/<user>) when none was supplied. */
fun inferHomeDir(workdir: String?): String? {
    val probe = workdir ?: ""
    val m = HOME_DIR_RE.find(probe)
    return m?.groupValues?.getOrNull(1)
}
