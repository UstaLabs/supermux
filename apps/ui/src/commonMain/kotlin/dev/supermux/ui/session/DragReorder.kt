package dev.supermux.ui.session

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import dev.supermux.session.moveId
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.Haptics
import dev.supermux.ui.theme.LocalHaptics
import dev.supermux.ui.theme.NoHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Whole-row drag reorder with floating ghost (web useSectionReorder parity).
 *
 * [InputMode.Pointer]: press + small move grabs immediately (mouse-friendly; no long-press).
 * [InputMode.Touch]: long-press lifts the row (with a [HapticKind.Tick]) before it follows the
 * finger, so a vertical fling still scrolls the list.
 *
 * Floating ghost follows the pointer; the list slot dims as a placeholder; live
 * insert under the finger; edge auto-scroll; commits ordered ids on release.
 *
 * Build it with [rememberSessionDragReorderState] so the input mode and haptics come from the
 * composition; the constructor stays public for tests that drive one mode explicitly.
 */
class SessionDragReorderState(
    private val scope: CoroutineScope,
    private val listState: LazyListState,
    private val inputMode: InputMode = InputMode.Pointer,
    private val haptics: Haptics = NoHaptics,
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
            .pointerInput(id, enabled, inputMode) {
                val onDragStart: (Offset) -> Unit = start@{ _ ->
                    val order = sectionIds()
                    val idx = order.indexOf(id)
                    if (idx < 0) return@start
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
                    // Touch has no press-drag: the lift is what tells the finger it grabbed.
                    if (inputMode == InputMode.Touch) haptics.perform(HapticKind.Tick)
                    startEdgeScroll()
                }
                val onDrag: (PointerInputChange, Offset) -> Unit = drag@{ change, dragAmount ->
                    change.consume()
                    if (draggingId != id) return@drag
                    dragOffsetY += dragAmount.y
                    val g = ghost
                    if (g != null) {
                        ghost = g.copy(x = g.x + dragAmount.x, y = g.y + dragAmount.y)
                    }
                    val order = liveOrder ?: return@drag
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
                }
                if (inputMode == InputMode.Touch) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = { finish(commit = true) },
                        onDragCancel = { finish(commit = false) },
                    )
                } else {
                    detectDragGestures(
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = { finish(commit = true) },
                        onDragCancel = { finish(commit = false) },
                    )
                }
            }
    }

    /** Ends the gesture as if the pointer had been cancelled (Escape, host teardown). */
    fun cancel() = finish(commit = false)

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

/**
 * [SessionDragReorderState] wired to the composition: [LocalInputMode] picks press-drag vs
 * long-press, [LocalHaptics] fires the lift tick.
 */
@Composable
fun rememberSessionDragReorderState(
    listState: LazyListState,
    onCommit: (List<String>) -> Unit,
): SessionDragReorderState {
    val scope = rememberCoroutineScope()
    val mode = LocalInputMode.current
    val haptics = LocalHaptics.current
    val commit = rememberUpdatedState(onCommit)
    return remember(listState, mode, haptics) {
        SessionDragReorderState(scope, listState, mode, haptics) { commit.value(it) }
    }
}

// ---------------------------------------------------------------------------
// Reorder-in-place: the elevate-and-shuffle list reorder both hosts' session and
// workspace lists use. This is the one implementation of what the third-party reorderable library
// used to do on each host separately (same call shape: rememberReorderableListState +
// ReorderableItem + a drag-handle modifier), with the gesture branched on LocalInputMode:
// Pointer grabs on press + slop, Touch grabs on long-press with a haptic tick.
// ---------------------------------------------------------------------------

/** How far from the viewport edge the dragged row starts pulling the list along. */
private const val EDGE_SCROLL_ZONE_PX = 96f

/** Pixels per frame the list scrolls while the dragged row sits in the edge zone. */
private const val EDGE_SCROLL_STEP_PX = 12f

/**
 * Drives one [LazyListState]'s reorder gesture.
 *
 * [onMove] is called with the dragged item and the item it is now over, and answers whether it
 * accepted the move — a cross-section (or otherwise rejected) drag returns `false` and the row
 * keeps following the finger without the list shuffling under it. The caller mutates its own
 * working order inside [onMove]; this state only tracks the gesture.
 */
class ReorderableListState internal constructor(
    private val scope: CoroutineScope,
    private val listState: LazyListState,
    private val onMove: (from: LazyListItemInfo, to: LazyListItemInfo) -> Boolean,
) {
    /** Key of the row being dragged, or null when idle. */
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    /** Pixels the dragged row is displaced from its laid-out slot. */
    var draggingOffset by mutableFloatStateOf(0f)
        private set

    val isAnyItemDragging: Boolean get() = draggingKey != null

    private var scrollJob: Job? = null

    /**
     * Visible key order at the last accepted move. The layout has not caught up with a move
     * until this changes, so we hold off on the next one — otherwise a single drag past one
     * neighbour would run away through the whole list on stale [LazyListState.layoutInfo].
     */
    private var lastMoveOrder: List<Any?>? = null

    internal fun onDragStart(key: Any) {
        draggingKey = key
        draggingOffset = 0f
        lastMoveOrder = null
        startEdgeScroll()
    }

    internal fun onDrag(deltaY: Float) {
        if (draggingKey == null) return
        draggingOffset += deltaY
        settleMoves()
    }

    internal fun onDragStop() {
        scrollJob?.cancel()
        scrollJob = null
        draggingKey = null
        draggingOffset = 0f
        lastMoveOrder = null
    }

    private fun settleMoves() {
        val key = draggingKey ?: return
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        val order = visible.map { it.key }
        if (order == lastMoveOrder) return
        val from = visible.firstOrNull { it.key == key } ?: return
        val center = from.offset + draggingOffset + from.size / 2f
        val to = visible.firstOrNull {
            it.key != key && center >= it.offset && center <= it.offset + it.size
        } ?: return
        if (!onMove(from, to)) return
        lastMoveOrder = order
        // The row is about to be laid out in the target's slot; keep it under the finger.
        draggingOffset -= (to.offset - from.offset).toFloat()
    }

    private fun startEdgeScroll() {
        scrollJob?.cancel()
        scrollJob = scope.launch {
            while (isActive && draggingKey != null) {
                val info = listState.layoutInfo
                val dragged = info.visibleItemsInfo.firstOrNull { it.key == draggingKey }
                if (dragged != null) {
                    val center = dragged.offset + draggingOffset + dragged.size / 2f
                    val delta = when {
                        center < info.viewportStartOffset + EDGE_SCROLL_ZONE_PX ->
                            -EDGE_SCROLL_STEP_PX
                        center > info.viewportEndOffset - EDGE_SCROLL_ZONE_PX ->
                            EDGE_SCROLL_STEP_PX
                        else -> 0f
                    }
                    if (delta != 0f) {
                        // The list moved under a finger that did not: keep the visual offset.
                        draggingOffset += listState.scrollBy(delta)
                        settleMoves()
                    }
                }
                delay(16)
            }
        }
    }
}

/** Remembers a [ReorderableListState] for [listState]; see [ReorderableListState.onMove]. */
@Composable
fun rememberReorderableListState(
    listState: LazyListState,
    onMove: (from: LazyListItemInfo, to: LazyListItemInfo) -> Boolean,
): ReorderableListState {
    val scope = rememberCoroutineScope()
    val move = rememberUpdatedState(onMove)
    return remember(listState) {
        ReorderableListState(scope, listState) { from, to -> move.value(from, to) }
    }
}

/** Receiver of [ReorderableItem]'s content — carries the row's key to its drag handle. */
class ReorderableItemScope internal constructor(
    internal val state: ReorderableListState,
    internal val key: Any,
) {
    /**
     * Makes the modified node the row's drag handle.
     *
     * Under [InputMode.Pointer] the grab happens on press + touch slop (mouse-friendly, no
     * waiting); under [InputMode.Touch] it happens on long-press with a [HapticKind.Tick], so
     * a vertical fling still scrolls the list. [interactionSource] is the row's own, so the
     * click indication and the drag do not fight over the press.
     */
    @Composable
    fun Modifier.reorderDragHandle(
        enabled: Boolean = true,
        interactionSource: MutableInteractionSource? = null,
        onDragStarted: (startedPosition: Offset) -> Unit = {},
        onDragStopped: () -> Unit = {},
    ): Modifier {
        val touch = LocalInputMode.current == InputMode.Touch
        val haptics = LocalHaptics.current
        val started = rememberUpdatedState(onDragStarted)
        val stopped = rememberUpdatedState(onDragStopped)
        val scope = rememberCoroutineScope()
        return this.pointerInput(key, enabled, touch) {
            if (!enabled) return@pointerInput
            var interaction: DragInteraction.Start? = null
            val begin: (Offset) -> Unit = { position ->
                if (touch) haptics.perform(HapticKind.Tick)
                interaction = DragInteraction.Start().also { i ->
                    scope.launch { interactionSource?.emit(i) }
                }
                state.onDragStart(key)
                started.value(position)
            }
            val end: (Boolean) -> Unit = { cancelled ->
                interaction?.let { i ->
                    scope.launch {
                        interactionSource?.emit(
                            if (cancelled) DragInteraction.Cancel(i) else DragInteraction.Stop(i),
                        )
                    }
                }
                interaction = null
                state.onDragStop()
                stopped.value()
            }
            val drag: (PointerInputChange, Offset) -> Unit = { change, amount ->
                change.consume()
                state.onDrag(amount.y)
            }
            if (touch) {
                detectDragGesturesAfterLongPress(
                    onDragStart = begin,
                    onDrag = drag,
                    onDragEnd = { end(false) },
                    onDragCancel = { end(true) },
                )
            } else {
                detectDragGestures(
                    onDragStart = begin,
                    onDrag = drag,
                    onDragEnd = { end(false) },
                    onDragCancel = { end(true) },
                )
            }
        }
    }
}

/**
 * One reorderable row of a `LazyColumn`. [key] must be the same key the `items` call used.
 *
 * The dragged row rides above its neighbours (translated, `zIndex` 1); every other row animates
 * into its new slot through the lazy list's own item placement animation.
 */
@Composable
fun LazyItemScope.ReorderableItem(
    state: ReorderableListState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable ReorderableItemScope.(isDragging: Boolean) -> Unit,
) {
    val dragging = state.draggingKey == key
    val itemScope = remember(state, key) { ReorderableItemScope(state, key) }
    Box(
        modifier
            .zIndex(if (dragging) 1f else 0f)
            .then(
                if (dragging) {
                    Modifier.graphicsLayer { translationY = state.draggingOffset }
                } else {
                    Modifier.animateItem()
                },
            ),
    ) {
        itemScope.content(dragging)
    }
}
