package dev.supermux.desktop.session

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import dev.supermux.session.moveId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Whole-row drag reorder with floating ghost (web useSectionReorder parity).
 *
 * Desktop: press + small move grabs immediately (mouse-friendly; no long-press).
 * Floating ghost follows the pointer; the list slot dims as a placeholder; live
 * insert under the finger; edge auto-scroll; commits ordered ids on release.
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

    /** Floating ghost card (viewport / root coords). Null when idle. */
    var ghost by mutableStateOf<ReorderGhost?>(null)
        private set

    private var startOrder: List<String> = emptyList()
    private var fromIndex: Int = -1
    private var scrollJob: Job? = null
    private var rowRootX = 0f
    private var rowRootY = 0f
    private var rowSize = IntSize.Zero

    data class ReorderGhost(
        val label: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    )

    fun displayOrder(fallback: List<String>): List<String> = liveOrder ?: fallback

    fun rowModifier(
        id: String,
        sectionIds: () -> List<String>,
        enabled: Boolean,
        label: String = id,
    ): Modifier {
        if (!enabled) return Modifier
        return Modifier
            .zIndex(if (draggingId == id) 1f else 0f)
            .onGloballyPositioned { coords ->
                if (draggingId == null || draggingId == id) {
                    rowRootX = coords.positionInRoot().x
                    rowRootY = coords.positionInRoot().y
                    rowSize = coords.size
                }
            }
            .graphicsLayer {
                if (draggingId == id) {
                    // Placeholder slot: dimmed original stays in the list.
                    alpha = 0.3f
                    scaleX = 0.98f
                    scaleY = 0.98f
                }
            }
            .pointerInput(id, enabled) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val order = sectionIds()
                        val idx = order.indexOf(id)
                        if (idx < 0) return@detectDragGestures
                        startOrder = order
                        fromIndex = idx
                        liveOrder = order
                        draggingId = id
                        dragOffsetY = 0f
                        ghost = ReorderGhost(
                            label = label,
                            x = rowRootX,
                            y = rowRootY,
                            width = rowSize.width.toFloat().coerceAtLeast(200f),
                            height = rowSize.height.toFloat().coerceAtLeast(48f),
                        )
                        startEdgeScroll()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (draggingId != id) return@detectDragGestures
                        dragOffsetY += dragAmount.y
                        val g = ghost
                        if (g != null) {
                            ghost = g.copy(
                                x = g.x + dragAmount.x,
                                y = g.y + dragAmount.y,
                            )
                        }
                        val order = liveOrder ?: return@detectDragGestures
                        val layoutInfo = listState.layoutInfo
                        val draggedItem = layoutInfo.visibleItemsInfo.find {
                            (it.key as? String)?.endsWith(id) == true || it.key == id
                        }
                        val rowH = draggedItem?.size?.toFloat() ?: g?.height ?: 72f
                        val steps = (dragOffsetY / rowH).toInt()
                        val target = (fromIndex + steps).coerceIn(0, order.lastIndex)
                        val curIdx = order.indexOf(id)
                        if (curIdx >= 0 && target != curIdx) {
                            liveOrder = moveId(order, curIdx, target)
                            dragOffsetY -= (target - curIdx) * rowH
                            fromIndex = target
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
        // Commit before clearing liveOrder so the first recomposition already sees
        // the optimistic sortOrder (avoids a one-frame snap-back).
        if (commit && changed && finalOrder != null) onCommit(finalOrder)
        draggingId = null
        dragOffsetY = 0f
        liveOrder = null
        ghost = null
        fromIndex = -1
        startOrder = emptyList()
    }
}
