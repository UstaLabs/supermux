package dev.supermux.desktop.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Shared drag state for tab reorder / cross-strip move / edge-split.
 *
 * Hoisted to [LayoutHost] so every strip and pane can see the active drag.
 * Drop-zone highlights live on a **swapped** Compose surface (not an overlay
 * above SwingPanel) — see [GroupHost] and the Phase 5 plan.
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

    /** Strip bounds in root coords, keyed by group id. */
    private val stripBounds = mutableStateMapOf<String, Rect>()
    /** Pane (body) bounds in root coords, keyed by group id. */
    private val paneBounds = mutableStateMapOf<String, Rect>()
    /** Tab bounds in root coords, keyed by "groupId\\0viewId". */
    private val tabBounds = mutableStateMapOf<String, Rect>()

    val isDragging: Boolean get() = draggingViewId != null && pastThreshold

    fun registerStrip(groupId: String, bounds: Rect) {
        stripBounds[groupId] = bounds
    }

    fun registerPane(groupId: String, bounds: Rect) {
        paneBounds[groupId] = bounds
    }

    fun registerTab(groupId: String, viewId: String, bounds: Rect) {
        tabBounds[tabKey(groupId, viewId)] = bounds
    }

    /** Last known root bounds for a tab, used to seed the drag origin. */
    fun tabBoundsFor(groupId: String, viewId: String): Rect? = tabBounds[tabKey(groupId, viewId)]

    fun unregisterGroup(groupId: String) {
        stripBounds.remove(groupId)
        paneBounds.remove(groupId)
        val prefix = "$groupId\u0000"
        tabBounds.keys.filter { it.startsWith(prefix) }.forEach { tabBounds.remove(it) }
    }

    fun begin(viewId: String, groupId: String, rootPos: Offset) {
        draggingViewId = viewId
        originGroupId = groupId
        pointerRoot = rootPos
        dragStartRoot = rootPos
        pastThreshold = false
    }

    fun updatePointer(rootPos: Offset, startRoot: Offset, thresholdPx: Float) {
        pointerRoot = rootPos
        if (!pastThreshold && (rootPos - startRoot).getDistance() >= thresholdPx) {
            pastThreshold = true
        }
    }

    fun cancel() {
        draggingViewId = null
        originGroupId = null
        pastThreshold = false
        pointerRoot = Offset.Zero
        dragStartRoot = Offset.Zero
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

        // Prefer strip hits: reorder within a strip or move onto another strip.
        // Origin-strip reorder also matches when the pointer is only slightly
        // off the strip (SessionDragReorder-style vertical tolerance).
        stripBounds.entries
            .firstOrNull { (groupId, r) ->
                r.contains(p) || (groupId == origin && p.x >= r.left && p.x <= r.right)
            }
            ?.let { (groupId, _) ->
                return if (groupId == origin) {
                    TabDropTarget.Reorder(groupId, viewId, reorderIndexByOffset(groupId, viewId, p.x))
                } else {
                    TabDropTarget.MoveToGroup(viewId, groupId, insertIndexInStrip(groupId, p.x))
                }
            }

        // Pane edge / centre drop zones (only meaningful while the pane is swapped).
        paneBounds.entries
            .firstOrNull { (_, r) -> r.contains(p) }
            ?.let { (groupId, pane) ->
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
        return TabDropTarget.Reorder(origin, viewId, reorderIndexByOffset(origin, viewId, p.x))
    }

    fun finish(): TabDropTarget? {
        val target = resolveDrop()
        cancel()
        return target
    }

    /** Which edge zone (if any) the pointer is over for [groupId]'s pane. */
    fun zoneOver(groupId: String): DropZone? {
        if (!isDragging) return null
        val pane = paneBounds[groupId] ?: return null
        if (!pane.contains(pointerRoot)) return null
        return zoneFor(pane, pointerRoot)
    }

    /**
     * Same-strip reorder index for [reorderWithinGroup], using drag offset over
     * a slot width (SessionDragReorder's steps approach). Truncating division
     * so a deliberate horizontal drag lands on the neighbour.
     *
     * Slot is at least 100px: short tab labels (single-letter test ids) are far
     * narrower than a real title, and a 120px drag should mean "one step" as the
     * Phase 5 tests assert — not two.
     */
    private fun reorderIndexByOffset(groupId: String, viewId: String, pointerX: Float): Int {
        val tabs = tabsInStrip(groupId)
        if (tabs.isEmpty()) return 0
        val fromIndex = tabs.indexOfFirst { it.first == viewId }.coerceAtLeast(0)
        val avgW = tabs.map { it.second.width }.average().toFloat().coerceAtLeast(1f)
        val slot = avgW.coerceAtLeast(100f)
        val steps = ((pointerX - dragStartRoot.x) / slot).toInt()
        val targetFinal = (fromIndex + steps).coerceIn(0, tabs.lastIndex)
        // rest-list insert index == final index of the moved item.
        return targetFinal.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
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

    private fun tabKey(groupId: String, viewId: String) = "$groupId\u0000$viewId"
}

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
}

/** Click-vs-drag threshold in px. Below this, release is a plain tab select. */
const val TAB_DRAG_THRESHOLD_PX = 8f
