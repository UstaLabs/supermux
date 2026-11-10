package dev.supermux.workspace

import dev.supermux.proto.ViewDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Puts a new view in the layout before the broker has it, then POSTs it (spec §9.0): into [groupId], or with [split]
 * into a new group split from it to the RIGHT. A POST the broker refuses, or answers with another id, takes the tab
 * back out. The file opener's and the Browser tab opener's, so both keep the file opener's hard-won rules (below).
 */
internal fun placeNewView(
    id: String,
    kind: String,
    state: JsonObject,
    groupId: String,
    split: Boolean,
    workspaceId: String,
    provisional: MutableMap<String, ViewDto>,
    edit: ((LayoutNode) -> LayoutNode) -> Unit,
    post: suspend (id: String, state: JsonObject, groupId: String) -> String?,
    scope: CoroutineScope,
    newId: () -> String,
    onPlaced: (viewId: String) -> Unit,
    onWithdrawn: () -> Unit,
) {
    provisional[id] = ViewDto(id = id, workspaceId = workspaceId, kind = kind, state = state)
    if (split) {
        // Add THEN split, the same two tested primitives the "+" menu uses: splitGroup refuses
        // a group with fewer than two views, and an empty group would fail validateLayout, so
        // there is no way to make a fresh group directly. Every intermediate tree stays valid.
        //
        // Minted OUTSIDE the transform: a replay must land on the SAME group id, not invent a
        // new one each time it is rebased onto a frame.
        val newGroupId = newId()
        edit { tree ->
            // Address the view's CURRENT group, never the one it started in. This transform is
            // replayed over every workspace_changed frame, and the split's whole job is to move
            // the view OUT of `groupId` — so a naive `addViewToGroup(tree, groupId, id)` stops
            // finding it there on the second pass and adds it AGAIN. That is what "opening a
            // file opens it twice" was: the same file in two groups at once.
            when (val owner = groupIdOf(tree, id)) {
                // Already split out (our own edit, echoed back). Nothing to do.
                newGroupId -> tree
                // Not placed yet: put it in the source group so splitGroup has two to divide.
                null -> splitGroup(addViewToGroup(tree, groupId, id), groupId, id, "row", newGroupId)
                // The broker placed it (it runs addViewToGroup for the groupId we POSTed).
                // Split it out of wherever it actually landed.
                else -> splitGroup(tree, owner, id, "row", newGroupId)
            }
        }
    } else {
        edit { tree -> addViewToGroup(tree, groupId, id) }
    }
    onPlaced(id)

    scope.launch {
        val created = post(id, state, groupId)
        if (created == id) return@launch
        // Either the POST was rejected (null), or the broker created the view under a DIFFERENT
        // id than the one we drew. Both leave the same wreckage if ignored: a tab naming a view
        // that will never exist, which draws as "view", cannot be closed through the broker, and
        // pins the layout write open forever waiting for a confirmation that cannot come.
        //
        // So both take the same exit — withdraw the optimistic tab and let the broker's own
        // placement stand. Withdrawing is safe to compose: this edit is applied AFTER the split
        // that created the tab, so on every replay the pair cancels out rather than fighting.
        // A mismatched id costs the split (the tab lands wherever the broker put it) but never
        // an unclosable duplicate — degrade, don't wedge.
        if (created != null) {
            println("[placeNewView] broker created the $kind view as $created, not the $id we drew — withdrawing our tab")
        }
        provisional.remove(id)
        onWithdrawn()
        edit { tree -> removeViewFromLayout(tree, id) ?: tree }
    }
}

/** The views held by one group, or empty when the tree has no such group. */
internal fun viewIdsOfGroup(node: LayoutNode, groupId: String): List<String> = when (node) {
    is LayoutNode.Group -> if (node.id == groupId) node.viewIds else emptyList()
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull {
        viewIdsOfGroup(it, groupId).takeIf { ids -> ids.isNotEmpty() }
    } ?: emptyList()
}
