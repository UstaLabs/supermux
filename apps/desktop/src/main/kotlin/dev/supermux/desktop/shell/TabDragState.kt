package dev.supermux.desktop.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.supermux.session.moveId

/**
 * Shared drag state for tab reorder / cross-strip move / edge-split.
 *
 * Hoisted to [LayoutHost] so every strip and pane can see the active drag.
 * Drop-zone highlights live on a **swapped** Compose surface (not an overlay
 * above SwingPanel) — see [GroupHost] and the Phase 5 plan.
 *
 * Preview model (hybrid browser + IDE feel):
 * - [ghost] follows the pointer once past the click-vs-drag threshold
 * - [liveOrder] reshuffles the origin strip while the pointer stays on it
 * - foreign strips show an insert caret via [hoverStripGroupId]/[hoverInsertIndex]
 */
class TabDragState {
    var draggingViewId by mutableStateOf<String?>(null)
        private set
    var originGroupId by mutableStateOf<String?>(null)
        private set
    /** Pointer position in root/window coordinates while dragging. */
    var pointerRoot by mutableStateOf(Offset.Zero)
        private set
    /** Pointer root position at press — used for same-strip offset reorder. */
    var dragStartRoot by mutableStateOf(Offset.Zero)
        private set
    /** True once movement exceeded the click-vs-drag threshold. */
    var pastThreshold by mutableStateOf(false)
        private set

    /** Floating chip that tracks the pointer (null when idle or below threshold). */
    var ghost by mutableStateOf<TabDragGhost?>(null)
        private set

    /**
     * Live visual order for [liveOrderGroupId] while dragging. Only set for the
     * origin strip while the pointer is over that strip; otherwise the strip
     * falls back to the committed [LayoutNode] order with a dimmed placeholder.
     */
    var liveOrderGroupId by mutableStateOf<String?>(null)
        private set
    var liveOrder by mutableStateOf<List<String>?>(null)
        private set

    /** Foreign strip under the pointer (for insert caret). Null when none. */
    var hoverStripGroupId by mutableStateOf<String?>(null)
        private set
    /** Rest-list insert index into the foreign strip (see [insertIndexInStrip]). */
    var hoverInsertIndex by mutableStateOf(0)
        private set

    /** Strip bounds in root coords, keyed by group id. */
    private val stripBounds = mutableStateMapOf<String, Rect>()
    /** Pane (body) bounds in root coords, keyed by group id. */
    private val paneBounds = mutableStateMapOf<String, Rect>()
    /** Tab bounds in root coords, keyed by "groupId\\0viewId". */
    private val tabBounds = mutableStateMapOf<String, Rect>()
    /** Workspace sidebar row bounds in root coords, keyed by workspace id. */
    private val workspaceBounds = mutableStateMapOf<String, Rect>()

    /** Order of the origin strip at press (frozen for step-math). */
    private var originOrder: List<String> = emptyList()
    private var fromIndex: Int = -1
    /**
     * Sticky live index with hysteresis. Truncating `delta/slot` at the boundary
     * flip-flops when the pointer hovers near a slot edge (or when local coords
     * jitter) — a dead-zone past the midpoint is required for a single swipe.
     */
    private var stickyIndex: Int = 0
    /** Slot width frozen at press so live reorder doesn't feedback on layout. */
    private var slotWidth: Float = 100f
    /** Grab point relative to the tab's top-left (keeps the chip under the finger). */
    private var grabOffset: Offset = Offset.Zero
    private var ghostLabel: String = ""

    /**
     * Committed index of the dragged tab at press (for pinning its layout slot so
     * pointer math never rides an animated translation).
     */
    val dragOriginIndex: Int get() = fromIndex

    val isDragging: Boolean get() = draggingViewId != null && pastThreshold

    /** Workspace id under the pointer while dragging, if any (for row highlight). */
    val hoverWorkspaceId: String?
        get() {
            if (!isDragging) return null
            val p = pointerRoot
            return workspaceBounds.entries.firstOrNull { it.value.contains(p) }?.key
        }

    fun registerStrip(groupId: String, bounds: Rect) {
        stripBounds[groupId] = bounds
    }

    fun registerPane(groupId: String, bounds: Rect) {
        paneBounds[groupId] = bounds
    }

    fun registerTab(groupId: String, viewId: String, bounds: Rect) {
        tabBounds[tabKey(groupId, viewId)] = bounds
    }

    fun registerWorkspace(workspaceId: String, bounds: Rect) {
        workspaceBounds[workspaceId] = bounds
    }

    fun unregisterWorkspace(workspaceId: String) {
        workspaceBounds.remove(workspaceId)
    }

    /**
     * Drop every registered bound. Called when the open workspace changes: the whole
     * previous tree left the screen at once, so its entries are all stale.
     */
    fun forgetAllBounds() {
        stripBounds.clear()
        paneBounds.clear()
        tabBounds.clear()
    }

    /** Last known root bounds for a tab, used to seed the drag origin. */
    fun tabBoundsFor(groupId: String, viewId: String): Rect? = tabBounds[tabKey(groupId, viewId)]

    /**
     * Visual order for a strip while a drag is active. Falls back to [fallback]
     * when idle or when this strip is not the live-preview target.
     */
    fun displayOrder(groupId: String, fallback: List<String>): List<String> {
        if (!isDragging) return fallback
        if (liveOrderGroupId == groupId && liveOrder != null) return liveOrder!!
        return fallback
    }

    /**
     * Forget everything registered for a group. MUST be called when a group leaves
     * composition — a workspace switch, a session switch, a split collapsing.
     *
     * Without it the bounds maps grow for the lifetime of the app, and [resolveDrop]
     * hit-tests against panes that are no longer on screen. A stale entry from a
     * workspace you visited earlier occupies the same screen rectangle as the one you
     * are looking at, so it can win the match; the resolved group id then does not
     * exist in the current tree, every tree operation no-ops, `next == tree`, and
     * onLayoutChange is never called. The drag silently does nothing.
     */
    fun unregisterGroup(groupId: String) {
        stripBounds.remove(groupId)
        paneBounds.remove(groupId)
        val prefix = "$groupId\u0000"
        tabBounds.keys.filter { it.startsWith(prefix) }.forEach { tabBounds.remove(it) }
    }

    /**
     * @param stripOrder committed tab order of the origin strip at press
     * @param label title shown on the floating ghost
     * @param bounds tab root bounds at press (seeds ghost size + grab offset)
     */
    fun begin(
        viewId: String,
        groupId: String,
        rootPos: Offset,
        stripOrder: List<String>,
        label: String,
        bounds: Rect?,
    ) {
        draggingViewId = viewId
        originGroupId = groupId
        pointerRoot = rootPos
        dragStartRoot = rootPos
        pastThreshold = false
        ghostLabel = label
        originOrder = stripOrder.ifEmpty {
            tabsInStrip(groupId).map { it.first }.ifEmpty { listOf(viewId) }
        }
        fromIndex = originOrder.indexOf(viewId).coerceAtLeast(0)
        stickyIndex = fromIndex
        slotWidth = (bounds?.width ?: tabsInStrip(groupId).map { it.second.width }.average().toFloat())
            .coerceAtLeast(100f)
        if (bounds != null) {
            grabOffset = Offset(
                (rootPos.x - bounds.left).coerceIn(0f, bounds.width.coerceAtLeast(1f)),
                (rootPos.y - bounds.top).coerceIn(0f, bounds.height.coerceAtLeast(1f)),
            )
            // Provisional size/position; [visible] stays false until past threshold.
            ghost = TabDragGhost(
                label = label,
                width = bounds.width.coerceAtLeast(56f),
                height = bounds.height.coerceAtLeast(24f),
                x = bounds.left,
                y = bounds.top,
                visible = false,
            )
        } else {
            grabOffset = Offset(40f, 12f)
            ghost = TabDragGhost(
                label = label,
                width = 88f,
                height = 28f,
                x = rootPos.x - 40f,
                y = rootPos.y - 12f,
                visible = false,
            )
        }
        liveOrderGroupId = groupId
        liveOrder = originOrder
        hoverStripGroupId = null
        hoverInsertIndex = 0
    }

    fun updatePointer(rootPos: Offset, startRoot: Offset, thresholdPx: Float) {
        pointerRoot = rootPos
        if (!pastThreshold && (rootPos - startRoot).getDistance() >= thresholdPx) {
            pastThreshold = true
        }
        if (!pastThreshold) return

        val nextX = rootPos.x - grabOffset.x
        val nextY = rootPos.y - grabOffset.y
        val g = ghost
        // Only write ghost state when it actually changes — every-frame copies
        // recomposed the whole LayoutHost and fought the pointer stream.
        if (g == null) {
            ghost = TabDragGhost(
                label = ghostLabel,
                width = 88f,
                height = 28f,
                x = nextX,
                y = nextY,
                visible = true,
            )
        } else if (
            !g.visible ||
            g.x != nextX ||
            g.y != nextY ||
            g.label != ghostLabel
        ) {
            ghost = g.copy(x = nextX, y = nextY, visible = true, label = ghostLabel)
        }
        refreshPreview()
    }

    /**
     * Group id of the pane body under (or nearest just outside) the pointer while
     * dragging. Null on a tab strip / workspace row so strip reorder never shows
     * pane previews. Content stays mounted; a Popup overlay uses this id.
     */
    val hoverPaneId: String?
        get() {
            if (!isDragging) return null
            if (hoverWorkspaceId != null) return null
            if (stripUnderPointer(pointerRoot) != null) return null
            val p = pointerRoot
            paneBounds.entries.firstOrNull { it.value.contains(p) }?.let { return it.key }
            // Slightly outside a pane (edge split release) still counts.
            return paneBounds.entries
                .minByOrNull { (_, r) -> distanceOutside(r, p) }
                ?.takeIf { (_, r) -> distanceOutside(r, p) < 48f }
                ?.key
        }

    /** @deprecated Prefer [hoverPaneId]; kept for any leftover call sites. */
    val showPaneDropTargets: Boolean get() = hoverPaneId != null

    fun cancel() {
        draggingViewId = null
        originGroupId = null
        pastThreshold = false
        pointerRoot = Offset.Zero
        dragStartRoot = Offset.Zero
        ghost = null
        liveOrder = null
        liveOrderGroupId = null
        hoverStripGroupId = null
        hoverInsertIndex = 0
        originOrder = emptyList()
        fromIndex = -1
        stickyIndex = 0
        slotWidth = 100f
        grabOffset = Offset.Zero
        ghostLabel = ""
    }

    /**
     * Resolve a drop target from the current pointer. Returns null when the
     * gesture was a click (never passed threshold) or nothing useful is under
     * the pointer.
     */
    fun resolveDrop(): TabDropTarget? {
        if (!pastThreshold) return null
        val viewId = draggingViewId ?: return null
        val origin = originGroupId ?: return null
        val p = pointerRoot

        // Sidebar workspace row: cross-workspace move (POST /views/:id/move).
        workspaceBounds.entries
            .firstOrNull { (_, r) -> r.contains(p) }
            ?.let { (wsId, _) ->
                return TabDropTarget.MoveToWorkspace(viewId, wsId)
            }

        // Prefer strip hits: reorder within a strip or move onto another strip.
        // A small vertical slop keeps a slightly-low release on the strip; deeper
        // into the pane must fall through to edge zones (bottom-edge split).
        stripUnderPointer(p)?.let { groupId ->
            return if (groupId == origin) {
                TabDropTarget.Reorder(groupId, viewId, sameStripRestIndex(p.x))
            } else {
                TabDropTarget.MoveToGroup(viewId, groupId, insertIndexInStrip(groupId, p.x))
            }
        }

        // Pane edge / centre drop zones (only meaningful while the pane is swapped).
        // Pointer past a pane's outer edge still counts as that edge — test coords
        // and real drags often leave the window before release.
        val paneHit = paneBounds.entries
            .firstOrNull { (_, r) -> r.contains(p) }
            ?: paneBounds.entries
                .filter { (id, _) -> id == origin || paneBounds.size == 1 }
                .minByOrNull { (_, r) -> distanceOutside(r, p) }
                ?.takeIf { (_, r) -> distanceOutside(r, p) < Float.POSITIVE_INFINITY }
        if (paneHit != null) {
            val (groupId, pane) = paneHit
            val zone = zoneFor(pane, p)
            return when (zone) {
                DropZone.Centre -> TabDropTarget.MoveToGroup(viewId, groupId, index = Int.MAX_VALUE)
                DropZone.Left -> TabDropTarget.Split(groupId, viewId, direction = "row", newFirst = true)
                DropZone.Right -> TabDropTarget.Split(groupId, viewId, direction = "row", newFirst = false)
                DropZone.Top -> TabDropTarget.Split(groupId, viewId, direction = "column", newFirst = true)
                DropZone.Bottom -> TabDropTarget.Split(groupId, viewId, direction = "column", newFirst = false)
            }
        }

        // Fall back: same-strip offset reorder.
        return TabDropTarget.Reorder(origin, viewId, sameStripRestIndex(p.x))
    }

    fun finish(): TabDropTarget? {
        val target = resolveDrop()
        cancel()
        return target
    }

    /** Which edge zone (if any) the pointer is over for [groupId]'s pane. */
    fun zoneOver(groupId: String): DropZone? {
        if (!isDragging) return null
        if (hoverPaneId != groupId) return null
        val pane = paneBounds[groupId] ?: return null
        return zoneFor(pane, pointerRoot)
    }

    /**
     * Rest-list insert index for same-strip reorder.
     *
     * Uses frozen slot width + press origin (not live tab bounds) so animated
     * morphs never feed the math. Advances [stickyIndex] only after the pointer
     * crosses the slot midpoint **plus** a hysteresis band — pure truncation at
     * the boundary was the "tabs loop while I swipe" bug.
     *
     * A 120px drag on a ≥100px slot still crosses 0.7 and lands one step
     * (LayoutHostDragTest).
     */
    private fun sameStripRestIndex(pointerX: Float): Int {
        if (originOrder.isEmpty()) return 0
        val last = originOrder.lastIndex.coerceAtLeast(0)
        val raw = fromIndex + (pointerX - dragStartRoot.x) / slotWidth
        // Midpoint (0.5) + hysteresis (0.2) = must reach 0.7 into the next slot.
        val band = 0.70f
        while (raw >= stickyIndex + band && stickyIndex < last) stickyIndex++
        while (raw <= stickyIndex - band && stickyIndex > 0) stickyIndex--
        return stickyIndex.coerceIn(0, last)
    }

    private fun refreshPreview() {
        val origin = originGroupId ?: return
        val p = pointerRoot

        fun setLive(order: List<String>, group: String = origin) {
            if (liveOrderGroupId != group) liveOrderGroupId = group
            if (liveOrder != order) liveOrder = order
        }

        // Workspace hover wins for caret-clear.
        if (workspaceBounds.values.any { it.contains(p) }) {
            setLive(originOrder)
            if (hoverStripGroupId != null) hoverStripGroupId = null
            return
        }

        val stripId = stripUnderPointer(p)
        if (stripId != null) {
            if (stripId == origin) {
                val at = sameStripRestIndex(p.x).coerceIn(0, originOrder.lastIndex.coerceAtLeast(0))
                setLive(moveId(originOrder, fromIndex, at))
                if (hoverStripGroupId != null) hoverStripGroupId = null
            } else {
                // Placeholder stays at the origin slot; caret marks the foreign insert.
                setLive(originOrder)
                val at = insertIndexInStrip(stripId, p.x)
                if (hoverStripGroupId != stripId) hoverStripGroupId = stripId
                if (hoverInsertIndex != at) hoverInsertIndex = at
            }
            return
        }

        // Over a pane (split / move-to-group centre): freeze origin order with dimmed slot.
        setLive(originOrder)
        if (hoverStripGroupId != null) hoverStripGroupId = null
    }

    private fun stripUnderPointer(p: Offset): String? {
        val stripSlop = 24f
        return stripBounds.entries
            .firstOrNull { (_, r) ->
                r.contains(p) || (
                    p.x >= r.left && p.x <= r.right &&
                        p.y >= r.top - stripSlop && p.y <= r.bottom + stripSlop
                    )
            }
            ?.key
    }

    /** Midpoint insertion index among tabs currently in [groupId] (foreign strip). */
    private fun insertIndexInStrip(groupId: String, pointerX: Float): Int {
        val tabs = tabsInStrip(groupId)
        if (tabs.isEmpty()) return 0
        val dragged = draggingViewId
        val rest = tabs.filter { it.first != dragged }
        var at = 0
        for ((_, r) in rest) {
            val mid = r.left + r.width / 2f
            if (pointerX >= mid) at++ else break
        }
        return at.coerceIn(0, rest.size)
    }

    private fun tabsInStrip(groupId: String): List<Pair<String, Rect>> {
        val prefix = "$groupId\u0000"
        return tabBounds.entries
            .filter { it.key.startsWith(prefix) }
            .map { (k, r) -> k.removePrefix(prefix) to r }
            .sortedBy { it.second.left }
    }

    private fun zoneFor(pane: Rect, p: Offset): DropZone {
        // Outside the pane: the side we exited is the drop edge.
        if (!pane.contains(p)) {
            val dxLeft = pane.left - p.x
            val dxRight = p.x - pane.right
            val dyTop = pane.top - p.y
            val dyBottom = p.y - pane.bottom
            val maxOut = maxOf(dxLeft, dxRight, dyTop, dyBottom)
            return when (maxOut) {
                dxLeft -> DropZone.Left
                dxRight -> DropZone.Right
                dyTop -> DropZone.Top
                else -> DropZone.Bottom
            }
        }
        val x = (p.x - pane.left) / pane.width.coerceAtLeast(1f)
        val y = (p.y - pane.top) / pane.height.coerceAtLeast(1f)
        val edge = 0.25f
        // Prefer the dominant edge when in a corner.
        val distLeft = x
        val distRight = 1f - x
        val distTop = y
        val distBottom = 1f - y
        val min = minOf(distLeft, distRight, distTop, distBottom)
        return when {
            min >= edge -> DropZone.Centre
            min == distLeft -> DropZone.Left
            min == distRight -> DropZone.Right
            min == distTop -> DropZone.Top
            else -> DropZone.Bottom
        }
    }

    private fun distanceOutside(pane: Rect, p: Offset): Float {
        if (pane.contains(p)) return 0f
        val dx = when {
            p.x < pane.left -> pane.left - p.x
            p.x > pane.right -> p.x - pane.right
            else -> 0f
        }
        val dy = when {
            p.y < pane.top -> pane.top - p.y
            p.y > pane.bottom -> p.y - pane.bottom
            else -> 0f
        }
        return dx + dy
    }

    private fun tabKey(groupId: String, viewId: String) = "$groupId\u0000$viewId"
}

/** Floating tab chip drawn above the layout while a drag is past threshold. */
data class TabDragGhost(
    val label: String,
    val width: Float,
    val height: Float,
    val x: Float,
    val y: Float,
    val visible: Boolean = true,
)

enum class DropZone { Left, Right, Top, Bottom, Centre }

sealed class TabDropTarget {
    data class Reorder(val groupId: String, val viewId: String, val index: Int) : TabDropTarget()
    data class MoveToGroup(val viewId: String, val toGroupId: String, val index: Int) : TabDropTarget()
    data class Split(
        val groupId: String,
        val viewId: String,
        val direction: String,
        val newFirst: Boolean,
    ) : TabDropTarget()
    data class MoveToWorkspace(val viewId: String, val toWorkspaceId: String) : TabDropTarget()
}

/** Click-vs-drag threshold in px. Below this, release is a plain tab select. */
const val TAB_DRAG_THRESHOLD_PX = 8f
