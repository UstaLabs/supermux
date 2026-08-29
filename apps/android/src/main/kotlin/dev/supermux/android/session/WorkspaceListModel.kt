package dev.supermux.android.session

import dev.supermux.android.host.WorkspaceHostState
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.session.formatWorkdir
import dev.supermux.session.sessionListShowsUnread
import dev.supermux.workspace.WorkspaceActivity
import dev.supermux.workspace.chatSessionIds
import dev.supermux.workspace.isMultiAgent
import dev.supermux.workspace.workspaceActivity

/** Compose test-id vocabulary shared with desktop [WorkspaceListPanel]. */
object WorkspaceListTestIds {
    const val LIST = "workspaces_list"
    const val ARCHIVED_FOLD = "archived_fold"
    const val ROW_NEW_CHAT = "workspace_row_new_chat"
    fun row(id: String) = "workspace_row_$id"
    fun children(id: String) = "workspace-children-$id"
    fun multiAgent(id: String) = "workspace-multiagent-$id"
    fun archived(id: String) = "archived_workspace_$id"
}

/**
 * Session id to open when the workspace row is tapped.
 *
 * activeViewId → that view's chatSessionId; if the active view is not a chat,
 * the first chat view; if there is none, the primary session.
 */
fun resolveWorkspaceOpenSessionId(w: WorkspaceDto): String? {
    val active = w.activeViewId?.let { id -> w.views.firstOrNull { it.id == id } }
    active?.chatSessionId()?.let { return it }
    w.views.firstOrNull { it.kind == "chat" }?.chatSessionId()?.let { return it }
    return w.primarySessionId
}

/** Sidebar archived-workspace fold is independent of whether live workspaces exist. */
fun sessionListShowsArchivedWorkspaceFold(archivedWorkspaces: List<WorkspaceDto>): Boolean =
    archivedWorkspaces.isNotEmpty()

enum class SidebarReorderKind { SESSIONS, WORKSPACES }

/** Same rule as the sidebar: empty live workspaces → session reorder, else workspace reorder. */
fun sidebarReorderKind(liveWorkspaces: List<WorkspaceDto>): SidebarReorderKind =
    if (liveWorkspaces.isEmpty()) SidebarReorderKind.SESSIONS else SidebarReorderKind.WORKSPACES

/**
 * Optimistic workspace reorder across every host bucket that contains any of [orderedIds],
 * matching [AppViewModel.reorderSessions] host-scan behavior.
 */
fun applyWorkspaceReorder(
    buckets: Map<String, WorkspaceHostState>,
    orderedIds: List<String>,
): Map<String, WorkspaceHostState> {
    val order = orderedIds.withIndex().associate { (i, id) -> id to i }
    if (order.isEmpty()) return buckets
    var changed = false
    val next = buckets.mapValues { (_, st) ->
        var hostChanged = false
        val workspaces = st.workspaces.map { w ->
            val so = order[w.id] ?: return@map w
            if (w.sortOrder == so) w else {
                hostChanged = true
                w.copy(sortOrder = so)
            }
        }
        if (hostChanged) {
            changed = true
            st.copy(workspaces = workspaces)
        } else {
            st
        }
    }
    return if (changed) next else buckets
}

data class WorkspaceChildRowModel(
    val sessionId: String,
    val name: String,
)

data class WorkspaceRowModel(
    val workspace: WorkspaceDto,
    val name: String,
    val pathLabel: String,
    val git: GitLiteStatusDto?,
    val activity: WorkspaceActivity,
    val multiAgent: Boolean,
    val unread: Boolean,
    val children: List<WorkspaceChildRowModel>,
    val primarySessionId: String?,
    val openSessionId: String?,
)

data class ArchivedWorkspaceRowModel(
    val workspace: WorkspaceDto,
    val name: String,
    val pathLabel: String,
    val archivedAt: String?,
)

data class WorkspaceLists(
    val live: List<WorkspaceDto>,
    val archived: List<WorkspaceDto>,
)

fun deriveWorkspaceRow(
    w: WorkspaceDto,
    sessionsById: Map<String, SessionInfo>,
    agentState: Map<String, AgentStatus>,
    lastBySession: Map<String, LogEntry?>,
    lastRead: Map<String, String>,
    home: String,
    selectedSessionId: String?,
): WorkspaceRowModel {
    val primarySid = w.primarySessionId ?: w.chatSessionIds().firstOrNull()
    val primary = primarySid?.let { sessionsById[it] }
    val chatIds = w.chatSessionIds()
    val multi = w.isMultiAgent()
    val unread = chatIds.any { sid ->
        sessionListShowsUnread(
            active = sid == selectedSessionId,
            working = agentState[sid]?.working == true,
            lastMessageTs = lastBySession[sid]?.ts,
            lastReadAt = lastRead[sid],
        )
    }
    val children = if (multi) {
        chatIds.map { sid ->
            WorkspaceChildRowModel(sessionId = sid, name = sessionsById[sid]?.name ?: sid)
        }
    } else {
        emptyList()
    }
    return WorkspaceRowModel(
        workspace = w,
        name = w.name,
        pathLabel = formatWorkdir(w.repoRoot ?: w.workdir, home),
        git = primary?.git,
        activity = workspaceActivity(w, agentState),
        multiAgent = multi,
        unread = unread,
        children = children,
        primarySessionId = primarySid,
        openSessionId = resolveWorkspaceOpenSessionId(w),
    )
}

fun deriveArchivedWorkspaceRow(w: WorkspaceDto, home: String) = ArchivedWorkspaceRowModel(
    workspace = w,
    name = w.name,
    pathLabel = formatWorkdir(w.repoRoot ?: w.workdir, home),
    archivedAt = w.archivedAt,
)

fun applyArchiveWorkspace(
    live: List<WorkspaceDto>,
    archived: List<WorkspaceDto>,
    id: String,
): WorkspaceLists {
    val moving = live.find { it.id == id } ?: return WorkspaceLists(live, archived)
    val nextLive = live.filter { it.id != id }
    val archivedCopy = moving.copy(status = "archived")
    val nextArchived = if (archived.any { it.id == id }) {
        archived.map { if (it.id == id) archivedCopy else it }
    } else {
        archived + archivedCopy
    }
    return WorkspaceLists(nextLive, nextArchived)
}

fun applyRestoreWorkspace(
    live: List<WorkspaceDto>,
    archived: List<WorkspaceDto>,
    id: String,
): WorkspaceLists {
    val moving = archived.find { it.id == id } ?: return WorkspaceLists(live, archived)
    val nextArchived = archived.filter { it.id != id }
    val liveCopy = moving.copy(status = "active", archivedAt = null)
    val nextLive = if (live.any { it.id == id }) {
        live.map { if (it.id == id) liveCopy else it }
    } else {
        live + liveCopy
    }
    return WorkspaceLists(nextLive, nextArchived)
}
