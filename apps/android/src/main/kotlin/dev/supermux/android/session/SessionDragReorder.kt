package dev.supermux.android.session

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import dev.supermux.session.moveId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Whole-row long-press reorder (web useSectionReorder parity):
 * long-press ~ arms drag, live list reorders under the finger, edge auto-scroll,
 * commits ordered ids on release.
 */
class SessionDragReorderState(
    private val scope: CoroutineScope,
    private val listState: LazyListState,
    private val onCommit: (List<String>) -> Unit,
) {
    var draggingId by mutableStateOf<String?>(null)
        private set
    var dragOffsetY by mutableFloatStateOf(0f)
        private set
    /** Live order of the active section while dragging (null when idle). */
    var liveOrder by mutableStateOf<List<String>?>(null)
        private set

    private var startOrder: List<String> = emptyList()
    private var fromIndex: Int = -1
    private var scrollJob: Job? = null

    fun displayOrder(fallback: List<String>): List<String> = liveOrder ?: fallback

    fun rowModifier(
        id: String,
        sectionIds: () -> List<String>,
        enabled: Boolean,
    ): Modifier {
        if (!enabled) return Modifier
        return Modifier
            .zIndex(if (draggingId == id) 1f else 0f)
            .graphicsLayer {
                if (draggingId == id) {
                    translationY = dragOffsetY
                    shadowElevation = 8f
                    alpha = 0.95f
                }
            }
            .pointerInput(id, enabled) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        val order = sectionIds()
                        val idx = order.indexOf(id)
                        if (idx < 0) return@detectDragGesturesAfterLongPress
                        startOrder = order
                        fromIndex = idx
                        liveOrder = order
                        draggingId = id
                        dragOffsetY = 0f
                        startEdgeScroll()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (draggingId != id) return@detectDragGesturesAfterLongPress
                        dragOffsetY += dragAmount.y
                        val order = liveOrder ?: return@detectDragGesturesAfterLongPress
                        val layoutInfo = listState.layoutInfo
                        val draggedKey = id
                        // Approximate target: find visible item whose center is nearest to
                        // the dragged row's visual center.
                        val draggedItem = layoutInfo.visibleItemsInfo.find {
                            (it.key as? String)?.endsWith(draggedKey) == true || it.key == draggedKey
                        }
                        val fingerY = (draggedItem?.offset?.toFloat() ?: 0f) + dragOffsetY +
                            (draggedItem?.size?.toFloat() ?: 0f) / 2f
                        var target = fromIndex
                        for (info in layoutInfo.visibleItemsInfo) {
                            val key = info.key as? String ?: continue
                            val sid = order.find { key == it || key.endsWith(it) } ?: continue
                            val center = info.offset + info.size / 2f
                            val si = order.indexOf(sid)
                            if (si >= 0 && fingerY < center && si < target) target = si
                            if (si >= 0 && fingerY > center && si > target) target = si
                        }
                        // Simpler: step by half-row height relative to offset
                        val rowH = draggedItem?.size?.toFloat() ?: 72f
                        val steps = (dragOffsetY / rowH).toInt()
                        target = (fromIndex + steps).coerceIn(0, order.lastIndex)
                        if (target != order.indexOf(id)) {
                            val curIdx = order.indexOf(id)
                            if (curIdx >= 0 && target != curIdx) {
                                liveOrder = moveId(order, curIdx, target)
                                // Keep visual under finger: reset offset relative to new slot
                                dragOffsetY -= (target - curIdx) * rowH
                                fromIndex = target
                            }
                        }
                    },
                    onDragEnd = { finish(commit = true) },
                    onDragCancel = { finish(commit = false) },
                )
            }
    }

    private fun startEdgeScroll() {
        scrollJob?.cancel()
        scrollJob = scope.launch {
            while (isActive && draggingId != null) {
                val info = listState.layoutInfo
                val viewportStart = info.viewportStartOffset
                val viewportEnd = info.viewportEndOffset
                val dragged = info.visibleItemsInfo.find {
                    val k = it.key as? String
                    k == draggingId || k?.endsWith(draggingId!!) == true
                }
                if (dragged != null) {
                    val y = dragged.offset + dragOffsetY.toInt() + dragged.size / 2
                    val edge = 80
                    when {
                        y < viewportStart + edge -> listState.scrollToItem(
                            (listState.firstVisibleItemIndex - 1).coerceAtLeast(0),
                        )
                        y > viewportEnd - edge -> listState.scrollToItem(
                            listState.firstVisibleItemIndex + 1,
                        )
                    }
                }
                delay(16)
            }
        }
    }

    private fun finish(commit: Boolean) {
        scrollJob?.cancel()
        scrollJob = null
        val finalOrder = liveOrder
        val changed = finalOrder != null && finalOrder != startOrder
        draggingId = null
        dragOffsetY = 0f
        liveOrder = null
        fromIndex = -1
        if (commit && changed && finalOrder != null) onCommit(finalOrder)
        startOrder = emptyList()
    }
}
