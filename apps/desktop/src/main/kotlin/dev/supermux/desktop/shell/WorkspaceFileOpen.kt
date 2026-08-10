// Opening a file in a workspace: where the tab goes, and how it gets there without waiting for the
// network (spec §9.0).
//
// Before phase 3 an open was local and instant — the editor kept its own tab list in client memory.
// Now a file tab IS a workspace view, so an open is `POST /workspaces/:id/views`, and a tab that
// appears one round trip later feels broken. The repair is the client-minted id the broker learned
// to accept in 3bfb400c: mint a v4 UUID, put the tab in the tree AT ONCE, and send the request
// after. [WorkspaceLayoutState] already replays an unconfirmed edit over every `workspace_changed`
// frame, and the broker's echo carries the same id, so the replay is a no-op rather than a
// duplicate. That is the exact case that class was written for.
//
// The one gap the optimistic path opens is that the layout names a view id the broker has not told
// anyone about yet, so `titleFor` would say "view" and ViewHost would draw nothing. The caller
// therefore keeps a PROVISIONAL ViewDto until the real one lands — see [WorkspaceFileOpener]'s
// `provisional` map and AppShell's merge (the broker always wins on a collision).
package dev.supermux.desktop.shell

import dev.supermux.proto.ViewDto
import dev.supermux.proto.stateString
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.addViewToGroup
import dev.supermux.workspace.firstGroupId
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.removeViewFromLayout
import dev.supermux.workspace.setActiveViewInGroup
import dev.supermux.workspace.splitGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/** True when [this] is a `file` pane — an editor view whose mode names one document. */
internal fun ViewDto.isFileView(): Boolean = kind == "editor" && stateString("mode") == "file"

/** The `file` pane already showing [path], anywhere in the workspace. */
internal fun Map<String, ViewDto>.fileViewFor(path: String): ViewDto? =
    values.firstOrNull { it.isFileView() && it.stateString("path") == path }

/**
 * The first group in DOCUMENT ORDER holding a `file` pane, or null when the workspace has none.
 * Document order, not "the group I came from": a second file opened from the tree must join the
 * files it belongs with, however the user has since rearranged the panes.
 */
internal fun firstGroupWithFileView(node: LayoutNode, views: Map<String, ViewDto>): String? = when (node) {
    is LayoutNode.Group -> node.id.takeIf { node.viewIds.any { id -> views[id]?.isFileView() == true } }
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { firstGroupWithFileView(it, views) }
}

/** What an open of one path should do to the layout. Pure, so the rules are testable on their own. */
internal sealed interface FileOpenPlan {
    /** A pane already shows the path: select it. Never open a second one. */
    data class Activate(val viewId: String, val groupId: String) : FileOpenPlan

    /** The workspace already has files somewhere: the new tab joins them. */
    data class AddToGroup(val groupId: String) : FileOpenPlan

    /**
     * No file pane exists yet. Split the group the request came from, new group to the RIGHT —
     * the tree sits BESIDE the files rather than tabbed with them.
     */
    data class SplitFrom(val groupId: String) : FileOpenPlan

    /** There is no group to put anything in (an empty, unreadable layout). */
    data object Nowhere : FileOpenPlan
}

/**
 * Decide where an open of [path] lands.
 *
 * [sourceViewId] is the view the request came FROM (the explorer that was clicked, the chat whose
 * transcript was tapped) — used only when there is nowhere to join, to pick which group to split.
 */
internal fun planFileOpen(
    tree: LayoutNode,
    views: Map<String, ViewDto>,
    path: String,
    sourceViewId: String?,
): FileOpenPlan {
    val existing = views.fileViewFor(path)
    if (existing != null) {
        val group = groupIdOf(tree, existing.id)
        // A view the layout does not hold is not really open — fall through and place a new one.
        if (group != null) return FileOpenPlan.Activate(existing.id, group)
    }
    firstGroupWithFileView(tree, views)?.let { return FileOpenPlan.AddToGroup(it) }
    val source = sourceViewId?.let { groupIdOf(tree, it) } ?: firstGroupId(tree) ?: return FileOpenPlan.Nowhere
    // Nothing to split AWAY from: an empty group is already the file's own pane.
    return if (viewIdsOfGroup(tree, source).isEmpty()) FileOpenPlan.AddToGroup(source)
    else FileOpenPlan.SplitFrom(source)
}

/** The views held by one group, or empty when the tree has no such group. */
private fun viewIdsOfGroup(node: LayoutNode, groupId: String): List<String> = when (node) {
    is LayoutNode.Group -> if (node.id == groupId) node.viewIds else emptyList()
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull {
        viewIdsOfGroup(it, groupId).takeIf { ids -> ids.isNotEmpty() }
    } ?: emptyList()
}

/** The view state of a `file` pane. */
internal fun fileViewState(path: String): JsonObject = buildJsonObject {
    put("mode", JsonPrimitive("file"))
    put("path", JsonPrimitive(path))
}

/**
 * Opens files into a workspace's layout, optimistically.
 *
 * Everything it touches is injected, so the whole open — including the rollback of a rejected POST
 * — is testable without Compose or a broker. AppShell builds one of these per composition (it holds
 * no state of its own; the provisional map and the layout live outside it).
 */
internal class WorkspaceFileOpener(
    private val workspaceId: String,
    /** The layout as it stands right now. Read at call time — never captured. */
    private val treeOf: () -> LayoutNode,
    /** Every view the workspace has, provisional ones included. Read at call time. */
    private val viewsOf: () -> Map<String, ViewDto>,
    /** Apply a layout EDIT (a transform, not a tree) so it can be replayed over a broker frame. */
    private val edit: ((LayoutNode) -> LayoutNode) -> Unit,
    /** Views this client has created but the broker has not confirmed yet. */
    private val provisional: MutableMap<String, ViewDto>,
    /** Load the document, and reveal a line in it if one was asked for. */
    private val reveal: (path: String, line: Int?, endLine: Int?) -> Unit,
    /** POST the view with the id we already put in the tree. False → the open is rolled back. */
    private val post: suspend (id: String, state: JsonObject, groupId: String) -> Boolean,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun open(path: String, line: Int? = null, endLine: Int? = null, sourceViewId: String? = null) {
        // The document first, always: the pane reads it out of the store, and a re-open of an
        // already-open file is only ever about the reveal.
        reveal(path, line, endLine)

        val views = viewsOf()
        when (val plan = planFileOpen(treeOf(), views, path, sourceViewId)) {
            is FileOpenPlan.Activate -> edit { setActiveViewInGroup(it, plan.groupId, plan.viewId) }
            is FileOpenPlan.AddToGroup -> place(path, plan.groupId, split = false)
            is FileOpenPlan.SplitFrom -> place(path, plan.groupId, split = true)
            FileOpenPlan.Nowhere ->
                println("[WorkspaceFileOpener] no group to open '$path' into — layout is empty")
        }
    }

    private fun place(path: String, groupId: String, split: Boolean) {
        val id = newId()
        val state = fileViewState(path)
        provisional[id] = ViewDto(id = id, workspaceId = workspaceId, kind = "editor", state = state)

        if (split) {
            // Add THEN split, the same two tested primitives the "+" menu uses: splitGroup refuses
            // a group with fewer than two views, and an empty group would fail validateLayout, so
            // there is no way to make a fresh group directly. Every intermediate tree stays valid.
            val newGroupId = newId()
            edit { tree -> splitGroup(addViewToGroup(tree, groupId, id), groupId, id, "row", newGroupId) }
        } else {
            edit { tree -> addViewToGroup(tree, groupId, id) }
        }

        scope.launch {
            if (post(id, state, groupId)) return@launch
            // A rejected POST must leave NO trace: a tab whose view will never exist draws as
            // "view" forever and cannot be closed through the broker.
            provisional.remove(id)
            edit { tree -> removeViewFromLayout(tree, id) ?: tree }
        }
    }
}
