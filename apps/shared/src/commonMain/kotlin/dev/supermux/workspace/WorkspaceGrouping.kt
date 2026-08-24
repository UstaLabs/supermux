package dev.supermux.workspace

import dev.supermux.proto.AgentStatus
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.session.PA_GROUP_KEY
import dev.supermux.session.formatWorkdir

/**
 * Group workspaces by project for the sidebar (spec §13.6).
 *
 * Mirrors dev.supermux.session.SessionGrouping — same key (repo_root ?: workdir),
 * same label formatting, same ordering rules. Projects stay a CALCULATED value;
 * there is no project table (spec decision 7).
 */
data class WorkspaceGroup(
    /** The raw path the group keys on. */
    val key: String,
    /** The display label from [formatWorkdir]. */
    val label: String,
    val workspaces: List<WorkspaceDto>,
)

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
 * Groups are ordered by label; rows inside a group follow sortOrder then id, so a
 * new message never reshuffles the list. Only an explicit user drag changes
 * sortOrder — the same rule SessionGrouping documents.
 *
 * [isPersonalAssistant] pins matching workspaces under a "Personal Assistants"
 * group (same [PA_GROUP_KEY] as SessionGrouping). [WorkspaceDto] has no role, so
 * the caller decides from the primary session's [dev.supermux.proto.SessionInfo.role].
 * Default is nobody is a PA, so existing tests stay green.
 */
fun groupWorkspaces(
    workspaces: List<WorkspaceDto>,
    home: String,
    isPersonalAssistant: (WorkspaceDto) -> Boolean = { false },
): List<WorkspaceGroup> {
    val live = workspaces.filter { it.status != "archived" }

    val pas = live.filter(isPersonalAssistant)
    val rest = live.filterNot(isPersonalAssistant)

    val byPath = LinkedHashMap<String, MutableList<WorkspaceDto>>()
    for (w in rest) byPath.getOrPut(w.repoRoot ?: w.workdir) { mutableListOf() }.add(w)

    val projectGroups = byPath.map { (key, list) ->
        WorkspaceGroup(
            key = key,
            label = formatWorkdir(key, home),
            workspaces = list.sortedWith(compareBy({ it.sortOrder }, { it.id })),
        )
    }.sortedBy { it.label }

    val result = ArrayList<WorkspaceGroup>(projectGroups.size + 1)
    if (pas.isNotEmpty()) {
        result.add(
            WorkspaceGroup(
                key = PA_GROUP_KEY,
                label = "Personal Assistants",
                workspaces = pas.sortedWith(compareBy({ it.sortOrder }, { it.id })),
            ),
        )
    }
    result.addAll(projectGroups)
    return result
}

/**
 * Archived workspaces grouped by project, newest-archived first inside a group.
 * Live rows are ignored — pair with [groupWorkspaces] for the sidebar fold.
 */
fun groupArchivedWorkspaces(
    workspaces: List<WorkspaceDto>,
    home: String,
): List<WorkspaceGroup> {
    val dead = workspaces.filter { it.status == "archived" }
    val byPath = LinkedHashMap<String, MutableList<WorkspaceDto>>()
    for (w in dead) byPath.getOrPut(w.repoRoot ?: w.workdir) { mutableListOf() }.add(w)
    return byPath.map { (key, list) ->
        WorkspaceGroup(
            key = key,
            label = formatWorkdir(key, home),
            workspaces = list.sortedWith(
                compareByDescending<WorkspaceDto> { it.archivedAt ?: "" }.thenBy { it.id },
            ),
        )
    }.sortedBy { it.label }
}
