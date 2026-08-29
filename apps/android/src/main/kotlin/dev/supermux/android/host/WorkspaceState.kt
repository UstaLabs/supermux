package dev.supermux.android.host

import dev.supermux.proto.ServerFrame
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.workspace.chatSessionIds

/** Per-host broker workspace lists (live + archived). */
data class WorkspaceHostState(
    val workspaces: List<WorkspaceDto> = emptyList(),
    val archivedWorkspaces: List<WorkspaceDto> = emptyList(),
)

/**
 * Fold one workspace/view/[ServerFrame.Snapshot] frame into [state].
 * Returns null when [frame] is unrelated so the caller can leave other branches untouched.
 * Semantics match desktop [dev.supermux.desktop.state.DesktopAppState.reduce].
 */
fun reduceWorkspaceFrame(state: WorkspaceHostState, frame: ServerFrame): WorkspaceHostState? = when (frame) {
    is ServerFrame.Snapshot -> state.copy(
        workspaces = frame.workspaces,
        archivedWorkspaces = frame.archivedWorkspaces,
    )
    is ServerFrame.WorkspaceAdded -> {
        // The broker re-broadcasts the same workspace (early add on spawn, then the
        // authoritative one carrying repo_root / branch). Replace, never duplicate.
        val id = frame.workspace.id
        val archived = state.archivedWorkspaces.filter { it.id != id }
        val live = if (state.workspaces.none { it.id == id }) {
            state.workspaces + frame.workspace
        } else {
            state.workspaces.map { if (it.id == id) frame.workspace else it }
        }
        state.copy(workspaces = live, archivedWorkspaces = archived)
    }
    is ServerFrame.WorkspaceChanged -> {
        // Unknown id: a workspace this client never saw added. Ignore rather than
        // append: appending would put it at the end, out of sort order.
        state.copy(
            workspaces = state.workspaces.map {
                if (it.id == frame.workspace.id) frame.workspace else it
            },
        )
    }
    is ServerFrame.WorkspaceRemoved -> {
        val moving = state.workspaces.find { it.id == frame.id }
        val live = state.workspaces.filter { it.id != frame.id }
        if (moving == null) {
            state.copy(workspaces = live)
        } else {
            val archived = moving.copy(status = "archived")
            val nextArchived = if (state.archivedWorkspaces.any { it.id == frame.id }) {
                state.archivedWorkspaces.map { if (it.id == frame.id) archived else it }
            } else {
                state.archivedWorkspaces + archived
            }
            state.copy(workspaces = live, archivedWorkspaces = nextArchived)
        }
    }
    is ServerFrame.WorkspacesReordered -> {
        val rank = frame.orderedIds.withIndex().associate { (i, id) -> id to i }
        state.copy(
            workspaces = state.workspaces.map { w ->
                rank[w.id]?.let { w.copy(sortOrder = it) } ?: w
            },
        )
    }
    is ServerFrame.ViewAdded -> updateViews(state, frame.workspaceId) { it + frame.view }
    is ServerFrame.ViewRemoved -> updateViews(state, frame.workspaceId) { vs ->
        vs.filter { it.id != frame.viewId }
    }
    is ServerFrame.ViewChanged -> updateViews(state, frame.workspaceId) { vs ->
        vs.map { if (it.id == frame.view.id) frame.view else it }
    }
    is ServerFrame.ViewMoved -> moveView(state, frame)
    else -> null
}

/** Workspace that currently hosts [sessionId] as a chat view, if any. */
fun workspaceForSession(workspaces: List<WorkspaceDto>, sessionId: String): WorkspaceDto? =
    workspaces.firstOrNull { w ->
        w.status != "archived" && w.chatSessionIds().contains(sessionId)
    }

/** Do NOT rebuild either workspace's layout. The broker sends workspace_changed
 *  for both workspaces right after view_moved, carrying the authoritative trees. */
private fun moveView(state: WorkspaceHostState, frame: ServerFrame.ViewMoved): WorkspaceHostState {
    var moved: ViewDto? = null
    val stripped = state.workspaces.map { w ->
        if (w.id != frame.fromWorkspaceId) w
        else {
            moved = w.views.firstOrNull { it.id == frame.viewId }
            w.copy(views = w.views.filter { it.id != frame.viewId })
        }
    }
    val v = moved ?: return state.copy(workspaces = stripped)
    return state.copy(
        workspaces = stripped.map { w ->
            if (w.id != frame.toWorkspaceId) w
            else w.copy(views = w.views + v.copy(workspaceId = frame.toWorkspaceId))
        },
    )
}

private fun updateViews(
    state: WorkspaceHostState,
    workspaceId: String,
    edit: (List<ViewDto>) -> List<ViewDto>,
): WorkspaceHostState = state.copy(
    workspaces = state.workspaces.map {
        if (it.id == workspaceId) it.copy(views = edit(it.views)) else it
    },
)
