package dev.supermux.ui.shell

import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.firstGroupId
import dev.supermux.workspace.groupIdOf

/** The pane Chrome's tab keys act in (#122): the one last worked in ([focusedViewId]'s), else the first. */
private fun tabKeysGroup(tree: LayoutNode, focusedViewId: String?): LayoutNode.Group? {
    val groupId = focusedViewId?.let { groupIdOf(tree, it) } ?: firstGroupId(tree) ?: return null
    fun find(node: LayoutNode): LayoutNode.Group? = when (node) {
        is LayoutNode.Group -> node.takeIf { it.id == groupId }
        is LayoutNode.Split -> node.children.firstNotNullOfOrNull(::find)
    }
    return find(tree)
}

/** Next ([step] = +1) or previous (-1) tab of that pane, wrapping: its group and view, or null with nowhere to go. */
internal fun tabStep(tree: LayoutNode, focusedViewId: String?, step: Int): Pair<String, String>? {
    val group = tabKeysGroup(tree, focusedViewId) ?: return null
    val ids = group.viewIds
    if (ids.size < 2) return null
    val at = ids.indexOf(group.activeViewId).coerceAtLeast(0)
    return group.id to ids[((at + step) % ids.size + ids.size) % ids.size]
}

/** Tab [n] (1–8) of that pane, or its last for 9, as Chrome's ⌘1–⌘9: its group and view, or null when it has no such tab. */
internal fun tabNumbered(tree: LayoutNode, focusedViewId: String?, n: Int): Pair<String, String>? {
    val group = tabKeysGroup(tree, focusedViewId) ?: return null
    val id = if (n == 9) group.viewIds.lastOrNull() else group.viewIds.getOrNull(n - 1)
    return id?.let { group.id to it }
}
