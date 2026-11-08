// The "+" opens a New tab, and the workspace's split buttons open a pane holding one (#91).
//
// A New tab lives on this client only. Until a view is picked it is a question, not workspace state:
// the broker never hears of it (it refuses a layout naming a view it does not have), so other devices
// do not see it and a restart does not bring it back. WorkspacePanes draws New tabs into the tree it
// hands PaneHost (withNewTabs) and replays every edit PaneHost makes on the real tree with them put in
// first and taken out after (withoutNewTabs) — the same replay rule WorkspaceLayoutState follows.
package dev.supermux.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.vector.ImageVector
import dev.supermux.fs.pathLeaf
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.panes.TabDropEndpoint
import dev.supermux.ui.panes.TabHandoff
import dev.supermux.ui.panes.TabTearOff
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.NewViewKind
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.normalizeLayout
import dev.supermux.workspace.openSingletonView
import dev.supermux.workspace.setActiveViewInGroup
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A New tab's id. Never a view id: those are bare UUIDs. */
internal const val NEW_TAB_PREFIX = "newtab:"

internal fun isNewTabId(id: String): Boolean = id.startsWith(NEW_TAB_PREFIX)

/**
 * A pane that exists only until its New tab is given a view, split "row" (right) or "column" (down).
 * [edge]: at the edge of the whole workspace — a new rightmost column or bottom row, drawn around
 * everything drawn before it; otherwise beside [anchorGroupId]. A pick's view is first added to
 * [anchorGroupId] when that pane is real (the broker needs a real one). [share]: the part of its split
 * it takes — where its seam was dragged to, since the real tree has no split to keep that in; null
 * shares the split equally.
 */
internal data class PendingPane(
    val anchorGroupId: String,
    val direction: String,
    val edge: Boolean = false,
    val share: Double? = null,
)

/**
 * Where one New tab sits: its group, its index among everything that group's strip shows, and
 * whether it is the tab on show. [shownOver] is the group's real active tab when it was selected: if
 * the broker makes another tab active there (a file opened from a chat), that one shows instead.
 * [pane]: it is the only tab of a pane that is not real yet. [seq]: when it was opened — pending
 * panes are drawn in that order (the map holding them has none).
 */
internal data class NewTabSlot(
    val groupId: String,
    val index: Int,
    val selected: Boolean,
    val shownOver: String? = null,
    val pane: PendingPane? = null,
    val seq: Int = 0,
)

/** One workspace's New tabs, kept with its panes (WorkspacePanesKept) so a rotation keeps them. */
internal class NewTabsState {
    val slots = mutableStateMapOf<String, NewTabSlot>()

    /** The kind a New tab is turning into, while the broker makes it. */
    val opening = mutableStateMapOf<String, NewViewKind>()

    /** New tab id → the view that replaces it, once the broker has made it. */
    val replacedBy = mutableStateMapOf<String, String>()

    /** New tab id → the real group a pick added its view to, and the tab that group showed before. */
    val pickedFrom = mutableMapOf<String, Pair<String, String?>>()

    private var opened = 0

    /** The "+": a New tab at the end of [groupId], or the one already there, shown. */
    @OptIn(ExperimentalUuidApi::class)
    fun open(groupId: String, realActive: String?) {
        val existing = slots.entries.firstOrNull { it.value.groupId == groupId }
        if (existing != null) {
            slots[existing.key] = existing.value.copy(selected = true, shownOver = realActive)
            return
        }
        slots[NEW_TAB_PREFIX + Uuid.random()] =
            NewTabSlot(groupId, Int.MAX_VALUE, selected = true, shownOver = realActive, seq = ++opened)
    }

    /**
     * The workspace's split buttons (and #96's workspace.splitRight / splitDown): a pane holding a New
     * tab as a new rightmost column ("row") or bottom row ("column") of the whole workspace.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun splitWorkspace(direction: String, tree: LayoutNode) {
        slots[NEW_TAB_PREFIX + Uuid.random()] = NewTabSlot(
            groupId = Uuid.random().toString(),
            index = 0,
            selected = true,
            pane = PendingPane(topRightGroupId(tree), direction, edge = true),
            seq = ++opened,
        )
    }

    fun close(id: String) {
        slots.remove(id)
        opening.remove(id)
        replacedBy.remove(id)
        pickedFrom.remove(id)
    }
}

// ── Drawing New tabs into the tree, and taking them out ─────────────────────────────────────────────

/**
 * [tree] with every New tab drawn in. Pending panes come first, in the order they were opened, each
 * around everything drawn before it; the others go into their group at their index, and a selected
 * one is that group's active tab.
 */
internal fun withNewTabs(tree: LayoutNode, slots: Map<String, NewTabSlot>): LayoutNode {
    var t = tree
    for ((id, s) in slots.entries.filter { it.value.pane != null }.sortedBy { it.value.seq }.map { it.key to it.value }) {
        val pane = s.pane ?: continue
        val group = LayoutNode.Group(s.groupId, listOf(id), id)
        t = if (pane.edge) {
            withPaneAtEdge(t, pane.direction, group, pane.share)
        } else {
            val s = pane.share ?: 0.5
            replaceGroup(t, pane.anchorGroupId) { anchor -> LayoutNode.Split(pane.direction, listOf(1 - s, s), listOf(anchor, group)) }
        }
    }
    return withTabsInGroups(t, slots.filterValues { it.pane == null })
}

/**
 * [tree] with [pane] added at the workspace's edge, taking [share] of it (null: an equal share):
 * "row" = a new rightmost column the full height, "column" = a new bottom row the full width. A root
 * already split that way gets one more child, the others keeping their proportions.
 */
internal fun withPaneAtEdge(tree: LayoutNode, direction: String, pane: LayoutNode, share: Double? = null): LayoutNode {
    if (tree is LayoutNode.Split && tree.direction == direction) {
        val n = tree.children.size
        val s = share ?: (1.0 / (n + 1))
        val sum = tree.sizes.sum()
        val sizes = if (tree.sizes.size == n && sum > 0.0) tree.sizes.map { it / sum * (1 - s) } else List(n) { (1 - s) / n }
        return LayoutNode.Split(direction, sizes + s, tree.children + pane)
    }
    val s = share ?: 0.5
    return LayoutNode.Split(direction, listOf(1 - s, s), listOf(tree, pane))
}

/** The pane at the workspace's top-right corner: its strip carries the split buttons. */
internal fun topRightGroupId(node: LayoutNode): String = when (node) {
    is LayoutNode.Group -> node.id
    is LayoutNode.Split -> topRightGroupId(if (node.direction == "row") node.children.last() else node.children.first())
}

private fun replaceGroup(node: LayoutNode, groupId: String, with: (LayoutNode.Group) -> LayoutNode): LayoutNode = when (node) {
    is LayoutNode.Group -> if (node.id == groupId) with(node) else node
    is LayoutNode.Split -> node.copy(children = node.children.map { replaceGroup(it, groupId, with) })
}

private fun withTabsInGroups(tree: LayoutNode, slots: Map<String, NewTabSlot>): LayoutNode = when (tree) {
    is LayoutNode.Group -> {
        val mine = slots.entries.filter { it.value.groupId == tree.id }.sortedBy { it.value.index }
        if (mine.isEmpty()) {
            tree
        } else {
            val ids = tree.viewIds.toMutableList()
            for ((id, s) in mine) ids.add(s.index.coerceIn(0, ids.size), id)
            val shown = mine.lastOrNull { it.value.selected && it.value.shownOver == tree.activeViewId }?.key
            LayoutNode.Group(tree.id, ids, shown ?: tree.activeViewId)
        }
    }
    is LayoutNode.Split -> tree.copy(children = tree.children.map { withTabsInGroups(it, slots) })
}

/**
 * [shown] without its New tabs, the inverse of [withNewTabs]: the pending panes of [slots] come out
 * last-drawn first, each giving its room back to the panes beside it in proportion — or all of it to
 * the pane before it, for one in [intoPrevious] — so every pane stays the size it is drawn at. A group
 * whose New tab was on show keeps [real]'s active tab.
 */
internal fun withoutNewTabs(
    shown: LayoutNode,
    real: LayoutNode,
    slots: Map<String, NewTabSlot> = emptyMap(),
    intoPrevious: Set<String> = emptySet(),
): LayoutNode = withoutTabs(pendingPanesOut(shown, slots, intoPrevious).first, real)

private fun withoutTabs(shown: LayoutNode, real: LayoutNode): LayoutNode = when (shown) {
    is LayoutNode.Group -> {
        val ids = shown.viewIds.filterNot(::isNewTabId)
        val active = shown.activeViewId?.takeIf { it in ids }
            ?: groupActiveViewId(real, shown.id)?.takeIf { it in ids }
            ?: ids.firstOrNull()
        LayoutNode.Group(shown.id, ids, active)
    }
    is LayoutNode.Split -> shown.copy(children = shown.children.map { withoutTabs(it, real) })
}

/**
 * [shown] with the pending panes of [slots] taken out, last-drawn first (see [withoutNewTabs]), and the
 * part of its split each took, by its group: the share it is drawn at from now on.
 */
private fun pendingPanesOut(
    shown: LayoutNode,
    slots: Map<String, NewTabSlot>,
    intoPrevious: Set<String> = emptySet(),
): Pair<LayoutNode, Map<String, Double>> {
    var tree = shown
    val shares = mutableMapOf<String, Double>()
    for (slot in slots.values.filter { it.pane != null }.sortedByDescending { it.seq }) {
        val (out, share) = withoutPane(tree, slot.groupId, slot.groupId in intoPrevious)
        if (share != null) {
            tree = out
            shares[slot.groupId] = share
        }
    }
    return tree to shares
}

/**
 * [node] without pane [groupId] while it holds nothing but New tabs, and the part of its split it took
 * (null: no such pane in a split). Its room goes to the panes beside it in proportion, or all of it to
 * the one before it when [intoPrevious].
 */
private fun withoutPane(node: LayoutNode, groupId: String, intoPrevious: Boolean): Pair<LayoutNode, Double?> {
    if (node !is LayoutNode.Split) return node to null
    val i = node.children.indexOfFirst { it is LayoutNode.Group && it.id == groupId && it.viewIds.all(::isNewTabId) }
    if (i < 0) {
        for ((k, child) in node.children.withIndex()) {
            val (out, share) = withoutPane(child, groupId, intoPrevious)
            if (share != null) return node.copy(children = node.children.toMutableList().also { it[k] = out }) to share
        }
        return node to null
    }
    val n = node.children.size
    val sum = node.sizes.sum()
    val sizes = if (node.sizes.size == n && sum > 0.0) node.sizes.map { it / sum } else List(n) { 1.0 / n }
    val share = sizes[i]
    val children = node.children.filterIndexed { k, _ -> k != i }
    if (children.size == 1) return children.single() to share
    val rest = sizes.filterIndexed { k, _ -> k != i }
    val kept = when {
        intoPrevious && i > 0 -> rest.mapIndexed { k, size -> if (k == i - 1) size + share else size }
        share < 1.0 -> rest.map { it / (1 - share) }
        else -> List(rest.size) { 1.0 / rest.size }
    }
    return LayoutNode.Split(node.direction, kept, children) to share
}

/** The active tab of [groupId], or null when there is no such group. */
internal fun groupActiveViewId(node: LayoutNode, groupId: String): String? = when (node) {
    is LayoutNode.Group -> node.activeViewId.takeIf { node.id == groupId }
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { groupActiveViewId(it, groupId) }
}

private fun groupIdsOf(node: LayoutNode): Set<String> = when (node) {
    is LayoutNode.Group -> setOf(node.id)
    is LayoutNode.Split -> node.children.flatMap { groupIdsOf(it) }.toSet()
}

// ── Edits PaneHost makes on the drawn tree ──────────────────────────────────────────────────────────

/** An edit of the drawn tree, made on the real tree instead. */
internal class NewTabEdit(
    /** The real tree's edit: a function of the tree, so it replays onto a broker frame like any edit. */
    val onReal: (LayoutNode) -> LayoutNode,
    /** The New tabs it changes. */
    val slots: Map<String, NewTabSlot>,
)

/**
 * [edit], which PaneHost made on [real] with [slots] drawn in, as an edit of the real tree: the New
 * tabs are put in first and taken out after. Null when it is not made at all: a New tab dropped on a
 * pane edge would be a pane of its own.
 */
internal fun newTabEdit(real: LayoutNode, slots: Map<String, NewTabSlot>, edit: (LayoutNode) -> LayoutNode): NewTabEdit? {
    val shown = withNewTabs(real, slots)
    val edited = normalizeLayout(edit(shown))
    if (edited != null && newTabWouldMakeAPane(edited, shown)) return null
    return NewTabEdit(
        onReal = { tree -> withoutNewTabs(edit(withNewTabs(tree, slots)), tree, slots) },
        slots = edited?.let { newTabSlotsAfter(it, normalizeLayout(withoutNewTabs(it, real, slots)) ?: real, slots) } ?: slots,
    )
}

/**
 * True when [edited] puts a New tab into a group [shown] did not have — it was dropped on a pane
 * edge. A New tab cannot make a pane of its own (the broker would get an empty one), so such an edit
 * is not made at all.
 */
internal fun newTabWouldMakeAPane(edited: LayoutNode, shown: LayoutNode): Boolean {
    val known = groupIdsOf(shown)
    fun walk(n: LayoutNode): Boolean = when (n) {
        is LayoutNode.Group -> n.id !in known && n.viewIds.any(::isNewTabId)
        is LayoutNode.Split -> n.children.any(::walk)
    }
    return walk(edited)
}

/**
 * Where each New tab is after an edit of the tree they were drawn into ([edited]); [realAfter] is the
 * same edit without them. A pending pane stays pending only while it is the same pane and nothing
 * real has joined it, at the share of its split it now takes.
 */
internal fun newTabSlotsAfter(edited: LayoutNode, realAfter: LayoutNode, before: Map<String, NewTabSlot>): Map<String, NewTabSlot> {
    val out = mutableMapOf<String, NewTabSlot>()
    val real = groupIdsOf(realAfter)
    val shares = pendingPanesOut(edited, before).second
    fun walk(n: LayoutNode) {
        when (n) {
            is LayoutNode.Group -> n.viewIds.forEachIndexed { i, id ->
                val prior = before[id] ?: return@forEachIndexed
                val selected = n.activeViewId == id
                out[id] = prior.copy(
                    groupId = n.id,
                    index = i,
                    selected = selected,
                    shownOver = if (selected) groupActiveViewId(realAfter, n.id) else prior.shownOver,
                    pane = prior.pane?.takeIf { n.id == prior.groupId && n.id !in real }
                        ?.let { pane -> shares[n.id]?.let { pane.copy(share = it) } ?: pane },
                )
            }
            is LayoutNode.Split -> n.children.forEach(::walk)
        }
    }
    walk(edited)
    for ((id, s) in before) if (id !in out) out[id] = s
    return out
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
 * [real] once [viewId] — which the broker put in some group of it — takes the place New tab
 * [newTabId] is drawn in: its index in a strip, or its whole pending pane, at the size it is drawn.
 * Every other New tab and pending pane stays out of the real tree; the room of one in [intoPrevious]
 * (anchored to the pane before it from now on) goes to that pane. [from] is the group the view was
 * added to and the tab it showed before, which it shows again.
 */
internal fun placeWhereDrawn(
    real: LayoutNode,
    slots: Map<String, NewTabSlot>,
    newTabId: String,
    viewId: String,
    from: Pair<String, String?>? = null,
    intoPrevious: Set<String> = emptySet(),
): LayoutNode {
    val drawn = withNewTabs(real, slots)
    if (groupIdOf(drawn, newTabId) == null) return real
    val placed = swapIn(dropView(drawn, viewId), newTabId, viewId)
    var out = normalizeLayout(withoutNewTabs(placed, real, slots - newTabId, intoPrevious)) ?: return real
    if (from != null) {
        val (group, before) = from
        if (before != null && groupIdOf(out, viewId) != group) out = setActiveViewInGroup(out, group, before)
    }
    return out
}

/**
 * New tab [newTabId] picked, once the broker has made [viewId] for it in [tree]: the real tree's edit
 * that puts the view where the New tab is drawn, and the workspace-edge panes opened before it, which
 * change with it (see [anchorEarlierEdgePanes]). Null when the New tab is gone.
 */
internal fun newTabPicked(
    tree: LayoutNode,
    slots: Map<String, NewTabSlot>,
    newTabId: String,
    viewId: String,
    from: Pair<String, String?>?,
): NewTabEdit? {
    val picked = slots[newTabId] ?: return null
    val anchored = anchorEarlierEdgePanes(withNewTabs(tree, slots), slots, picked)
    val intoPrevious = anchored.values.map { it.groupId }.toSet()
    return NewTabEdit(
        onReal = { t -> placeWhereDrawn(t, slots, newTabId, viewId, from, intoPrevious) },
        slots = anchored,
    )
}

/**
 * Workspace-edge panes opened before [picked] are drawn around the whole workspace, so once [picked]
 * is real they would wrap it as well and jump. Each is anchored instead to the pane drawn just before
 * it in [drawn], taking the part of the two it is drawn at.
 */
internal fun anchorEarlierEdgePanes(drawn: LayoutNode, slots: Map<String, NewTabSlot>, picked: NewTabSlot): Map<String, NewTabSlot> =
    slots.filter { (_, s) -> s.pane?.edge == true && s.seq < picked.seq }
        .mapNotNull { (id, s) ->
            val (prev, direction, share) = previousOf(drawn, s.groupId) ?: return@mapNotNull null
            id to s.copy(pane = PendingPane(prev, direction, share = share))
        }
        .toMap()

/**
 * The pane drawn just before [groupId] in its split (the last pane of the child before it), that
 * split's direction, and the part of the two [groupId] takes.
 */
private fun previousOf(node: LayoutNode, groupId: String): Triple<String, String, Double>? {
    if (node !is LayoutNode.Split) return null
    val i = node.children.indexOfFirst { it is LayoutNode.Group && it.id == groupId }
    if (i > 0) {
        val mine = node.sizes.getOrNull(i) ?: 0.0
        val two = mine + (node.sizes.getOrNull(i - 1) ?: 0.0)
        return Triple(lastGroupId(node.children[i - 1]), node.direction, if (two > 0.0) mine / two else 0.5)
    }
    return node.children.firstNotNullOfOrNull { previousOf(it, groupId) }
}

private fun lastGroupId(node: LayoutNode): String = when (node) {
    is LayoutNode.Group -> node.id
    is LayoutNode.Split -> lastGroupId(node.children.last())
}

private fun dropView(node: LayoutNode, viewId: String): LayoutNode = when (node) {
    is LayoutNode.Group -> if (viewId !in node.viewIds) node else {
        val ids = node.viewIds - viewId
        LayoutNode.Group(node.id, ids, node.activeViewId?.takeIf { it != viewId } ?: ids.firstOrNull())
    }
    is LayoutNode.Split -> node.copy(children = node.children.map { dropView(it, viewId) })
}

private fun swapIn(node: LayoutNode, newTabId: String, viewId: String): LayoutNode = when (node) {
    is LayoutNode.Group -> if (newTabId !in node.viewIds) node else
        LayoutNode.Group(node.id, node.viewIds.map { if (it == newTabId) viewId else it }, viewId)
    is LayoutNode.Split -> node.copy(children = node.children.map { swapIn(it, newTabId, viewId) })
}

/**
 * The New tabs to close because what held them is gone: a tab's pane, or the pane a pending one was
 * split from. A workspace-edge pane needs no other pane, and one being replaced is the upkeep's.
 */
internal fun newTabsToClose(tree: LayoutNode, slots: Map<String, NewTabSlot>, replaced: Set<String>): Set<String> {
    val drawn = groupIdsOf(withNewTabs(tree, slots))
    return slots.filter { (id, s) ->
        id !in replaced && s.pane?.edge != true && (s.pane?.anchorGroupId ?: s.groupId) !in drawn
    }.keys
}

/**
 * Keeps the New tabs in step with the real tree: a picked one gives its place to the view the broker
 * made for it, and one whose pane is gone goes with it.
 */
@Composable
internal fun NewTabUpkeep(newTabs: NewTabsState, tree: LayoutNode, edit: ((LayoutNode) -> LayoutNode) -> Unit) {
    val replaced = newTabs.replacedBy.toMap()
    val slots = newTabs.slots.toMap()
    LaunchedEffect(tree, replaced, slots) {
        for ((newId, viewId) in replaced) {
            if (groupIdOf(tree, viewId) == null) continue
            newTabPicked(tree, slots, newId, viewId, newTabs.pickedFrom[newId])?.let { picked ->
                edit(picked.onReal)
                for ((id, s) in picked.slots) newTabs.slots[id] = s
            }
            newTabs.close(newId)
        }
        for (id in newTabsToClose(tree, slots, replaced.keys)) newTabs.close(id)
    }
}

/** A window's tear-off, except that a New tab stays in its window: it is not a view a window can hold. */
internal class NewTabsStayInWindow(private val inner: TabTearOff) : TabTearOff {
    override fun register(endpoint: TabDropEndpoint) = inner.register(endpoint)
    override fun unregister(endpoint: TabDropEndpoint) = inner.unregister(endpoint)
    override fun takeOverOnStart(handoff: TabHandoff): Boolean = !isNewTabId(handoff.viewId) && inner.takeOverOnStart(handoff)
    override fun takeOverOutside(handoff: TabHandoff): Boolean = !isNewTabId(handoff.viewId) && inner.takeOverOutside(handoff)
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
