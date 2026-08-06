package dev.supermux.workspace

import dev.supermux.proto.AgentStatus
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
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
 */
fun groupWorkspaces(workspaces: List<WorkspaceDto>, home: String): List<WorkspaceGroup> {
    val live = workspaces.filter { it.status != "archived" }

    val byPath = LinkedHashMap<String, MutableList<WorkspaceDto>>()
    for (w in live) byPath.getOrPut(w.repoRoot ?: w.workdir) { mutableListOf() }.add(w)

    return byPath.map { (key, list) ->
        WorkspaceGroup(
            key = key,
            label = formatWorkdir(key, home),
            workspaces = list.sortedWith(compareBy({ it.sortOrder }, { it.id })),
        )
    }.sortedBy { it.label }
}
