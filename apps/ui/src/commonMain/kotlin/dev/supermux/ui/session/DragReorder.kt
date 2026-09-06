package dev.supermux.ui.session

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
     * The layout pass an accepted move was computed from. Until the list is measured again,
     * [LazyListState.layoutInfo] still describes the pre-move layout, and acting on it would run
     * a single drag past one neighbour away through the whole list.
     *
     * The guard is the layout pass ITSELF (instance identity), not the key order it produced, so
     * it cannot wedge: any new measure releases it, including one that puts the visible order back
     * exactly as it was (an optimistic order overwritten by a server refresh, a scroll that
     * re-lands on the same keys). Comparing key ORDER instead would hold the guard forever in that
     * case and leave a dead row under the finger for the rest of the gesture — it self-heals on
     * the next drag ([onDragStart] and [onDragStop] both clear it), which is far too late.
     */
    private var lastMoveLayout: Any? = null

    /**
     * Where the pointer is, in viewport coordinates. Seeded from the dragged row's centre and moved
     * by the raw gesture deltas only — never by [draggingOffset], which the list's own scrolling and
     * accepted moves also adjust. Edge auto-scroll asks THIS, so it keeps the right direction even
     * when the row itself has been carried out of `visibleItemsInfo`.
     */
    private var pointerY = 0f

    internal fun onDragStart(key: Any) {
        draggingKey = key
        draggingOffset = 0f
        lastMoveLayout = null
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        pointerY = if (item != null) item.offset + item.size / 2f else 0f
        startEdgeScroll()
    }

    internal fun onDrag(deltaY: Float) {
        if (draggingKey == null) return
        draggingOffset += deltaY
        pointerY += deltaY
        settleMoves()
    }

    internal fun onDragStop() {
        scrollJob?.cancel()
        scrollJob = null
        draggingKey = null
        draggingOffset = 0f
        lastMoveLayout = null
    }

    private fun settleMoves() {
        val key = draggingKey ?: return
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        // Still the very layout the last accepted move was computed from: wait for a new measure.
        if (info === lastMoveLayout) return
        val from = visible.firstOrNull { it.key == key } ?: return
        val center = from.offset + draggingOffset + from.size / 2f
        val to = visible.firstOrNull {
            it.key != key && center >= it.offset && center <= it.offset + it.size
        } ?: return
        if (!onMove(from, to)) return
        lastMoveLayout = info
        // The row is about to be laid out in the target's slot; keep it under the finger.
        draggingOffset -= (to.offset - from.offset).toFloat()
    }

    private fun startEdgeScroll() {
        scrollJob?.cancel()
        scrollJob = scope.launch {
            while (isActive && draggingKey != null) {
                val info = listState.layoutInfo
                // Asked of the POINTER, not of the row: a fast drag carries the row out of
                // `visibleItemsInfo` (it is only translated, its slot stays behind), and stopping
                // there would strand the list mid-scroll with the row pinned off-screen.
                val delta = when {
                    pointerY < info.viewportStartOffset + EDGE_SCROLL_ZONE_PX -> -EDGE_SCROLL_STEP_PX
                    pointerY > info.viewportEndOffset - EDGE_SCROLL_ZONE_PX -> EDGE_SCROLL_STEP_PX
                    else -> 0f
                }
                if (delta != 0f) {
                    // The list moved under a finger that did not: keep the visual offset.
                    draggingOffset += listState.scrollBy(delta)
                    settleMoves()
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
                // Vertical only — the library this replaced used `draggable(Orientation.Vertical)`.
                // A horizontal mouse drag across a row (a text swipe, an aimed-at-the-scrollbar
                // slip) must not lift it and swallow the click that follows.
                detectVerticalDragGestures(
                    onDragStart = begin,
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        state.onDrag(dy)
                    },
                    onDragEnd = { end(false) },
                    onDragCancel = { end(true) },
                )
            }
        }
    }
}

/** How long the dropped row takes to slide from under the finger into its new slot. */
private const val DROP_SETTLE_MS = 160

/**
 * One reorderable row of a `LazyColumn`. [key] must be the same key the `items` call used.
 *
 * The dragged row rides above its neighbours (translated, `zIndex` 1); every other row animates
 * into its new slot through the lazy list's own item placement animation.
 *
 * On release the row does NOT snap: its `translationY` animates from wherever the finger left it
 * down to 0 over [DROP_SETTLE_MS], still above its neighbours, and only then is placement handed
 * back to `animateItem`. Without that, a drop that moved the row by less than a full slot (or a
 * rejected cross-scope drag, which never moved the list at all) jumped visibly.
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

    // Offset the finger left behind, and whether we are still animating it away. Both are decided
    // during composition (not in an effect) so the frame the drag ends already renders the settle
    // instead of one snapped-to-zero frame.
    val settle = remember(key) { Animatable(0f) }
    // Plain (non-snapshot) holder: the drag offset is sampled here every frame, and observing it in
    // composition would recompose the whole row per frame. Drag frames stay draw-only — the only
    // composition read of the offset is inside the graphicsLayer lambda below.
    val settleFrom = remember(key) { floatArrayOf(0f) }
    var settling by remember(key) { mutableStateOf(false) }
    var wasDragging by remember(key) { mutableStateOf(false) }
    LaunchedEffect(dragging) {
        if (dragging) snapshotFlow { state.draggingOffset }.collect { settleFrom[0] = it }
    }
    if (dragging != wasDragging) {
        wasDragging = dragging
        if (!dragging && settleFrom[0] != 0f) settling = true
    }
    LaunchedEffect(settling) {
        if (settling) {
            settle.snapTo(settleFrom[0])
            settle.animateTo(0f, tween(DROP_SETTLE_MS))
            settleFrom[0] = 0f
            settling = false
        }
    }

    Box(
        modifier
            .zIndex(if (dragging || settling) 1f else 0f)
            .then(
                when {
                    dragging -> Modifier.graphicsLayer { translationY = state.draggingOffset }
                    settling -> Modifier.graphicsLayer {
                        // Before the first frame of the animation lands, hold the drop position.
                        translationY = if (settle.isRunning) settle.value else settleFrom[0]
                    }
                    else -> Modifier.animateItem()
                },
            ),
    ) {
        itemScope.content(dragging)
    }
}
