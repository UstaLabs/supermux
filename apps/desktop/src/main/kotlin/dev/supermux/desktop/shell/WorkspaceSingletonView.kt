// One-per-workspace views: which "+" kinds may exist only once, and how to find the one that
// already does.
//
// Files (the file tree) and Changes (the working-tree diff) both show the WHOLE workspace, so a
// second copy of either is the same pane twice — it cannot show anything the first one does not.
// Picking one that is open therefore reveals it rather than adding a duplicate, the same rule
// WorkspaceFileOpen.kt already applies per path: one pane per file, one pane per tree.
package dev.supermux.desktop.shell

import dev.supermux.proto.ViewDto
import dev.supermux.proto.stateString
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.groupIdOf

/**
 * True when [this] is the file tree — an `editor` view that is neither a `file` nor a `diff`.
 *
 * Written as "not the other two" rather than `mode == "tree"` to match [viewTitle], which falls
 * back to the tree for an editor with no mode at all.
 */
internal fun ViewDto.isExplorerView(): Boolean =
    kind == "editor" && stateString("mode").let { it != "file" && it != "diff" }

/** True when [this] is a `diff` pane — the Changes view. */
internal fun ViewDto.isDiffView(): Boolean = kind == "editor" && stateString("mode") == "diff"

/** True when [this] view is what picking [kind] in the "+" would have created. */
internal fun ViewDto.matchesKind(kind: NewViewKind): Boolean = when (kind) {
    NewViewKind.EDITOR -> isExplorerView()
    NewViewKind.DIFF -> isDiffView()
    // Neither is a singleton, so nothing asks; answering "no" keeps every non-singleton pick
    // going straight to a fresh view.
    NewViewKind.CHAT, NewViewKind.TERMINAL, NewViewKind.DISPLAY -> false
}

/**
 * The already-open view for a singleton [kind] as `viewId to groupId`, or null when picking it
 * should make a new one.
 *
 * Null for a non-singleton kind, and null for a view [views] knows about but [tree] does not hold:
 * a view outside the layout is not really open, so a pick must be free to place a fresh one —
 * [planFileOpen] falls through on exactly the same condition.
 */
internal fun openSingletonView(
    tree: LayoutNode,
    views: Map<String, ViewDto>,
    kind: NewViewKind,
): Pair<String, String>? {
    if (!kind.singleton) return null
    return views.values.firstNotNullOfOrNull { v ->
        if (!v.matchesKind(kind)) null else groupIdOf(tree, v.id)?.let { g -> v.id to g }
    }
}
