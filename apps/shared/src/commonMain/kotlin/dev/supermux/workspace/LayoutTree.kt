package dev.supermux.workspace

import kotlin.math.abs

/**
 * The workspace layout tree: splits and groups, VS-Code style.
 *
 * Faithful port of src/core/workspace/layout-tree.ts. LayoutTreeTest mirrors that
 * file's test suite case for case, including the exact validation message
 * strings. When you change one side, change both — the same contract
 * PredictiveEcho.kt and TerminalKeys.kt live under.
 *
 * commonMain only: no java.*, no coroutines, no Compose. This compiles for JVM,
 * Android, and every Apple target.
 *
 * Spec: docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md §5.3
 */
sealed interface LayoutNode {
    data class Group(
        val id: String,
        val viewIds: List<String> = emptyList(),
        val activeViewId: String? = null,
    ) : LayoutNode

    data class Split(
        /** "row" places children side by side; "column" stacks them. */
        val direction: String,
        /** Fractions, one per child, all > 0, adding up to 1. */
        val sizes: List<Double> = emptyList(),
        val children: List<LayoutNode> = emptyList(),
    ) : LayoutNode
}

/** Float comparison tolerance for the sizes-add-up-to-1 rule. Same value as the TypeScript. */
private const val SIZE_EPSILON = 1e-6

fun singleViewLayout(groupId: String, viewId: String): LayoutNode =
    LayoutNode.Group(groupId, listOf(viewId), viewId)

/** Every view id in the tree, in document order. Duplicates are kept — [validateLayout] reports them. */
fun collectViewIds(node: LayoutNode): List<String> = when (node) {
    is LayoutNode.Group -> node.viewIds
    is LayoutNode.Split -> node.children.flatMap { collectViewIds(it) }
}

/**
 * The active view of every group, in document order. A chat sitting in a
 * background tab is NOT active — only these ids are on screen (spec §11).
 */
fun collectActiveViewIds(node: LayoutNode): List<String> = when (node) {
    is LayoutNode.Group -> listOfNotNull(node.activeViewId ?: node.viewIds.firstOrNull())
    is LayoutNode.Split -> node.children.flatMap { collectActiveViewIds(it) }
}

/**
 * Null when the tree is valid, or a human-readable reason when it is not.
 *
 * The client calls this BEFORE a PATCH so a bad drag never reaches the broker.
 * The messages match the broker's byte for byte, so a rejection that does slip
 * through reads the same on both sides.
 */
fun validateLayout(node: LayoutNode): String? {
    val seen = mutableSetOf<String>()

    fun walk(n: LayoutNode): String? = when (n) {
        is LayoutNode.Group -> {
            when {
                n.viewIds.isEmpty() -> "empty group: ${n.id}"
                else -> {
                    var err: String? = null
                    for (v in n.viewIds) {
                        if (!seen.add(v)) { err = "duplicate view id: $v"; break }
                    }
                    when {
                        err != null -> err
                        n.activeViewId != null && n.activeViewId !in n.viewIds ->
                            "activeViewId not in group ${n.id}: ${n.activeViewId}"
                        else -> null
                    }
                }
            }
        }
        is LayoutNode.Split -> {
            when {
                n.sizes.size != n.children.size ->
                    "split sizes length ${n.sizes.size} does not match children length ${n.children.size}"
                n.children.size < 2 ->
                    "split needs at least 2 children, got ${n.children.size}"
                n.sizes.any { it <= 0.0 } ->
                    "split sizes must all be greater than 0"
                abs(n.sizes.sum() - 1.0) > SIZE_EPSILON ->
                    "split sizes must add up to 1, got ${trimFloat(n.sizes.sum())}"
                else -> n.children.firstNotNullOfOrNull { walk(it) }
            }
        }
    }

    return walk(node)
}

/**
 * Trim float noise so a message reads "0.7", not "0.7000000000000001".
 * Mirrors the TypeScript `Number(total.toFixed(6))`.
 */
private fun trimFloat(v: Double): String {
    val rounded = kotlin.math.round(v * 1_000_000.0) / 1_000_000.0
    val s = rounded.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/**
 * Repair a tree into a valid one, or null when nothing is left.
 *
 *  - an empty group is dropped
 *  - a split with one surviving child becomes that child
 *  - a split with no surviving child is dropped
 *  - sizes are re-spread evenly ONLY when the child count changed
 *  - an activeViewId that is not in its group falls back to the first view
 *
 * Run this after every structural edit. A drag that leaves an empty group is the
 * normal case, not an error.
 */
fun normalizeLayout(node: LayoutNode): LayoutNode? = when (node) {
    is LayoutNode.Group -> {
        if (node.viewIds.isEmpty()) null
        else {
            val active = if (node.activeViewId != null && node.activeViewId in node.viewIds) node.activeViewId
                         else node.viewIds.first()
            LayoutNode.Group(node.id, node.viewIds, active)
        }
    }
    is LayoutNode.Split -> {
        val kept = node.children.mapIndexedNotNull { i, child ->
            normalizeLayout(child)?.let { it to (node.sizes.getOrNull(i) ?: 0.0) }
        }
        when {
            kept.isEmpty() -> null
            kept.size == 1 -> kept[0].first
            else -> {
                // Re-spread only when a child was dropped; an untouched split keeps
                // the user's drag positions. An even spread on every normalize would
                // reset the splitter whenever an unrelated tab closed elsewhere.
                val sizes = if (kept.size == node.children.size) kept.map { it.second }
                            else List(kept.size) { 1.0 / kept.size }
                LayoutNode.Split(node.direction, sizes, kept.map { it.first })
            }
        }
    }
}

/** Append a view to one group and make it the active tab. An unknown group id changes nothing. */
fun addViewToGroup(node: LayoutNode, groupId: String, viewId: String): LayoutNode = when (node) {
    is LayoutNode.Group -> when {
        node.id != groupId -> node
        viewId in node.viewIds -> node.copy(activeViewId = viewId)
        else -> LayoutNode.Group(node.id, node.viewIds + viewId, viewId)
    }
    is LayoutNode.Split -> node.copy(children = node.children.map { addViewToGroup(it, groupId, viewId) })
}

/** Remove a view wherever it is, then normalize. Null when the tree empties. */
fun removeViewFromLayout(node: LayoutNode, viewId: String): LayoutNode? {
    fun strip(n: LayoutNode): LayoutNode = when (n) {
        is LayoutNode.Group -> n.copy(viewIds = n.viewIds.filter { it != viewId })
        is LayoutNode.Split -> n.copy(children = n.children.map { strip(it) })
    }
    return normalizeLayout(strip(node))
}

/** The id of the first group in document order, or null for a tree with no group. */
fun firstGroupId(node: LayoutNode): String? = when (node) {
    is LayoutNode.Group -> node.id
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { firstGroupId(it) }
}

/**
 * Split one group in two: the named view moves into a NEW group beside the old
 * one, inside a split running in [direction].
 *
 * This has no TypeScript counterpart — the broker never splits, it only stores
 * what the client sends. It lives here because every Kotlin client needs the
 * same drag-to-split behaviour, and the result still has to pass [validateLayout].
 */
fun splitGroup(
    node: LayoutNode,
    groupId: String,
    viewId: String,
    direction: String,
    newGroupId: String,
): LayoutNode {
    fun walk(n: LayoutNode): LayoutNode = when (n) {
        is LayoutNode.Group -> {
            if (n.id != groupId || viewId !in n.viewIds || n.viewIds.size < 2) n
            else {
                val remaining = n.viewIds.filter { it != viewId }
                LayoutNode.Split(
                    direction = direction,
                    sizes = listOf(0.5, 0.5),
                    children = listOf(
                        LayoutNode.Group(n.id, remaining, remaining.first()),
                        LayoutNode.Group(newGroupId, listOf(viewId), viewId),
                    ),
                )
            }
        }
        is LayoutNode.Split -> n.copy(children = n.children.map { walk(it) })
    }
    return walk(node)
}

/**
 * Move [viewId] to [index] within its own group. Out-of-range indices clamp.
 * The active view is unchanged — reordering tabs must not switch which one you
 * are looking at.
 */
fun reorderWithinGroup(node: LayoutNode, groupId: String, viewId: String, index: Int): LayoutNode = when (node) {
    is LayoutNode.Group -> {
        if (node.id != groupId || viewId !in node.viewIds) node
        else {
            val rest = node.viewIds.filter { it != viewId }
            val at = index.coerceIn(0, rest.size)
            node.copy(viewIds = rest.subList(0, at) + viewId + rest.subList(at, rest.size))
        }
    }
    is LayoutNode.Split -> node.copy(children = node.children.map { reorderWithinGroup(it, groupId, viewId, index) })
}

/**
 * Move [viewId] out of wherever it is and into [toGroupId] at [index], and make
 * it active there — you dragged it, you want to see it.
 *
 * Emptying the source group collapses it, and a split left with one child
 * collapses too; that is [normalizeLayout]'s job and it runs here. Returns null
 * only if the whole tree emptied, which cannot happen while the moved view still
 * exists — but the signature stays nullable to match [removeViewFromLayout].
 */
fun moveViewToGroup(node: LayoutNode, viewId: String, toGroupId: String, index: Int): LayoutNode? {
    // Same-group move is a reorder; going through remove+add would briefly empty
    // a one-view group and collapse the split out from under the user.
    val owner = groupIdOf(node, viewId)
    if (owner == toGroupId) return reorderWithinGroup(node, toGroupId, viewId, index)
    if (!hasGroup(node, toGroupId)) return node

    val without = removeViewFromLayout(node, viewId) ?: return node
    if (!hasGroup(without, toGroupId)) return node
    return normalizeLayout(insertIntoGroup(without, toGroupId, viewId, index))
}

/** The id of the group holding [viewId], or null. */
fun groupIdOf(node: LayoutNode, viewId: String): String? = when (node) {
    is LayoutNode.Group -> node.id.takeIf { viewId in node.viewIds }
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { groupIdOf(it, viewId) }
}

private fun hasGroup(node: LayoutNode, groupId: String): Boolean = when (node) {
    is LayoutNode.Group -> node.id == groupId
    is LayoutNode.Split -> node.children.any { hasGroup(it, groupId) }
}

private fun insertIntoGroup(node: LayoutNode, groupId: String, viewId: String, index: Int): LayoutNode = when (node) {
    is LayoutNode.Group -> {
        if (node.id != groupId) node
        else {
            val at = index.coerceIn(0, node.viewIds.size)
            LayoutNode.Group(
                id = node.id,
                viewIds = node.viewIds.subList(0, at) + viewId + node.viewIds.subList(at, node.viewIds.size),
                activeViewId = viewId,
            )
        }
    }
    is LayoutNode.Split -> node.copy(children = node.children.map { insertIntoGroup(it, groupId, viewId, index) })
}
