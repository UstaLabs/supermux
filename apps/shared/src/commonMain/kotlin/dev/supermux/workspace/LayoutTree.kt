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

/**
 * Make [viewId] the active tab of [groupId]. An unknown group, or a view that
 * does not live in that group, changes nothing.
 *
 * Unlike [addViewToGroup] this never inserts. It exists so the tab strip can say
 * WHICH group to change by id instead of handing back a rebuilt parent node: a
 * Compose callback can outlive the composition that created it, and a rebuilt
 * parent carries a stale copy of every sibling with it. Naming the group means a
 * stale callback misses and does nothing, instead of reverting the tree.
 *
 * No TypeScript counterpart — like [splitGroup], the broker only stores what the
 * client computes.
 */
fun setActiveViewInGroup(node: LayoutNode, groupId: String, viewId: String): LayoutNode = when (node) {
    is LayoutNode.Group ->
        if (node.id != groupId || viewId !in node.viewIds) node else node.copy(activeViewId = viewId)
    is LayoutNode.Split -> node.copy(children = node.children.map { setActiveViewInGroup(it, groupId, viewId) })
}

/**
 * Replace the sizes of the split at [path] — the child indices to walk from the
 * root, so `[]` is the root itself and `[0, 1]` is the second child of the first.
 *
 * Splits are addressed by path because [LayoutNode.Split] has no id; only
 * [LayoutNode.Group] does. A path that runs off the tree, or that lands on a
 * group, or a [sizes] list of the wrong length changes nothing — which is the
 * point. A splitter drag handler holds its path from when Compose last built it,
 * and the tree may have changed underneath; missing is correct, and far better
 * than writing back the children that split had at capture time.
 *
 * No TypeScript counterpart — the broker only stores what the client computes.
 */
fun setSplitSizes(node: LayoutNode, path: List<Int>, sizes: List<Double>): LayoutNode {
    if (path.isEmpty()) {
        return if (node !is LayoutNode.Split || sizes.size != node.children.size) node
               else node.copy(sizes = sizes)
    }
    if (node !is LayoutNode.Split) return node
    val i = path.first()
    if (i !in node.children.indices) return node
    val child = setSplitSizes(node.children[i], path.drop(1), sizes)
    if (child === node.children[i]) return node
    return node.copy(children = node.children.toMutableList().also { it[i] = child })
}

/**
 * Move seam [seam] of [split] (the boundary between children `seam` and
 * `seam + 1`) by [delta], a fraction of the split's own extent, WITHOUT moving
 * any other seam on screen.
 *
 * Sizes are fractions of the parent, so plainly rewriting this split's two
 * sizes rescales every same-direction split nested inside those two children:
 * in `A | [B | C]`, dragging A|B would drag B|C along with it. Instead only the
 * descendant pane that touches the dragged seam absorbs the change, and every
 * other nested seam keeps its absolute position. Splits running the other way
 * are unaffected along this axis and are just walked through.
 *
 * No pane on the dragged seam's path shrinks below [minLeaf] of the two
 * children's combined extent; a drag past that clamps. Pure and total — an out
 * of range [seam] returns [split] unchanged.
 *
 * No TypeScript counterpart — the broker only stores what the client computes.
 */
fun resizeSplitSeam(
    split: LayoutNode.Split,
    seam: Int,
    delta: Double,
    minLeaf: Double = 0.05,
): LayoutNode.Split {
    if (seam < 0 || seam + 1 >= split.children.size || seam + 1 >= split.sizes.size) return split
    val dir = split.direction
    val a = split.sizes[seam]
    val b = split.sizes[seam + 1]
    val pair = a + b
    val min = minLeaf * pair
    val lo = minExtent(split.children[seam], dir, a, edgeAtEnd = true, min)
    val hi = pair - minExtent(split.children[seam + 1], dir, b, edgeAtEnd = false, min)
    if (lo > hi) return split
    val nextA = (a + delta).coerceIn(lo, hi)
    val nextB = pair - nextA
    return split.copy(
        sizes = split.sizes.toMutableList().also {
            it[seam] = nextA
            it[seam + 1] = nextB
        },
        children = split.children.toMutableList().also {
            it[seam] = rescaleEdge(it[seam], dir, a, nextA, edgeAtEnd = true, min)
            it[seam + 1] = rescaleEdge(it[seam + 1], dir, b, nextB, edgeAtEnd = false, min)
        },
    )
}

/**
 * Every split in [node] as (path relative to [node], sizes), [node] first. Lets
 * a caller replay a [resizeSplitSeam] result as plain [setSplitSizes] writes,
 * which stay idempotent when a pending edit is rebased onto a newer tree.
 */
fun splitSizesByPath(node: LayoutNode): List<Pair<List<Int>, List<Double>>> {
    val out = mutableListOf<Pair<List<Int>, List<Double>>>()
    fun walk(n: LayoutNode, path: List<Int>) {
        if (n !is LayoutNode.Split) return
        out += path to n.sizes
        n.children.forEachIndexed { i, c -> walk(c, path + i) }
    }
    walk(node, emptyList())
    return out
}

/** Smallest extent [node] can take along [dir] if only its [edgeAtEnd] side moves. */
private fun minExtent(node: LayoutNode, dir: String, extent: Double, edgeAtEnd: Boolean, min: Double): Double =
    when (node) {
        is LayoutNode.Group -> min
        is LayoutNode.Split -> if (node.direction != dir) {
            node.children.maxOfOrNull { minExtent(it, dir, extent, edgeAtEnd, min) } ?: min
        } else {
            val k = if (edgeAtEnd) node.children.lastIndex else 0
            node.children.indices.sumOf { i ->
                val e = node.sizes.getOrElse(i) { 0.0 } * extent
                if (i == k) minExtent(node.children[i], dir, e, edgeAtEnd, min) else e
            }
        }
    }

/**
 * Resize [node] from [old] to [new] along [dir], giving the change to the pane on
 * its moving edge ([edgeAtEnd]: the right/bottom edge, else the left/top one).
 * A shrink that would take that pane below [min] passes the rest on to the next
 * pane inward, and so on; if every pane is already at [min], what is left is
 * shared proportionally rather than dropped, so sizes still add up to 1.
 */
private fun rescaleEdge(
    node: LayoutNode,
    dir: String,
    old: Double,
    new: Double,
    edgeAtEnd: Boolean,
    min: Double,
): LayoutNode = when (node) {
    is LayoutNode.Group -> node
    is LayoutNode.Split -> when {
        node.direction != dir ->
            node.copy(children = node.children.map { rescaleEdge(it, dir, old, new, edgeAtEnd, min) })
        new <= 0.0 || old <= 0.0 || node.sizes.size != node.children.size -> node
        else -> {
            val oldE = node.sizes.map { it * old }
            val newE = oldE.toMutableList()
            val order = if (edgeAtEnd) node.children.indices.reversed() else node.children.indices
            var change = new - old
            if (change >= 0) {
                newE[order.first()] += change
            } else {
                for (i in order) {
                    if (change >= 0) break
                    val give = minOf(-change, (oldE[i] - minExtent(node.children[i], dir, oldE[i], edgeAtEnd, min)).coerceAtLeast(0.0))
                    newE[i] -= give
                    change += give
                }
                if (change < 0) {
                    // Every pane is at its minimum: squeeze them all alike.
                    val sum = newE.sum()
                    for (i in newE.indices) newE[i] = newE[i] * new / sum
                }
            }
            val total = newE.sum()
            node.copy(
                sizes = newE.map { it / total },
                children = node.children.mapIndexed { i, c ->
                    if (newE[i] == oldE[i]) c else rescaleEdge(c, dir, oldE[i], newE[i], edgeAtEnd, min)
                },
            )
        }
    }
}

/**
 * Re-fit [node] to a pane area whose edges moved, keeping every inner seam where
 * it was on screen: only the panes along an edge that moved grow or shrink.
 *
 * Extents are in any one unit (pixels, in practice). [oldWidth] → [newWidth] is
 * the area's width; [startShiftX] is how far its LEFT edge moved (positive =
 * rightwards); the right edge moved by the rest. The same for height/top. Panes
 * stay at least [min]; a shrink past that cascades inward (see [rescaleEdge]).
 * Returns [node] itself when nothing changed.
 *
 * No TypeScript counterpart — the broker only stores what the client computes.
 */
fun resizeLayoutEdges(
    node: LayoutNode,
    oldWidth: Double,
    newWidth: Double,
    startShiftX: Double,
    oldHeight: Double,
    newHeight: Double,
    startShiftY: Double,
    min: Double,
): LayoutNode {
    if (oldWidth <= 0 || newWidth <= 0 || oldHeight <= 0 || newHeight <= 0) return node
    var n = node
    if (oldWidth != newWidth || startShiftX != 0.0) {
        val mid = oldWidth - startShiftX
        if (mid > 0) {
            n = rescaleEdge(n, "row", oldWidth, mid, edgeAtEnd = false, min)
            n = rescaleEdge(n, "row", mid, newWidth, edgeAtEnd = true, min)
        } else {
            n = rescaleEdge(n, "row", oldWidth, newWidth, edgeAtEnd = true, min)
        }
    }
    if (oldHeight != newHeight || startShiftY != 0.0) {
        val mid = oldHeight - startShiftY
        if (mid > 0) {
            n = rescaleEdge(n, "column", oldHeight, mid, edgeAtEnd = false, min)
            n = rescaleEdge(n, "column", mid, newHeight, edgeAtEnd = true, min)
        } else {
            n = rescaleEdge(n, "column", oldHeight, newHeight, edgeAtEnd = true, min)
        }
    }
    return n
}

/** The node at [path] (child indices from [node]), or null when the path runs off the tree. */
fun layoutNodeAt(node: LayoutNode, path: List<Int>): LayoutNode? {
    var n = node
    for (i in path) {
        n = (n as? LayoutNode.Split)?.children?.getOrNull(i) ?: return null
    }
    return n
}

/** Remove a view wherever it is, then normalize. Null when the tree empties. */
fun removeViewFromLayout(node: LayoutNode, viewId: String): LayoutNode? {
    fun strip(n: LayoutNode): LayoutNode = when (n) {
        is LayoutNode.Group -> n.copy(viewIds = n.viewIds.filter { it != viewId })
        is LayoutNode.Split -> n.copy(children = n.children.map { strip(it) })
    }
    return normalizeLayout(strip(node))
}

/**
 * Smallest node whose view ids are exactly [viewIds]. Null when the set is empty,
 * not fully contained, or not equal to any one node's ids (e.g. a diagonal claim).
 *
 * Desktop-only: extra OS windows host a claim-set subtree. No TypeScript counterpart.
 */
fun subtreeCovering(node: LayoutNode, viewIds: Set<String>): LayoutNode? {
    if (viewIds.isEmpty()) return null
    val here = collectViewIds(node).toSet()
    if (!here.containsAll(viewIds)) return null
    if (here != viewIds) {
        return when (node) {
            is LayoutNode.Group -> null
            is LayoutNode.Split -> node.children.firstNotNullOfOrNull { subtreeCovering(it, viewIds) }
        }
    }
    if (node is LayoutNode.Split) {
        node.children.firstNotNullOfOrNull { subtreeCovering(it, viewIds) }?.let { return it }
    }
    return node
}

/**
 * Tree minus [claimed] view ids, then [normalizeLayout]. Null when nothing is left.
 *
 * What the main canvas draws after other OS windows have claimed subtrees.
 * Empty [claimed] is a no-op (the empty-claim-means-whole-canvas case lives at the caller).
 */
fun hideClaimed(node: LayoutNode, claimed: Set<String>): LayoutNode? {
    if (claimed.isEmpty()) return node
    var t: LayoutNode? = node
    for (id in claimed) {
        t = t?.let { removeViewFromLayout(it, id) }
    }
    return t?.let { normalizeLayout(it) }
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
