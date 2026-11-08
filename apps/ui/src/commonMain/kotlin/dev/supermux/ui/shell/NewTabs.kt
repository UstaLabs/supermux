// The "+" opens a New tab, and the workspace's split buttons open a pane holding one (#91).
//
// A New tab is a real view of kind "new" (NEW_TAB_KIND), holding nothing: the broker stores it like any tab,
// so closing, dragging, splitting, reordering and resizing it are every view's paths, and other devices see it.
// Picking what it shows makes the chosen view in its pane, puts it in its place (pickedInPlace) and closes it.
package dev.supermux.ui.shell

import androidx.compose.ui.graphics.vector.ImageVector
import dev.supermux.fs.pathLeaf
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.NewViewKind
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.normalizeLayout
import dev.supermux.workspace.openSingletonView

/** The view kind of a New tab. Apps that don't know it draw their UnknownViewHint. */
const val NEW_TAB_KIND = "new"

fun ViewDto?.isNewTab(): Boolean = this?.kind == NEW_TAB_KIND

// ── The workspace's split buttons ──────────────────────────────────────────────────────────────────

/**
 * [tree] with [pane] added at the workspace's edge: "row" = a new rightmost column the full height,
 * "column" = a new bottom row the full width. A root already split that way gets one more child, the
 * others keeping their proportions.
 */
internal fun withPaneAtEdge(tree: LayoutNode, direction: String, pane: LayoutNode): LayoutNode {
    if (tree is LayoutNode.Split && tree.direction == direction) {
        val n = tree.children.size
        val sum = tree.sizes.sum()
        val sizes = if (tree.sizes.size == n && sum > 0.0) tree.sizes.map { it / sum * n / (n + 1) } else List(n) { 1.0 / (n + 1) }
        return LayoutNode.Split(direction, sizes + 1.0 / (n + 1), tree.children + pane)
    }
    return LayoutNode.Split(direction, listOf(0.5, 0.5), listOf(tree, pane))
}

/** The pane at the workspace's top-right corner: its strip carries the split buttons. */
internal fun topRightGroupId(node: LayoutNode): String = when (node) {
    is LayoutNode.Group -> node.id
    is LayoutNode.Split -> topRightGroupId(if (node.direction == "row") node.children.last() else node.children.first())
}

/**
 * The split buttons' New tab [viewId], which the broker made in some pane of [tree], moved into a pane of its own
 * ([groupId]) at the workspace's edge (see [withPaneAtEdge]). Unchanged once it is there, so it replays.
 */
internal fun newTabToEdge(tree: LayoutNode, viewId: String, direction: String, groupId: String): LayoutNode {
    if (groupIdOf(tree, viewId) == groupId) return tree
    val rest = normalizeLayout(withoutView(tree, viewId)) ?: return LayoutNode.Group(groupId, listOf(viewId), viewId)
    return withPaneAtEdge(rest, direction, LayoutNode.Group(groupId, listOf(viewId), viewId))
}

// ── Picking ────────────────────────────────────────────────────────────────────────────────────────

/** What picking [kind] does: switch to the workspace's one Files or Changes, or make a new view. */
internal sealed interface NewTabPick {
    data class SwitchTo(val viewId: String, val groupId: String) : NewTabPick
    data object Add : NewTabPick
}

internal fun newTabPick(kind: NewViewKind, tree: LayoutNode, viewsById: Map<String, ViewDto>): NewTabPick =
    openSingletonView(tree, viewsById, kind)?.let { (viewId, groupId) -> NewTabPick.SwitchTo(viewId, groupId) } ?: NewTabPick.Add

/**
 * [tree] with [viewId], picked in New tab [newTabId], in the New tab's place: just before it in its pane, shown.
 * The broker may have added it to another pane; it leaves that one. Closing the New tab is the broker's.
 */
internal fun pickedInPlace(tree: LayoutNode, newTabId: String, viewId: String): LayoutNode {
    val target = groupIdOf(tree, newTabId) ?: return tree
    fun place(node: LayoutNode): LayoutNode = when (node) {
        is LayoutNode.Group -> when {
            node.id == target -> {
                val ids = node.viewIds - viewId
                val at = ids.indexOf(newTabId)
                LayoutNode.Group(node.id, ids.subList(0, at) + viewId + ids.subList(at, ids.size), viewId)
            }
            viewId in node.viewIds -> {
                val ids = node.viewIds - viewId
                LayoutNode.Group(node.id, ids, node.activeViewId?.takeIf { it != viewId } ?: ids.firstOrNull())
            }
            else -> node
        }
        is LayoutNode.Split -> node.copy(children = node.children.map(::place))
    }
    return normalizeLayout(place(tree)) ?: tree
}

private fun withoutView(node: LayoutNode, viewId: String): LayoutNode = when (node) {
    is LayoutNode.Group -> if (viewId !in node.viewIds) node else {
        val ids = node.viewIds - viewId
        LayoutNode.Group(node.id, ids, node.activeViewId?.takeIf { it != viewId } ?: ids.firstOrNull())
    }
    is LayoutNode.Split -> node.copy(children = node.children.map { withoutView(it, viewId) })
}

// ── The page's rows ────────────────────────────────────────────────────────────────────────────────

/** One row of the page: a view kind, what it is for, and whether this workspace already has its one. */
internal data class NewTabChoice(
    val kind: NewViewKind,
    val icon: ImageVector,
    val label: String,
    val detail: String,
    val alreadyOpen: Boolean = false,
)

/** A row per kind the "+" offers ([kinds]: the phone's are [phoneAddKinds]). */
internal fun newTabChoices(
    workspace: WorkspaceDto,
    layout: LayoutNode,
    viewsById: Map<String, ViewDto>,
    kinds: List<NewViewKind> = NewViewKind.entries,
): List<NewTabChoice> {
    val folder = pathLeaf(workspace.repoRoot ?: workspace.workdir)
    return kinds.map { k ->
        NewTabChoice(
            kind = k,
            icon = when (k) {
                NewViewKind.CHAT -> NewTabGlyphs.Chat
                NewViewKind.TERMINAL -> NewTabGlyphs.Terminal
                NewViewKind.EDITOR -> NewTabGlyphs.Files
                NewViewKind.DIFF -> NewTabGlyphs.Changes
                NewViewKind.DISPLAY -> NewTabGlyphs.Display
            },
            label = k.label,
            detail = when (k) {
                NewViewKind.CHAT -> "Start a chat with an agent"
                NewViewKind.TERMINAL -> "A shell in $folder"
                NewViewKind.EDITOR -> "Browse and edit files"
                NewViewKind.DIFF -> "Review what changed"
                NewViewKind.DISPLAY -> "Watch an agent's screen"
            },
            alreadyOpen = k.singleton && openSingletonView(layout, viewsById, k) != null,
        )
    }
}
