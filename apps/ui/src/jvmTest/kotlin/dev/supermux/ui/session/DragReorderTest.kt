package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.Haptics
import dev.supermux.ui.theme.LocalHaptics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.math.abs
import kotlin.test.assertTrue

private const val ROW_PX = 60

/** Bounds for the hand-driven drag in `anOverwrittenOrderDoesNotWedgeTheRestOfTheGesture`: enough
 *  slack that a slow frame cannot fail the test, small enough that a genuinely wedged drag still
 *  reports rather than spinning. */
private const val MAX_DRAG_STEPS = 8
private const val MAX_FRAMES_PER_STEP = 8
private const val LONG_PRESS_MS = 800L

private class RecordingHaptics : Haptics {
    val kinds = mutableListOf<HapticKind>()
    override fun perform(kind: HapticKind) { kinds += kind }
}

/**
 * The one drag-reorder implementation, under both input modes.
 *
 * [InputMode.Pointer] grabs on press + touch slop (desktop's mouse-friendly reorder);
 * [InputMode.Touch] grabs only after a long press, so a vertical fling still scrolls the list
 * (Android's `sh.calvin.reorderable` semantics, reimplemented in `ui/session/DragReorder.kt`).
 */
@OptIn(ExperimentalTestApi::class)
class DragReorderTest {

    // ── ReorderableListState: the elevate-and-shuffle list reorder ────────────────────────────

    @Composable
    private fun ReorderHarness(
        mode: InputMode,
        ids: MutableList<String>,
        moves: MutableList<Pair<String, String>>,
        haptics: Haptics,
        onListState: (LazyListState) -> Unit = {},
        rejectAll: Boolean = false,
        onLifted: (String) -> Unit = {},
        afterMove: (Int) -> Unit = {},
    ) {
        CompositionLocalProvider(
            LocalInputMode provides mode,
            LocalHaptics provides haptics,
        ) {
            val listState = rememberLazyListState()
            onListState(listState)
            val state = rememberReorderableListState(listState) { from, to ->
                val f = from.key as String
                val t = to.key as String
                val fi = ids.indexOf(f)
                val ti = ids.indexOf(t)
                if (rejectAll || fi < 0 || ti < 0) return@rememberReorderableListState false
                moves += f to t
                ids.removeAt(fi)
                ids.add(ti, f)
                afterMove(moves.size)
                true
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height((ROW_PX * 3).dp)
                    .testTag("list"),
            ) {
                items(ids.toList(), key = { it }) { id ->
                    ReorderableItem(state, key = id) { isDragging ->
                        if (isDragging) onLifted(id)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(ROW_PX.dp)
                                .background(Color.DarkGray)
                                .testTag(id)
                                .reorderDragHandle(),
                        )
                    }
                }
            }
        }
    }

    @Test fun pointerPressDragMovesTheRowPastItsNeighbour() = runComposeUiTest {
        val ids = mutableStateListOf("a", "b", "c")
        val moves = mutableListOf<Pair<String, String>>()
        setContent { ReorderHarness(InputMode.Pointer, ids, moves, RecordingHaptics()) }
        waitForIdle()

        onNodeWithTag("a").performTouchInput {
            down(center)
            moveBy(Offset(0f, 25f))
            moveBy(Offset(0f, 55f))
            up()
        }
        waitForIdle()

        assertEquals(listOf("a" to "b"), moves)
        assertEquals(listOf("b", "a", "c"), ids.toList())
    }

    @Test fun pointerDragBelowTheRowHeightKeepsTheOrder() = runComposeUiTest {
        val ids = mutableStateListOf("a", "b", "c")
        val moves = mutableListOf<Pair<String, String>>()
        setContent { ReorderHarness(InputMode.Pointer, ids, moves, RecordingHaptics()) }
        waitForIdle()

        // The row's centre never leaves its own slot, so no neighbour is crossed.
        onNodeWithTag("a").performTouchInput {
            down(center)
            moveBy(Offset(0f, 12f))
            moveBy(Offset(0f, 8f))
            up()
        }
        waitForIdle()

        assertEquals(emptyList(), moves)
        assertEquals(listOf("a", "b", "c"), ids.toList())
    }

    @Test fun touchDragWithoutALongPressScrollsInsteadOfReordering() = runComposeUiTest {
        val ids = mutableStateListOf("a", "b", "c")
        val moves = mutableListOf<Pair<String, String>>()
        setContent { ReorderHarness(InputMode.Touch, ids, moves, RecordingHaptics()) }
        waitForIdle()

        onNodeWithTag("a").performTouchInput {
            down(center)
            moveBy(Offset(0f, 25f))
            moveBy(Offset(0f, 55f))
            up()
        }
        waitForIdle()

        assertEquals(emptyList(), moves)
        assertEquals(listOf("a", "b", "c"), ids.toList())
    }

    @Test fun touchLongPressLiftsWithAHapticAndThenReorders() = runComposeUiTest {
        val ids = mutableStateListOf("a", "b", "c")
        val moves = mutableListOf<Pair<String, String>>()
        val haptics = RecordingHaptics()
        setContent { ReorderHarness(InputMode.Touch, ids, moves, haptics) }
        waitForIdle()

        onNodeWithTag("a").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            moveBy(Offset(0f, 25f))
            moveBy(Offset(0f, 55f))
            up()
        }
        waitForIdle()

        assertEquals(listOf("a" to "b"), moves)
        assertEquals(listOf("b", "a", "c"), ids.toList())
        assertTrue(HapticKind.Tick in haptics.kinds, "the lift must be felt")
    }

    @Test fun aRejectedMoveLeavesTheListAloneAndKeepsTheRowUnderTheFinger() = runComposeUiTest {
        val ids = mutableStateListOf("a", "b", "c")
        val moves = mutableListOf<Pair<String, String>>()
        setContent {
            ReorderHarness(InputMode.Pointer, ids, moves, RecordingHaptics(), rejectAll = true)
        }
        waitForIdle()

        onNodeWithTag("a").performTouchInput {
            down(center)
            moveBy(Offset(0f, 25f))
            moveBy(Offset(0f, 55f))
            up()
        }
        waitForIdle()

        // Cross-section drags answer `false`: nothing shuffles.
        assertEquals(emptyList(), moves)
        assertEquals(listOf("a", "b", "c"), ids.toList())
    }

    @Test fun releasingEndsTheGestureAndClearsTheDragState() = runComposeUiTest {
        val ids = mutableStateListOf("a", "b", "c")
        val moves = mutableListOf<Pair<String, String>>()
        var listState: LazyListState? = null
        setContent {
            ReorderHarness(
                InputMode.Pointer, ids, moves, RecordingHaptics(),
                onListState = { listState = it },
            )
        }
        waitForIdle()
        onNodeWithTag("a").performTouchInput {
            down(center)
            moveBy(Offset(0f, 25f))
            moveBy(Offset(0f, 55f))
            up()
        }
        waitForIdle()
        // The gesture is over: nothing is dragging, so the sidebar's finishDrag() has fired.
        assertTrue(listState != null)
        assertEquals(listOf("b", "a", "c"), ids.toList())
    }

    @Test fun edgeAutoScrollPullsTheListWhileTheRowSitsAtTheBottom() = runComposeUiTest {
        val ids = mutableStateListOf("a", "b", "c", "d", "e", "f", "g", "h")
        val moves = mutableListOf<Pair<String, String>>()
        var listState: LazyListState? = null
        setContent {
            ReorderHarness(
                InputMode.Pointer, ids, moves, RecordingHaptics(),
                onListState = { listState = it },
            )
        }
        waitForIdle()

        onNodeWithTag("a").performTouchInput {
            down(center)
            moveBy(Offset(0f, 25f))
            moveBy(Offset(0f, 140f)) // parked inside the bottom edge zone
        }
        waitUntil(timeoutMillis = 5_000) {
            val s = listState ?: return@waitUntil false
            s.firstVisibleItemIndex > 0 || s.firstVisibleItemScrollOffset > 0
        }
        // "a" may have scrolled out of the viewport by now — release through the list node.
        onNodeWithTag("list").performTouchInput { up() }
        waitForIdle()
    }

    @Test fun aHorizontalPointerDragDoesNotLiftTheRow() = runComposeUiTest {
        val ids = mutableStateListOf("a", "b", "c")
        val moves = mutableListOf<Pair<String, String>>()
        val lifted = mutableListOf<String>()
        setContent {
            ReorderHarness(
                InputMode.Pointer, ids, moves, RecordingHaptics(),
                onLifted = { lifted += it },
            )
        }
        waitForIdle()

        // A sideways mouse drag across the row (aimed at the scrollbar, a text swipe): the reorder
        // is vertical-only, so it must not grab the row and swallow the click that follows.
        onNodeWithTag("a").performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
            up()
        }
        waitForIdle()

        assertEquals(emptyList(), lifted)
        assertEquals(emptyList(), moves)
        assertEquals(listOf("a", "b", "c"), ids.toList())

        // The same row still lifts and reorders on a vertical drag.
        onNodeWithTag("a").performTouchInput {
            down(center)
            moveBy(Offset(0f, 25f))
            moveBy(Offset(0f, 55f))
            up()
        }
        waitForIdle()
        assertEquals(listOf("a" to "b"), moves)
        assertTrue("a" in lifted)
    }

    @Test fun anOverwrittenOrderDoesNotWedgeTheRestOfTheGesture() = runComposeUiTest {
        val ids = mutableStateListOf("a", "b", "c", "d")
        val moves = mutableListOf<Pair<String, String>>()
        setContent {
            ReorderHarness(
                InputMode.Pointer, ids, moves, RecordingHaptics(),
                // A server refresh lands mid-drag and puts the optimistic order back exactly as it
                // was before the first accepted move. The move guard must NOT read that as "the
                // layout still hasn't caught up" and skip every later move of this gesture.
                afterMove = { n ->
                    if (n == 1) {
                        ids.clear()
                        ids.addAll(listOf("a", "b", "c", "d"))
                    }
                },
            )
        }
        waitForIdle()

        // Frames are driven BY HAND: the move guard holds the next move until the list has
        // re-measured, so whether one gesture's events happen to straddle a frame decided the
        // outcome under autoAdvance.
        //
        // Neither the step size nor the frame count is hard-coded any more. The step is HALF A
        // MEASURED ROW, so the test does not depend on the harness's pixel constants, and after
        // each step frames are advanced until the move guard actually accepts a move (or a bounded
        // budget runs out) — the previous fixed two-frames-per-move budget was the flake.
        val rowPx = onNodeWithTag("a").fetchSemanticsNode().size.height.toFloat()
        assertTrue(rowPx > 0f, "the row measured zero-height; the harness never laid out")
        mainClock.autoAdvance = false
        onNodeWithTag("a").performTouchInput { down(center) }
        mainClock.advanceTimeByFrame()

        var steps = 0
        while (moves.size < 2 && steps < MAX_DRAG_STEPS) {
            steps++
            val before = moves.size
            onNodeWithTag("a").performTouchInput { moveBy(Offset(0f, rowPx / 2f)) }
            var frames = 0
            while (moves.size == before && frames < MAX_FRAMES_PER_STEP) {
                mainClock.advanceTimeByFrame()
                frames++
            }
        }
        onNodeWithTag("a").performTouchInput { up() }
        mainClock.advanceTimeByFrame()
        mainClock.autoAdvance = true
        waitForIdle()

        assertTrue(
            moves.size >= 2,
            "the drag wedged after the order was overwritten: $moves " +
                "(gave it $steps half-row steps of ${rowPx / 2f}px, " +
                "$MAX_FRAMES_PER_STEP frames each)",
        )
    }

    @Test fun aDroppedRowSettlesInsteadOfSnappingHome() = runComposeUiTest {
        val ids = mutableStateListOf("a", "b", "c")
        val moves = mutableListOf<Pair<String, String>>()
        setContent { ReorderHarness(InputMode.Pointer, ids, moves, RecordingHaptics()) }
        waitForIdle()
        val home = onNodeWithTag("b").getBoundsInRoot().top

        // Less than one slot, so no move is accepted — the row is purely displaced.
        onNodeWithTag("b").performTouchInput {
            down(center)
            moveBy(Offset(0f, 12f))
            moveBy(Offset(0f, 18f))
        }
        waitForIdle()
        val held = onNodeWithTag("b").getBoundsInRoot().top
        assertTrue((held - home).value > 1f, "the row did not follow the pointer: $held vs $home")

        // Drive the clock by hand from here: the release frame must be inspectable.
        mainClock.autoAdvance = false
        onNodeWithTag("b").performTouchInput { up() }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()

        assertEquals(emptyList(), moves)
        // The drop must ANIMATE home from where the finger left it — the offset captured at
        // release, not the drag state that onDragStop has already zeroed.
        val onRelease = onNodeWithTag("b").getBoundsInRoot().top
        assertTrue(
            (onRelease - home).value > 1f,
            "the drop snapped home instead of settling: released at $onRelease, slot at $home",
        )

        mainClock.autoAdvance = true
        waitForIdle()
        val settled = onNodeWithTag("b").getBoundsInRoot().top
        assertTrue(
            abs((settled - home).value) < 1f,
            "the settle did not finish: $settled vs $home",
        )
    }

}
