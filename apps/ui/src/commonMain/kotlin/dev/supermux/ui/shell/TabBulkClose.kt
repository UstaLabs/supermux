package dev.supermux.ui.shell

import dev.supermux.proto.ViewDto
import dev.supermux.ui.session.RowContextMenuEntry
import dev.supermux.workspace.LayoutNode

/**
 * The tab menu's bulk closes: everything to the right of a tab, everything to the left of it, or
 * the whole strip. Scoped to ONE strip — the tab's own group on a wide layout, the single row on a
 * phone — the way an editor's "Close to the Right" never reaches into the pane beside it.
 */
enum class BulkClose(val label: String) {
    RIGHT("Close to the Right"),
    LEFT("Close to the Left"),
    ALL("Close All"),
}

/** The ids [which] closes from a strip of [viewIds], relative to [anchorId]. Empty when the anchor
 *  is not in the strip. */
fun bulkCloseTargets(viewIds: List<String>, anchorId: String, which: BulkClose): List<String> {
    val at = viewIds.indexOf(anchorId)
    if (at < 0) return emptyList()
    return when (which) {
        BulkClose.RIGHT -> viewIds.drop(at + 1)
        BulkClose.LEFT -> viewIds.take(at)
        BulkClose.ALL -> viewIds
    }
}

/**
 * The bulk-close menu rows for [anchorId], omitting any that would close nothing (no "Close to the
 * Right" on the last tab).
 */
fun bulkCloseEntries(
    viewIds: List<String>,
    anchorId: String,
    onBulkClose: (BulkClose, List<String>) -> Unit,
): List<RowContextMenuEntry> = BulkClose.entries.mapNotNull { which ->
    val ids = bulkCloseTargets(viewIds, anchorId, which)
    if (ids.isEmpty()) null else RowContextMenuEntry(which.label) { onBulkClose(which, ids) }
}

/** The tab ids of the group holding [viewId], in strip order; empty when no group holds it. */
fun groupViewIdsOf(node: LayoutNode, viewId: String): List<String> = when (node) {
    is LayoutNode.Group -> if (viewId in node.viewIds) node.viewIds else emptyList()
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { c -> groupViewIdsOf(c, viewId).ifEmpty { null } }
        ?: emptyList()
}

/**
 * The next tab a bulk close hands to its own close action, taken off the front of [queue]. Ids whose
 * view is already gone (closed elsewhere meanwhile) are dropped. Null once the queue is spent.
 *
 * A bulk close is only a SEQUENCE of ordinary closes: each tab closes exactly as its × would —
 * an editor silently, a chat / terminal / display behind its own question (worktree option
 * included) — one at a time, because there is one close dialog. Cancelling one skips just that tab.
 */
fun nextBulkClose(queue: MutableList<String>, viewFor: (String) -> ViewDto?): ViewDto? {
    while (queue.isNotEmpty()) {
        viewFor(queue.removeAt(0))?.let { return it }
    }
    return null
}
