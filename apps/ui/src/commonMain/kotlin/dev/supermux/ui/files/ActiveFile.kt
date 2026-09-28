// Which file the Files tree should follow: the editor file the user is working in.
//
// The shell has no real "focused group" (the desktop editor is a heavyweight JCEF panel, so a
// click inside it never reaches Compose). What it does know: which `file` view the user last
// touched — a press on its tab or pane, or a file tab that just became active in its group (a tab
// click, a file opened from the tree). [nextFocusedFileView] keeps that id; [activeFilePath]
// turns it into a path, falling back to the first group (layout order) showing a file.
package dev.supermux.ui.files

import dev.supermux.proto.ViewDto
import dev.supermux.proto.stateString
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectActiveViewIds

/** The document path of a `file` editor view, or null for every other view. */
fun ViewDto.filePathOrNull(): String? =
    if (kind == "editor" && stateString("mode") == "file") stateString("path") else null

/** A Files (tree) editor view: an `editor` with no mode, "tree", or any mode that isn't file/diff. */
fun ViewDto.isFilesTreeView(): Boolean =
    kind == "editor" && stateString("mode").let { it != "file" && it != "diff" }

private fun groupOf(node: LayoutNode, viewId: String): LayoutNode.Group? = when (node) {
    is LayoutNode.Group -> node.takeIf { viewId in node.viewIds }
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { groupOf(it, viewId) }
}

/**
 * The workspace-relative path the Files tree should reveal.
 *
 * The group holding [focusedViewId] wins when its active tab is a file; otherwise the first group
 * (in layout order) whose active tab is a file. Null when no group is showing a file.
 */
fun activeFilePath(layout: LayoutNode, views: Map<String, ViewDto>, focusedViewId: String?): String? {
    if (focusedViewId != null) {
        val group = groupOf(layout, focusedViewId)
        val active = group?.let { it.activeViewId ?: it.viewIds.firstOrNull() }
        active?.let { views[it] }?.filePathOrNull()?.let { return it }
    }
    return collectActiveViewIds(layout).firstNotNullOfOrNull { views[it]?.filePathOrNull() }
}

/**
 * The next "last focused file view", given the groups' active views before ([previousActive]) and
 * now ([active]): a file view that has just become active in its group takes over (the first in
 * layout order if several did); otherwise [current] stays.
 */
fun nextFocusedFileView(
    previousActive: List<String>,
    active: List<String>,
    views: Map<String, ViewDto>,
    current: String?,
): String? {
    val before = previousActive.toSet()
    return active.firstOrNull { it !in before && views[it]?.filePathOrNull() != null } ?: current
}
