package dev.supermux.ui.panes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.supermux.session.moveId

/**
 * Shared drag state for tab reorder / cross-strip move / edge-split.
 *
 * Hoisted to [PaneHost] so every strip and pane can see the active drag.
 * Drop-zone highlights live on a **swapped** Compose surface (not an overlay
 * above SwingPanel) — see [GroupHost] and the Phase 5 plan.
 *
 * Preview model (hybrid browser + IDE feel):
 * - [ghost] is where the held tab is drawn once past the click-vs-drag threshold: in its own
 *   strip's row, following the pointer sideways, while the pointer is on that strip; anywhere
 *   the pointer takes it otherwise
 * - [liveOrder] reshuffles the origin strip while the pointer stays on it, a neighbour giving
 *   way as soon as the held tab's leading edge crosses its middle ([liveInsertIndex])
 * - foreign strips show an insert caret via [hoverStripGroupId]/[hoverInsertIndex]
 * - [settling] is a tab just let go, eased from where it was drawn into its slot
 */
class PaneDragController {
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
    var ghost by mutableStateOf<PaneDragGhost?>(null)
        private set

    /**
     * A tab just let go (or put back by Esc): where it was drawn then. The pane layer draws it there
     * and eases it into whatever slot the drop gave it, instead of popping it in from its old one.
     * Cleared by [endSettle], or by the next [begin].
     */
    var settling by mutableStateOf<TabSettle?>(null)
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

    /**
     * Set by [abort] (Esc): the drag is over, and the rest of the press that started it does
     * nothing — not a drop, not a click. Cleared by the next [begin].
     */
    var aborted by mutableStateOf(false)
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
    /**
     * Panes whose group has no views (a canvas showing nothing), keyed by group id: the canvas,
     * and the band across its top where a strip would be.
     */
    private val emptyPanes = mutableStateMapOf<String, EmptyPane>()
    /**
     * Where each tab rests (its slot, not where a slide has it now), by view id, with the group
     * showing it: where a tab let go eases to. Read off-composition only.
     */
    private val slots = HashMap<String, Pair<String, Rect>>()

    /** Order of the origin strip at press (frozen for step-math). */
    private var originOrder: List<String> = emptyList()
    private var fromIndex: Int = -1
    /**
     * Sticky live index with hysteresis. Truncating `delta/slot` at the boundary
     * flip-flops when the pointer hovers near a slot edge (or when local coords
     * jitter) — a dead-zone past the midpoint is required for a single swipe.
     */
    private var stickyIndex: Int = 0
    /** Widths of the origin strip's tabs at press, in [originOrder]: the live reorder's geometry. */
    private var originWidths: List<Float> = emptyList()
    /** Root x where the origin strip's first tab started at press. */
    private var originContentLeft: Float = 0f
    /** Grab point relative to the tab's top-left (keeps the chip under the finger). */
    private var grabOffset: Offset = Offset.Zero
    private var ghostLabel: String = ""
    /**
     * How far the origin strip has auto-scrolled since the press. Same-strip reorder is
     * press-relative pointer math, and a scrolled strip moves the slots under a still pointer.
     */
    private var originScroll: Float = 0f

    /**
     * Committed index of the dragged tab at press (for pinning its layout slot so
     * pointer math never rides an animated translation).
     */
    val dragOriginIndex: Int get() = fromIndex

    /** Where the pointer holds the dragged tab, from its top-left, in px. */
    val grab: Offset get() = grabOffset

    /** Label of the dragged tab, as its ghost shows it. */
    val label: String get() = ghostLabel

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

    /**
     * [groupId] has no views: its whole pane is a canvas a tab can be dropped onto, and the top
     * [stripHeight] px of it stands in for the strip it does not have.
     */
    fun registerEmptyPane(groupId: String, bounds: Rect, stripHeight: Float) {
        emptyPanes[groupId] = EmptyPane(bounds, stripHeight)
    }

    /** [groupId]'s strip, in root px, while it is on screen. */
    fun stripBoundsOf(groupId: String): Rect? = stripBounds[groupId]

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
        emptyPanes.clear()
        slots.clear()
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
        emptyPanes.remove(groupId)
        slots.entries.removeAll { it.value.first == groupId }
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
        aborted = false
        settling = null
        ghostLabel = label
        originScroll = 0f
        originOrder = stripOrder.ifEmpty {
            tabsInStrip(groupId).map { it.first }.ifEmpty { listOf(viewId) }
        }
        fromIndex = originOrder.indexOf(viewId).coerceAtLeast(0)
        stickyIndex = fromIndex
        val heldWidth = bounds?.width ?: DefaultTabWidth
        originWidths = originOrder.map { id -> tabBounds[tabKey(groupId, id)]?.width ?: heldWidth }
        originContentLeft = (bounds?.left ?: rootPos.x) - originWidths.take(fromIndex).sum()
        if (bounds != null) {
            grabOffset = Offset(
                (rootPos.x - bounds.left).coerceIn(0f, bounds.width.coerceAtLeast(1f)),
                (rootPos.y - bounds.top).coerceIn(0f, bounds.height.coerceAtLeast(1f)),
            )
            // Provisional size/position; [visible] stays false until past threshold.
            ghost = PaneDragGhost(
                label = label,
                width = bounds.width.coerceAtLeast(56f),
                height = bounds.height.coerceAtLeast(24f),
                x = bounds.left,
                y = bounds.top,
                visible = false,
            )
        } else {
            grabOffset = Offset(40f, 12f)
            ghost = PaneDragGhost(
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

        val g = ghost
        val ownStrip = ownStripUnder(rootPos)
        val held = if (ownStrip != null && g != null) {
            heldInStrip(ownStrip, rootPos, g.width, g.height)
        } else {
            Offset(rootPos.x - grabOffset.x, rootPos.y - grabOffset.y)
        }
        val nextX = held.x
        val nextY = held.y
        // Only write ghost state when it actually changes — every-frame copies
        // recomposed the whole PaneHost and fought the pointer stream.
        if (g == null) {
            ghost = PaneDragGhost(
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
                ?.takeIf { (_, r) -> distanceOutside(r, p) < PaneEdgeSlop }
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
        originWidths = emptyList()
        originContentLeft = 0f
        grabOffset = Offset.Zero
        ghostLabel = ""
        originScroll = 0f
    }

    /**
     * Esc: end the drag where it is, and let the rest of its press do nothing — see [aborted].
     * Returns whether there was a drag to end.
     */
    fun abort(): Boolean {
        if (draggingViewId == null) return false
        settleFromHere()
        cancel()
        aborted = true
        return true
    }

    /**
     * A tab of another window is dragged over this one (live tear-off): drag [viewId] here as if
     * it came from a strip of none of these groups, so every strip shows where it would go and
     * every pane its drop zones. Its ghost appears where the pointer holds it — [grab] into a
     * [tabSize] chip — and is live at once.
     */
    fun beginIncoming(viewId: String, rootPos: Offset, label: String, grab: Offset, tabSize: Size) {
        begin(
            viewId = viewId,
            groupId = IncomingGroupId,
            rootPos = rootPos,
            stripOrder = listOf(viewId),
            label = label,
            bounds = Rect(rootPos - grab, tabSize),
        )
        updatePointer(rootPos, rootPos, thresholdPx = 0f)
    }

    /** Last known root bounds of [viewId]'s tab, in whichever strip shows it. */
    fun tabBoundsOf(viewId: String): Rect? {
        val suffix = "\u0000$viewId"
        return tabBounds.entries.firstOrNull { it.key.endsWith(suffix) }?.value
    }

    /** [groupId]'s strip and body together — the area its tabs are shown in — in root px. */
    fun groupAreaOf(groupId: String): Rect? {
        val pane = paneBounds[groupId]
        val strip = stripBounds[groupId]
        return when {
            pane != null && strip != null -> Rect(
                minOf(pane.left, strip.left),
                minOf(pane.top, strip.top),
                maxOf(pane.right, strip.right),
                maxOf(pane.bottom, strip.bottom),
            )
            else -> pane ?: strip
        }
    }

    /**
     * The group whose empty canvas ([registerEmptyPane]) has [p] where its strip would be — the
     * band across its top, give or take the strip slop. A tab dragged in from another window
     * joins an empty canvas there, as it joins a strip; lower down, a window can be dropped on it.
     */
    internal fun emptyGroupAt(p: Offset): String? =
        emptyPanes.entries.firstOrNull { (_, e) -> e.band.withSlop().contains(p) }?.key

    /** The band of [groupId]'s empty canvas where its strip would be, in root px. */
    internal fun emptyBandOf(groupId: String): Rect? = emptyPanes[groupId]?.band

    /** Whether [groupId] is an empty canvas rather than a group with tabs. */
    internal fun isEmptyGroup(groupId: String): Boolean = groupId in emptyPanes

    /** The origin strip auto-scrolled by [deltaPx] under the drag; re-run the live preview. */
    fun originStripScrolled(deltaPx: Float) {
        if (!isDragging) return
        originScroll += deltaPx
        refreshPreview()
    }

    /**
     * Pixels to scroll the strip whose on-screen viewport is [viewport] this frame: negative near
     * its left end, positive near its right end, faster the closer the pointer is, and 0 unless a
     * drag is live with the pointer on that strip (inside its x-range, within the strip slop).
     */
    fun stripAutoScrollStep(viewport: Rect, edgePx: Float, maxStepPx: Float): Float {
        if (!isDragging || edgePx <= 0f) return 0f
        val p = pointerRoot
        if (p.x < viewport.left || p.x > viewport.right) return 0f
        if (p.y < viewport.top - StripSlop || p.y > viewport.bottom + StripSlop) return 0f
        val fromLeft = p.x - viewport.left
        val fromRight = viewport.right - p.x
        return when {
            fromLeft < edgePx && fromLeft <= fromRight -> -maxStepPx * (1f - fromLeft / edgePx)
            fromRight < edgePx -> maxStepPx * (1f - fromRight / edgePx)
            else -> 0f
        }
    }

    /**
     * Resolve a drop target from the current pointer. Returns null when the
     * gesture was a click (never passed threshold) or nothing useful is under
     * the pointer.
     */
    fun resolveDrop(): PaneDropTarget? {
        if (!pastThreshold) return null
        val viewId = draggingViewId ?: return null
        val origin = originGroupId ?: return null
        val p = pointerRoot

        // Sidebar workspace row: cross-workspace move (POST /views/:id/move).
        workspaceBounds.entries
            .firstOrNull { (_, r) -> r.contains(p) }
            ?.let { (wsId, _) ->
                return PaneDropTarget.MoveToWorkspace(viewId, wsId)
            }

        // Prefer strip hits: reorder within a strip or move onto another strip.
        // A small vertical slop keeps a slightly-low release on the strip; deeper
        // into the pane must fall through to edge zones (bottom-edge split).
        stripUnderPointer(p)?.let { groupId ->
            return if (groupId == origin) {
                PaneDropTarget.Reorder(groupId, viewId, sameStripIndex())
            } else {
                PaneDropTarget.MoveToGroup(viewId, groupId, insertIndexInStrip(groupId, p.x))
            }
        }

        // Pane edge / centre drop zones (only meaningful while the pane is swapped).
        // A release just past a pane's outer edge still counts as that edge. Only JUST
        // past it: this used to accept any distance, so every release found the origin
        // pane, nothing was ever a miss, and a tab dragged off the window never tore out.
        val paneHit = paneBounds.entries
            .firstOrNull { (_, r) -> r.contains(p) }
            ?: paneBounds.entries
                .filter { (id, _) -> id == origin || paneBounds.size == 1 }
                .minByOrNull { (_, r) -> distanceOutside(r, p) }
                ?.takeIf { (_, r) -> distanceOutside(r, p) < PaneEdgeSlop }
        if (paneHit != null) {
            val (groupId, pane) = paneHit
            val zone = zoneFor(pane, p)
            return when (zone) {
                DropZone.Centre -> PaneDropTarget.MoveToGroup(viewId, groupId, index = Int.MAX_VALUE)
                DropZone.Left -> PaneDropTarget.Split(groupId, viewId, direction = "row", newFirst = true)
                DropZone.Right -> PaneDropTarget.Split(groupId, viewId, direction = "row", newFirst = false)
                DropZone.Top -> PaneDropTarget.Split(groupId, viewId, direction = "column", newFirst = true)
                DropZone.Bottom -> PaneDropTarget.Split(groupId, viewId, direction = "column", newFirst = false)
            }
        }

        // Off every target: a miss. Desktop tears the tab into a new window (onDragEndMiss).
        return null
    }

    fun finish(): PaneDropTarget? {
        val target = resolveDrop()
        settleFromHere()
        cancel()
        return target
    }

    /** The held tab, let go where it is drawn now: it settles from there ([settling]). */
    private fun settleFromHere() {
        val viewId = draggingViewId ?: return
        val g = ghost?.takeIf { pastThreshold && it.visible } ?: return
        settling = TabSettle(viewId, g.label, Rect(g.x, g.y, g.x + g.width, g.y + g.height))
    }

    /** [settle] has landed (or has nowhere to land here): stop drawing it. */
    fun endSettle(settle: TabSettle) {
        if (settling == settle) settling = null
    }

    /** [viewId]'s tab rests at [bounds] (root px) in [groupId]'s strip. */
    fun registerSlot(groupId: String, viewId: String, bounds: Rect) {
        slots[viewId] = groupId to bounds
    }

    /** [viewId]'s tab left [groupId]'s strip. Ignored when another strip shows it now. */
    fun unregisterSlot(groupId: String, viewId: String) {
        if (slots[viewId]?.first == groupId) slots.remove(viewId)
    }

    /** Where [viewId]'s tab rests now, in whichever strip shows it; null when none does. */
    internal fun slotOf(viewId: String): Rect? = slots[viewId]?.second

    /** Which edge zone (if any) the pointer is over for [groupId]'s pane. */
    fun zoneOver(groupId: String): DropZone? {
        if (!isDragging) return null
        if (hoverPaneId != groupId) return null
        val pane = paneBounds[groupId] ?: return null
        return zoneFor(pane, pointerRoot)
    }

    /**
     * The held tab's index in its own strip: past each neighbour whose middle its leading edge has
     * crossed ([liveInsertIndex]). By the tabs' widths at press and where the held tab is drawn
     * ([ghost]), so the order changes exactly when it visibly passes a tab — never by live tab
     * bounds, which slide under it — with the strip's auto-scroll since press counted in.
     */
    private fun sameStripIndex(): Int {
        val width = originWidths.getOrNull(fromIndex) ?: return stickyIndex
        val rest = originWidths.filterIndexed { i, _ -> i != fromIndex }
        val left = (ghost?.x ?: (pointerRoot.x - grabOffset.x)) - (originContentLeft - originScroll)
        stickyIndex = liveInsertIndex(rest, width, stickyIndex, left)
        return stickyIndex
    }

    /** The origin strip, while the pointer is on it (within its slop); null anywhere else. */
    private fun ownStripUnder(p: Offset): Rect? {
        val origin = originGroupId ?: return null
        return stripBounds[origin]?.takeIf { stripUnderPointer(p) == origin }
    }

    /**
     * Where the held tab ([width] × [height]) is drawn while the pointer is on its own [strip]: in
     * the strip's row, following the pointer sideways, between where its tabs start and the strip's
     * far end.
     */
    private fun heldInStrip(strip: Rect, p: Offset, width: Float, height: Float): Offset {
        val minLeft = maxOf(strip.left, originContentLeft - originScroll)
        val maxLeft = (strip.right - width).coerceAtLeast(minLeft)
        return Offset((p.x - grabOffset.x).coerceIn(minLeft, maxLeft), strip.top + (strip.height - height) / 2f)
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
                val at = sameStripIndex().coerceIn(0, originOrder.lastIndex.coerceAtLeast(0))
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

    internal fun stripUnderPointer(p: Offset): String? {
        return stripBounds.entries
            .firstOrNull { (_, r) ->
                r.contains(p) || (
                    p.x >= r.left && p.x <= r.right &&
                        p.y >= r.top - StripSlop && p.y <= r.bottom + StripSlop
                    )
            }
            ?.key
    }

    /** Midpoint insertion index among tabs currently in [groupId] (foreign strip). */
    internal fun insertIndexInStrip(groupId: String, pointerX: Float): Int {
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

private class EmptyPane(val bounds: Rect, stripHeight: Float) {
    val band = Rect(bounds.left, bounds.top, bounds.right, minOf(bounds.bottom, bounds.top + stripHeight))
}

/** A strip's rect with the vertical slop a pointer gets on it. */
private fun Rect.withSlop() = Rect(left, top - StripSlop, right, bottom + StripSlop)

/** Origin group of a [PaneDragController.beginIncoming] drag: none of this window's groups. */
internal const val IncomingGroupId = "\u0000incoming"

/** Floating tab chip drawn above the layout while a drag is past threshold. */
data class PaneDragGhost(
    val label: String,
    val width: Float,
    val height: Float,
    val x: Float,
    val y: Float,
    val visible: Boolean = true,
)

enum class DropZone { Left, Right, Top, Bottom, Centre }

sealed class PaneDropTarget {
    data class Reorder(val groupId: String, val viewId: String, val index: Int) : PaneDropTarget()
    data class MoveToGroup(val viewId: String, val toGroupId: String, val index: Int) : PaneDropTarget()
    data class Split(
        val groupId: String,
        val viewId: String,
        val direction: String,
        val newFirst: Boolean,
    ) : PaneDropTarget()
    data class MoveToWorkspace(val viewId: String, val toWorkspaceId: String) : PaneDropTarget()
}

/** Vertical slop (px) that keeps a slightly-off pointer on a tab strip. */
private const val StripSlop = 24f

/** How far (px) past a pane's edge a release still counts as that pane's edge zone. */
private const val PaneEdgeSlop = 48f

/** Click-vs-drag threshold in px. Below this, release is a plain tab select. */
const val PANE_DRAG_THRESHOLD_PX = 8f

/** A tab let go: [viewId], labelled [label], drawn at [from] (root px) when it was. */
data class TabSettle(val viewId: String, val label: String, val from: Rect)

/** A tab's width when nothing has measured it yet. */
private const val DefaultTabWidth = 100f
