package dev.supermux.desktop.shell

import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.moveId

/**
 * Scope for workspace drag-reorder (project group key, or a flat-list bucket).
 * PA workspaces are never reorderable and never appear in a working order.
 */
data class WorkspaceReorderScope(val key: String)

data class WorkspaceReorderMove(
    val scope: WorkspaceReorderScope,
    val orderedIds: List<String>,
)

/** Flat (ungrouped) non-PA workspace list shares one reorder scope. */
const val WORKSPACE_FLAT_SCOPE = "__flat__"

/**
 * Live drag-working order (Android [dev.supermux.android.session.SessionDragWorkingState] parity).
 * Captures the original order at drag start and the current order while neighbors animate;
 * [finish] returns a commit only when the order actually changed.
 */
class WorkspaceDragWorkingState {
    private var scope: WorkspaceReorderScope? = null
    private var original: List<String> = emptyList()
    private var current: List<String> = emptyList()

    fun begin(scope: WorkspaceReorderScope, orderedIds: List<String>) {
        this.scope = scope
        original = orderedIds
        current = orderedIds
    }

    fun beginIfIdle(scope: WorkspaceReorderScope, orderedIds: List<String>) {
        if (this.scope == null) begin(scope, orderedIds)
    }

    fun move(orderedIds: List<String>) {
        if (scope != null) current = orderedIds
    }

    fun finish(commit: Boolean): WorkspaceReorderMove? {
        val finishedScope = scope
        val result = if (commit && finishedScope != null && current != original) {
            WorkspaceReorderMove(finishedScope, current)
        } else {
            null
        }
        scope = null
        original = emptyList()
        current = emptyList()
        return result
    }
}

/** Apply a live working order over [workspaces] (missing ids keep their relative position at the end). */
fun applyWorkspaceWorkingOrder(
    workspaces: List<WorkspaceDto>,
    workingOrder: List<String>?,
): List<WorkspaceDto> {
    if (workingOrder.isNullOrEmpty()) return workspaces
    val byId = workspaces.associateBy { it.id }
    val ordered = workingOrder.mapNotNull { byId[it] }
    val rest = workspaces.filter { it.id !in workingOrder }
    return ordered + rest
}

/**
 * Move [fromId] to [toId]'s slot within the same scope. Returns null when scopes differ,
 * either id is unknown, or the order is unchanged.
 */
fun moveWorkspaceWithinScope(
    rows: List<WorkspaceDto>,
    workingOrders: Map<String, List<String>>,
    scopeKey: String,
    fromId: String,
    toId: String,
): WorkspaceReorderMove? {
    val scope = WorkspaceReorderScope(scopeKey)
    val ids = workingOrders[scopeKey] ?: rows.map { it.id }
    val fromIndex = ids.indexOf(fromId)
    val toIndex = ids.indexOf(toId)
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return null
    if (fromId !in rows.map { it.id } || toId !in rows.map { it.id }) return null
    return WorkspaceReorderMove(scope, moveId(ids, fromIndex, toIndex))
}
