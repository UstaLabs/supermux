package dev.supermux.ui.session

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
import dev.supermux.state.SidebarReorderKind
import dev.supermux.state.sidebarReorderKind

/** Compose test-id vocabulary shared by both hosts’ [SessionListScreen] rows. */
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
