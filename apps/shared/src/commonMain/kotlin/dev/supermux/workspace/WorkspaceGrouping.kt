package dev.supermux.workspace

import dev.supermux.proto.AgentStatus
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.session.PA_GROUP_KEY
import dev.supermux.session.formatWorkdir

/**
 * Group workspaces by project for the sidebar (spec §13.6).
 *
 * A workspace whose [WorkspaceDto.projectId] names a known persistent project ([ProjectRef]) joins
 * that project's group; everything else falls back to the path grouping that mirrors
 * dev.supermux.session.SessionGrouping — same key (repo_root ?: workdir), same label formatting.
 * Membership itself is still computed by the broker from paths; the client only groups.
 */
data class WorkspaceGroup(
    /** "p:<hostId>:<projectId>" for a persistent project, the raw path for fallback groups, PA_GROUP_KEY for PAs. */
    val key: String,
    /** The project name, or the display label from [formatWorkdir] for a fallback group. */
    val label: String,
    val workspaces: List<WorkspaceDto>,
    /** Set for persistent projects only. */
    val project: ProjectDto? = null,
    val hostId: String? = null,
)

/** A persistent project plus the host record id whose broker owns it (ids are unique per broker only). */
data class ProjectRef(val hostId: String, val project: ProjectDto)

/** The [WorkspaceGroup.key] of a persistent project's group. */
fun projectGroupKey(hostId: String, projectId: String): String = "p:$hostId:$projectId"

/** What the sidebar row's status dot shows. */
enum class WorkspaceActivity { NONE, IDLE, WORKING }

/** The sessions of every chat view, in view order. */
fun WorkspaceDto.chatSessionIds(): List<String> = views.mapNotNull { it.chatSessionId() }

/** Two or more live agents share this workspace's work tree (spec §10 risk control 2). */
fun WorkspaceDto.isMultiAgent(): Boolean = views.count { it.kind == "chat" } >= 2

/** The busiest state across the workspace's chat sessions. */
fun workspaceActivity(w: WorkspaceDto, agentState: Map<String, AgentStatus>): WorkspaceActivity {
    val ids = w.chatSessionIds()
    if (ids.isEmpty()) return WorkspaceActivity.NONE
    return if (ids.any { agentState[it]?.working == true }) WorkspaceActivity.WORKING
           else WorkspaceActivity.IDLE
}

/**
 * Active workspaces, grouped by project.
 *
 * Order: the "Personal Assistants" group (if any), then every persistent project in [projects] —
 * empty ones included, since a persistent project shows without workspaces — ordered by
 * sortOrder, name, id; then the path-fallback groups ordered by label. Rows inside a group follow
 * sortOrder then id, so a new message never reshuffles the list. Only an explicit user drag
 * changes sortOrder — the same rule SessionGrouping documents.
 *
 * [isPersonalAssistant] pins matching workspaces under a "Personal Assistants" group (same
 * [PA_GROUP_KEY] as SessionGrouping). [WorkspaceDto] has no role, so the caller decides from the
 * primary session's [dev.supermux.proto.SessionInfo.role].
 *
 * A workspace joins a project group when `(hostOf(w), w.projectId)` matches a [ProjectRef];
 * an unresolved or unknown projectId falls back to path grouping. With [projects] empty (an old
 * broker) the output is exactly the path grouping.
 */
fun groupWorkspaces(
    workspaces: List<WorkspaceDto>,
    home: String,
    projects: List<ProjectRef> = emptyList(),
    hostOf: (WorkspaceDto) -> String = { "" },
    // Kept LAST so existing `groupWorkspaces(ws, home) { isPa }` trailing-lambda callers still bind here.
    isPersonalAssistant: (WorkspaceDto) -> Boolean = { false },
): List<WorkspaceGroup> {
    val live = workspaces.filter { it.status != "archived" }

    val pas = live.filter(isPersonalAssistant)
    val rest = live.filterNot(isPersonalAssistant)

    val rowOrder = compareBy<WorkspaceDto>({ it.sortOrder }, { it.id })
    val result = ArrayList<WorkspaceGroup>()
    if (pas.isNotEmpty()) {
        result.add(
            WorkspaceGroup(
                key = PA_GROUP_KEY,
                label = "Personal Assistants",
                workspaces = pas.sortedWith(rowOrder),
            ),
        )
    }
    result.addAll(resolveGroups(rest, home, projects, hostOf, rowOrder, keepEmptyProjects = true))
    return result
}

/**
 * Archived workspaces grouped by project, newest-archived first inside a group.
 * Live rows are ignored — pair with [groupWorkspaces] for the sidebar fold. Same project
 * resolution and group order as the live list, but only non-empty groups are returned.
 */
fun groupArchivedWorkspaces(
    workspaces: List<WorkspaceDto>,
    home: String,
    projects: List<ProjectRef> = emptyList(),
    hostOf: (WorkspaceDto) -> String = { "" },
): List<WorkspaceGroup> {
    val dead = workspaces.filter { it.status == "archived" }
    val rowOrder = compareByDescending<WorkspaceDto> { it.archivedAt ?: "" }.thenBy { it.id }
    return resolveGroups(dead, home, projects, hostOf, rowOrder, keepEmptyProjects = false)
}

/**
 * Project groups followed by path-fallback groups (by label).
 *
 * Projects are ordered by host first — so one host's projects never interleave with another's in
 * fleet mode — then by sortOrder, name, id within that host. Host rank is the host's first
 * appearance in [projects] (the caller's fleet order), not an alphabetic or arbitrary sort.
 */
private fun resolveGroups(
    rows: List<WorkspaceDto>,
    home: String,
    projects: List<ProjectRef>,
    hostOf: (WorkspaceDto) -> String,
    rowOrder: Comparator<WorkspaceDto>,
    keepEmptyProjects: Boolean,
): List<WorkspaceGroup> {
    val hostRank = projects.map { it.hostId }.distinct().withIndex().associate { (i, h) -> h to i }
    val refs = projects
        .distinctBy { projectGroupKey(it.hostId, it.project.id) }
        .sortedWith(compareBy({ hostRank[it.hostId] }, { it.project.sortOrder }, { it.project.name }, { it.project.id }))
    val byProject = LinkedHashMap<String, MutableList<WorkspaceDto>>()
    for (r in refs) byProject[projectGroupKey(r.hostId, r.project.id)] = mutableListOf()

    val byPath = LinkedHashMap<String, MutableList<WorkspaceDto>>()
    for (w in rows) {
        val bucket = w.projectId?.let { byProject[projectGroupKey(hostOf(w), it)] }
        if (bucket != null) bucket.add(w)
        else byPath.getOrPut(w.repoRoot ?: w.workdir) { mutableListOf() }.add(w)
    }

    val projectGroups = refs.mapNotNull { r ->
        val key = projectGroupKey(r.hostId, r.project.id)
        val list = byProject.getValue(key)
        if (list.isEmpty() && !keepEmptyProjects) null
        else WorkspaceGroup(
            key = key,
            label = r.project.name,
            workspaces = list.sortedWith(rowOrder),
            project = r.project,
            hostId = r.hostId,
        )
    }
    val pathGroups = byPath.map { (key, list) ->
        WorkspaceGroup(
            key = key,
            label = formatWorkdir(key, home),
            workspaces = list.sortedWith(rowOrder),
        )
    }.sortedBy { it.label }
    return projectGroups + pathGroups
}
