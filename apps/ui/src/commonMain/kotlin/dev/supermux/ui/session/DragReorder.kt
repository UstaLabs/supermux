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
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
     * a new measure releases it even when the visible order came back exactly as it was (an
     * optimistic order overwritten by a server refresh, a scroll that re-lands on the same keys) —
     * comparing key ORDER would hold forever there. And it expires unconditionally once the
     * pointer has travelled a further row height ([pointerYAtMove]), which covers the case where
     * the overwrite means no new measure happens AT ALL. Neither can wedge the rest of a gesture;
     * without them a dead row follows the finger until it lifts.
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
        pointerYAtMove = pointerY
        startEdgeScroll()
    }

    internal fun onDrag(deltaY: Float) {
        if (draggingKey == null) return
        draggingOffset += deltaY
        pointerY += deltaY
        settleMoves()
    }

    /**
     * The row that was just released, and the offset it was displaced by at that instant.
     *
     * The drop-settle animation starts from HERE rather than from [draggingOffset], which
     * [onDragStop] zeroes in the same snapshot that clears [draggingKey]: anything sampling the
     * live offset would be racing the recomposition that notices the drag ended, and a lost sample
     * means the row snaps home instead of settling. Written before the two are cleared, so the
     * recomposition that sees `draggingKey == null` already sees the drop it has to animate.
     */
    /** [pointerY] when the last move was accepted — the second half of the move guard. */
    private var pointerYAtMove = 0f

    var lastDropKey by mutableStateOf<Any?>(null)
        private set
    var lastDropOffset by mutableFloatStateOf(0f)
        private set

    internal fun onDragStop() {
        scrollJob?.cancel()
        scrollJob = null
        lastDropKey = draggingKey
        lastDropOffset = draggingOffset
        draggingKey = null
        draggingOffset = 0f
        lastMoveLayout = null
    }

    private fun settleMoves() {
        val key = draggingKey ?: return
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        val from = visible.firstOrNull { it.key == key } ?: return
        // Still the very layout the last accepted move was computed from: wait for a new measure,
        // or for the pointer to have dragged a whole further row (that measure may never come).
        if (info === lastMoveLayout && abs(pointerY - pointerYAtMove) < from.size) return
        val center = from.offset + draggingOffset + from.size / 2f
        val to = visible.firstOrNull {
            it.key != key && center >= it.offset && center <= it.offset + it.size
        } ?: return
        if (!onMove(from, to)) return
        lastMoveLayout = info
        pointerYAtMove = pointerY
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
    // Plain (non-snapshot) holder, so nothing here observes a per-frame value: drag frames stay
    // draw-only, and the only composition read of the LIVE offset is the graphicsLayer lambda
    // below. It is filled once, from the offset the state captured at release.
    val settleFrom = remember(key) { floatArrayOf(0f) }
    var settling by remember(key) { mutableStateOf(false) }
    var wasDragging by remember(key) { mutableStateOf(false) }
    if (dragging != wasDragging) {
        wasDragging = dragging
        if (!dragging) {
            val dropped = if (state.lastDropKey == key) state.lastDropOffset else 0f
            if (dropped != 0f) {
                settleFrom[0] = dropped
                settling = true
            }
        }
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
