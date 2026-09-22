package dev.supermux.terminal.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.dp
import dev.supermux.terminal.OutputOrigin
import dev.supermux.terminal.TerminalColors
import dev.supermux.terminal.TerminalEffect
import dev.supermux.terminal.TerminalEngine
import dev.supermux.terminal.TerminalKey
import dev.supermux.terminal.TerminalLimits
import dev.supermux.terminal.TerminalModes
import dev.supermux.terminal.TerminalMouse
import dev.supermux.terminal.TerminalSelection
import dev.supermux.terminal.TerminalSession
import dev.supermux.terminal.TerminalSize
import dev.supermux.terminal.TerminalViewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smooth local history scrolling: the arithmetic, the controller, and the gestures that drive it.
 *
 * Four claims are asserted here, and together they are the feature:
 *
 * 1. **Fractional motion is free.** A drag inside one row changes nothing the engine knows about —
 *    `scrollTo` is called once per ROW boundary and never for the pixels in between.
 * 2. **Nothing crosses the wire.** Ordinary history gestures produce ZERO key, mouse or paste
 *    events; the program on the other end of the pty never learns that anybody scrolled.
 * 3. **No blank strip.** Whatever the offset, the span the painter covers contains the whole grid
 *    rectangle, because the offset is clamped to what the overscan rows the surface still remembers
 *    can actually cover.
 * 4. **The text the user is reading stays put** — under new output, under a reflow and under
 *    eviction — until they type, which returns them to the bottom.
 *
 * The fling is driven headlessly, through Compose's own touch injection and its own decay spec. A
 * recording of a real touch fling is deferred to Plan 2 Task 6, which is where the sample app that
 * could be recorded gets built.
 */
class ScrollControllerTest {

    // ----------------------------------------------------------------- arithmetic ----

    @Test fun fractionalScrollIsImmediate() {
        assertEquals(ScrollPosition(10, 3.0), moveViewport(ScrollPosition(10, 0.0), 3.0, 16.0, 100))
    }

    @Test fun negativeMotionCrossesOneBoundary() {
        assertEquals(ScrollPosition(9, 15.0), moveViewport(ScrollPosition(10, 1.0), -2.0, 16.0, 100))
    }

    @Test fun upperBoundaryHasNoPhantomRemainder() {
        assertEquals(ScrollPosition(100, 0.0), moveViewport(ScrollPosition(99, 10.0), 50.0, 16.0, 100))
    }

    @Test fun theOldestRetainedRowIsAHardFloor() {
        assertEquals(ScrollPosition(0, 0.0), moveViewport(ScrollPosition(1, 2.0), -500.0, 16.0, 100))
        // A delta that cannot be turned into a position is simply not one.
        val at = ScrollPosition(7, 4.0)
        assertEquals(at, moveViewport(at, Double.NaN, 16.0, 100))
        assertEquals(at, moveViewport(at, Double.POSITIVE_INFINITY, 16.0, 100))
    }

    @Test fun theRemainderIsAlwaysInsideOneCell() {
        var position = ScrollPosition(50, 0.0)
        var delta = -7.0
        repeat(400) {
            position = moveViewport(position, delta, 16.0, 100)
            assertTrue(position.remainderPx >= 0.0, "negative remainder at $position")
            assertTrue(position.remainderPx < 16.0, "the remainder leaked into the next row at $position")
            assertTrue(position.row in 0..100, "the row left the buffer at $position")
            if (position.row == 0L) delta = 11.0
            if (position.row == 100L) delta = -13.0
        }
    }

    @Test fun theEvictionClampPinsTheAnchorInsideWhatIsLeft() {
        assertEquals(ScrollPosition(40, 0.0), clampViewport(ScrollPosition(400, 7.0), 40))
        assertEquals(ScrollPosition(0, 0.0), clampViewport(ScrollPosition(-3, 7.0), 40))
        // Inside the buffer the sub-row displacement is untouched.
        assertEquals(ScrollPosition(12, 7.0), clampViewport(ScrollPosition(12, 7.0), 40))
    }

    // ----------------------------------------------------------------- no blank strip ----

    /**
     * The overscan contract, swept: for every offset the painter could be handed and every
     * combination of remembered rows, the span it paints CONTAINS the grid rectangle.
     */
    @Test fun thePaintedSpanAlwaysCoversTheGridRectangle() {
        val cell = 16.0
        val rows = 24
        for (above in listOf(false, true)) {
            for (below in listOf(false, true)) {
                // A logical position anywhere within five rows of the published frame, in
                // quarter-cell steps: this is what a fling that outruns the engine produces.
                for (step in -80..80) {
                    val absolute = step * (cell / 4)
                    val row = floor(absolute / cell).toLong()
                    val position = ScrollPosition(row, absolute - row * cell)
                    val offset = paintOffsetPx(position, 0L, cell, above, below)
                    val span = paintedSpanPx(offset, rows, cell, above, below)
                    assertTrue(span.start <= 0.0, "a blank strip at the top: offset=$offset span=$span")
                    assertTrue(
                        span.endInclusive >= rows * cell,
                        "a blank strip at the bottom: offset=$offset span=$span",
                    )
                }
            }
        }
    }

    @Test fun theOffsetIsClampedToWhatTheRememberedRowsCover() {
        val cell = 16.0
        // Nothing remembered: the grid is drawn exactly where the engine put it, not slid off an edge.
        assertEquals(0.0, paintOffsetPx(ScrollPosition(3, 9.0), 3L, cell, false, false))
        // The row below buys a whole cell of upward travel, and not one pixel more.
        assertEquals(9.0, paintOffsetPx(ScrollPosition(3, 9.0), 3L, cell, false, true))
        assertEquals(cell, paintOffsetPx(ScrollPosition(9, 0.0), 3L, cell, false, true))
        // The row above buys a whole cell downward, for a frame that still lags the anchor.
        assertEquals(-cell, paintOffsetPx(ScrollPosition(0, 0.0), 3L, cell, true, false))
        assertEquals(0.0, paintOffsetPx(ScrollPosition(0, 0.0), 3L, cell, false, false))
    }

    @Test fun theGridRectangleNeverLeavesTheCanvas() {
        assertEquals(160f, gridHeightPx(frameRows = 10, cellHeightPx = 16f, canvasPx = 300f))
        assertEquals(300f, gridHeightPx(frameRows = 30, cellHeightPx = 16f, canvasPx = 300f))
    }

    // ----------------------------------------------------------------- the controller ----

    /** A controller fed hand-built frames: no engine, no Compose, no coroutines to wait for. */
    private class Harness(private val accept: (Long) -> Boolean = { true }) {
        val scrolls: MutableList<Long> = mutableListOf()

        /** Rows the session REFUSED: asked for, never delivered. */
        val refused: MutableList<Long> = mutableListOf()

        val controller = ScrollController(CoroutineScope(Dispatchers.Unconfined)) { row ->
            if (accept(row)) {
                scrolls += row
                true
            } else {
                refused += row
                false
            }
        }
        private val model = ViewportModel()
        private var generation = 0L

        /** Publish a frame anchored at [viewportTop] with [historyRows] rows above the bottom. */
        fun publish(viewportTop: Long, historyRows: Long, rows: Int = 4): TerminalFrame {
            val update = model.apply(
                ViewportFixtures.full(
                    generation = ++generation,
                    size = TerminalSize(8, rows, 8, 16),
                    lines = List(rows) { "r${viewportTop + it}" },
                    viewportTop = viewportTop,
                    historyRows = historyRows,
                ),
            )
            val frame = (update as ViewportUpdate.Applied).frame
            controller.onFrame(frame)
            return frame
        }
    }

    private fun harness(accept: (Long) -> Boolean = { true }): Harness =
        Harness(accept).also { it.controller.onCellHeight(16f) }

    @Test fun fractionalMovementNeverReachesTheEngine() {
        val harness = harness()
        harness.publish(viewportTop = 100, historyRows = 100)
        assertTrue(harness.controller.following)

        // 15 pixels of a 16-pixel row: the position moves, the engine hears about the row once.
        assertEquals(-15f, harness.controller.consumePx(-15f))
        assertEquals(ScrollPosition(99, 1.0), harness.controller.position)
        assertEquals(listOf(99L), harness.scrolls, "crossing INTO row 99 is one request")

        repeat(15) { harness.controller.consumePx(-0.05f) }
        assertEquals(99L, harness.controller.position.row)
        assertEquals(listOf(99L), harness.scrolls, "sub-row motion must not ask the engine again")
        // And the pixels really moved: there is something for the painter to translate by.
        assertTrue(harness.controller.position.remainderPx in 0.1..0.4)
    }

    @Test fun oneRequestPerRowBoundaryAndNoneForThePixelsBetween() {
        val harness = harness()
        harness.publish(viewportTop = 100, historyRows = 100)
        repeat(160) { harness.controller.consumePx(-1f) }
        assertEquals((99L downTo 90L).toList(), harness.scrolls, "exactly one request per boundary")
        assertEquals(ScrollPosition(90, 0.0), harness.controller.position)
        assertTrue(!harness.controller.following)
    }

    @Test fun aSubRowDragHasSomethingToPaint() {
        val harness = harness()
        harness.publish(viewportTop = 100, historyRows = 100)
        harness.controller.consumePx(-16f)
        val frame = harness.publish(viewportTop = 99, historyRows = 100)

        // Row 99 + 4 = 103 is the row past the bottom of this frame, and the frame anchored at 100
        // still carries it: three pixels of drag are three pixels of motion, not a clamped no-op.
        assertNotNull(harness.controller.rowBelow(frame), "the overscan row was forgotten")
        harness.controller.consumePx(3f)
        assertEquals(3f, harness.controller.paintOffset(frame))
    }

    @Test fun outputWhileAnchoredMovesTheBottomAndNotTheAnchor() {
        val harness = harness()
        harness.publish(viewportTop = 100, historyRows = 100)
        repeat(3) { harness.controller.consumePx(-16f) }
        assertEquals(97L, harness.controller.position.row)
        harness.publish(viewportTop = 97, historyRows = 100)

        // 50 more lines of output: the bottom moves, the anchor does not.
        harness.publish(viewportTop = 97, historyRows = 150)
        assertEquals(97L, harness.controller.position.row)
        assertEquals(150L, harness.controller.newestTop)
        assertTrue(!harness.controller.following)
        assertEquals(listOf(99L, 98L, 97L), harness.scrolls, "output must not re-ask for a viewport")
    }

    @Test fun anEvictedAnchorFollowsTheEnginesPinAndThenTheOldestRow() {
        val harness = harness()
        harness.publish(viewportTop = 100, historyRows = 100)
        repeat(90) { harness.controller.consumePx(-16f) }
        assertEquals(10L, harness.controller.position.row)
        harness.publish(viewportTop = 10, historyRows = 100)

        // Ghostty evicted 4 pages' worth of rows: every surviving row's number dropped, and its own
        // pin came with them. The anchor follows the PIN, so the same text stays on screen.
        harness.publish(viewportTop = 6, historyRows = 96)
        assertEquals(6L, harness.controller.position.row)
        assertTrue(!harness.controller.following)

        // Evicted past the anchor: the pin lands on the oldest row that is left, and so does this.
        harness.publish(viewportTop = 0, historyRows = 90)
        assertEquals(0L, harness.controller.position.row)
        assertTrue(!harness.controller.following, "the oldest row is not the bottom")

        // The backstop: a bottom that shrinks below the anchor drags it down with it.
        harness.publish(viewportTop = 0, historyRows = 0)
        assertEquals(ScrollPosition(0, 0.0), harness.controller.position)
        assertTrue(harness.controller.following)
    }

    @Test fun aLaggingFrameDoesNotDragTheAnchorBackwards() {
        val harness = harness()
        harness.publish(viewportTop = 100, historyRows = 100)
        repeat(5) { harness.controller.consumePx(-16f) }
        assertEquals(95L, harness.controller.position.row)
        // A frame for a row the anchor has already left: stale, not an eviction.
        harness.publish(viewportTop = 98, historyRows = 100)
        assertEquals(95L, harness.controller.position.row)
    }

    @Test fun typingReturnsToTheBottom() {
        val harness = harness()
        harness.publish(viewportTop = 100, historyRows = 100)
        repeat(20) { harness.controller.consumePx(-16f) }
        assertTrue(!harness.controller.following)

        harness.controller.followBottom()
        assertEquals(ScrollPosition(100, 0.0), harness.controller.position)
        assertTrue(harness.controller.following)
        assertEquals(100L, harness.scrolls.last(), "the engine is told to follow the bottom again")

        // And it stays there as output arrives.
        harness.publish(viewportTop = 120, historyRows = 120)
        assertEquals(ScrollPosition(120, 0.0), harness.controller.position)
    }

    @Test fun aRefusedRequestIsRetriedUntilTheViewReconciles() {
        // The session's mailbox is saturated (a fast fling over a flooding pty): `scrollTo` returns
        // Rejected and NOTHING was actually sent.
        var accepting = false
        val harness = harness { accepting }
        harness.publish(viewportTop = 100, historyRows = 100)
        harness.controller.consumePx(-32f)
        assertEquals(98L, harness.controller.position.row)
        assertEquals(emptyList(), harness.scrolls, "a refused request must not count as sent")
        assertEquals(listOf(98L), harness.refused)

        // Frames keep arriving for the row the engine is still sitting on. The controller must not
        // believe its request was delivered — if it did, `answered` would never come back and the
        // eviction-adoption rule below would stay off forever.
        harness.publish(viewportTop = 100, historyRows = 100)
        assertEquals(98L, harness.controller.position.row, "the anchor was dragged back by a stale frame")
        assertEquals(listOf(98L), harness.refused.drop(1), "the owed row was not retried")

        // The flood ends; the next retry is accepted and the engine answers it.
        accepting = true
        harness.publish(viewportTop = 100, historyRows = 100)
        assertEquals(listOf(98L), harness.scrolls, "the owed row never reached the session")
        harness.publish(viewportTop = 98, historyRows = 100)
        assertEquals(98L, harness.controller.position.row)

        // Reconciled: the adoption rule works again, so eviction still keeps the user's text.
        harness.publish(viewportTop = 95, historyRows = 97)
        assertEquals(95L, harness.controller.position.row, "the view never recovered from the refusal")
    }

    @Test fun aRefusedRequestIsForgottenOnceTheUserIsBackAtTheBottom() {
        var accepting = false
        val harness = harness { accepting }
        harness.publish(viewportTop = 100, historyRows = 100)
        harness.controller.consumePx(-32f)
        assertTrue(harness.refused.isNotEmpty())

        // The user types: back to the bottom — and that request is refused too.
        harness.controller.followBottom()
        accepting = true
        harness.publish(viewportTop = 100, historyRows = 100)
        assertTrue(harness.controller.following)
        // The bottom follows itself, so there is nothing left to owe and nothing stale is replayed.
        assertEquals(emptyList(), harness.scrolls)
        harness.publish(viewportTop = 100, historyRows = 110)
        assertEquals(emptyList(), harness.scrolls)
        assertEquals(ScrollPosition(110, 0.0), harness.controller.position)
    }

    @Test fun aFrameThatArrivesMidGestureNeverDragsTheAnchorBackwards() = runBlocking {
        val harness = harness()
        harness.publish(viewportTop = 100, historyRows = 100)

        // A genuine gesture: inside `scroll {}` the scrollable state reports a scroll in progress,
        // which is the guard's actual precondition — driving `consumePx` directly never sets it.
        harness.controller.scrollableState.scroll {
            scrollBy(-32f)
            assertTrue(harness.controller.scrolling, "the state does not report a gesture in flight")
            assertEquals(98L, harness.controller.position.row)
            // The engine is two rows behind the finger and publishes the frame it has.
            harness.publish(viewportTop = 100, historyRows = 100)
            assertEquals(98L, harness.controller.position.row, "a lagging frame moved the anchor mid-gesture")
            scrollBy(-16f)
            assertEquals(97L, harness.controller.position.row)
            harness.publish(viewportTop = 99, historyRows = 100)
            assertEquals(97L, harness.controller.position.row, "the frame caught up and overtook the finger")
        }
        // Once the finger is gone the engine's own pin is the truth again.
        harness.publish(viewportTop = 97, historyRows = 100)
        assertEquals(97L, harness.controller.position.row)
        Unit
    }

    @Test fun aReflowKeepsTheLogicalRowAndDropsThePixels() {
        val harness = harness()
        harness.publish(viewportTop = 100, historyRows = 100)
        harness.controller.consumePx(-24f)
        assertEquals(ScrollPosition(98, 8.0), harness.controller.position)

        harness.controller.onGridChanged()
        assertEquals(ScrollPosition(98, 0.0), harness.controller.position, "the row survives a reflow")
        assertEquals(98L, harness.scrolls.last(), "and is asked for again against the new geometry")
    }

    @Test fun aCellHeightChangeDropsPixelsMeasuredAgainstTheOldOne() {
        val harness = harness()
        harness.publish(viewportTop = 100, historyRows = 100)
        harness.controller.consumePx(-20f)
        assertEquals(ScrollPosition(98, 12.0), harness.controller.position)

        harness.controller.onCellHeight(32f)
        assertEquals(ScrollPosition(98, 0.0), harness.controller.position)
        assertEquals(32.0, harness.controller.cellHeightPx)
        // A pixel now buys half of what it used to.
        harness.controller.consumePx(-16f)
        assertEquals(ScrollPosition(97, 16.0), harness.controller.position)
    }

    // ----------------------------------------------------------------- gestures, end to end ----

    /**
     * A scrollback engine: it keeps every line it is fed, answers with the `rows` lines under its
     * viewport pin, and models the two things about Ghostty that matter here — `scrollTo` clamps to
     * `0..historyRows`, and the PIN follows its row when history is evicted, so absolute row
     * numbers of surviving rows DECREASE.
     *
     * Eviction is driven through the feed, like the real thing: a line of [EVICT] drops that many
     * of the oldest rows.
     */
    private class ScrollEngine(initial: TerminalSize) : TerminalEngine {
        private val lock = Any()
        private val lines = mutableListOf<String>()
        private var firstId = 0L
        private var pinId: Long? = null
        private var generation = 1L

        @Volatile var size: TerminalSize = initial
            private set

        @Volatile var mouseTracking: Boolean = false

        val resizes: MutableList<TerminalSize> = Collections.synchronizedList(mutableListOf())
        val scrolls: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        /** Everything the program on the other end of the pty would ever see. It must stay empty. */
        val hostInput: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val historyRows: Long get() = synchronized(lock) { history() }

        fun lineAt(absolute: Long): String =
            synchronized(lock) { lines.getOrElse(absolute.toInt()) { "" } }

        private fun history(): Long = maxOf(0L, lines.size.toLong() - size.rows)

        private fun top(): Long = pinId?.let { (it - firstId).coerceIn(0L, history()) } ?: history()

        private fun evictLocked(count: Int) {
            val dropped = minOf(count, lines.size)
            repeat(dropped) { lines.removeAt(0) }
            firstId += dropped
            // The pin cannot point at a row that is gone: it lands on the oldest one that is left.
            pinId?.let { if (it < firstId) pinId = firstId }
        }

        override fun feed(bytes: ByteArray, origin: OutputOrigin) {
            synchronized(lock) {
                for (line in bytes.decodeToString().split("\n")) {
                    when {
                        line.isEmpty() -> Unit
                        line.startsWith(EVICT) -> evictLocked(line.removePrefix(EVICT).toInt())
                        else -> lines += line
                    }
                }
                generation++
            }
        }

        override fun reset() {
            synchronized(lock) {
                lines.clear()
                pinId = null
                generation++
            }
        }

        override fun resize(size: TerminalSize) {
            synchronized(lock) {
                this.size = size
                resizes += size
                generation++
            }
        }

        override fun colors(colors: TerminalColors) {
            synchronized(lock) { generation++ }
        }

        override fun viewport(forceFull: Boolean, breakHold: Boolean): TerminalViewport =
            synchronized(lock) {
                val top = top()
                ViewportFixtures.full(
                    generation = generation,
                    size = size,
                    lines = List(size.rows) { lines.getOrElse((top + it).toInt()) { "" } },
                    viewportTop = top,
                    historyRows = history(),
                    modes = TerminalModes(
                        alternateScreen = false,
                        mouseTracking = mouseTracking,
                        bracketedPaste = false,
                        alternateScroll = false,
                    ),
                )
            }

        override fun scrollTo(row: Long) {
            synchronized(lock) {
                scrolls += row
                val clamped = row.coerceIn(0L, history())
                pinId = if (clamped >= history()) null else firstId + clamped
                generation++
            }
        }

        override fun acknowledge(generation: Long) = Unit
        override fun key(key: TerminalKey) { hostInput += "key" }
        override fun mouse(mouse: TerminalMouse) { hostInput += "mouse" }
        override fun paste(text: String, allowUnsafe: Boolean): Boolean {
            hostInput += "paste"
            return true
        }
        override fun focus(focused: Boolean) = Unit
        override fun select(selection: TerminalSelection?) = Unit
        override fun selectedText() = ""
        override fun drainEffects(): List<TerminalEffect> = emptyList()
        override fun close() = Unit

        companion object {
            const val EVICT = "\u0001evict:"
        }
    }

    /** A live surface, its session and the controller it published, plus the knobs a test turns. */
    private class Fixture(
        val engine: ScrollEngine,
        private val session: TerminalSession,
        val scroll: ScrollController,
        val setWidth: (Int) -> Unit,
    ) {
        private var emitted = 0

        /** [count] lines of program output, through the real session path. */
        fun emit(count: Int, label: String = "line") {
            val text = (0 until count).joinToString("\n") { "$label-${emitted++}" }
            runBlocking { session.receive(text.encodeToByteArray()) }
        }

        /** Drop the [count] oldest history rows, the way a history budget would. */
        fun evict(count: Int) {
            runBlocking { session.receive("${ScrollEngine.EVICT}$count".encodeToByteArray()) }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun withTerminal(lines: Int, body: ComposeUiTest.(Fixture) -> Unit) = runComposeUiTest {
        val engine = ScrollEngine(TerminalSize(40, 10, 8, 16))
        val session = runBlocking {
            TerminalSession.open(
                size = engine.size,
                limits = TerminalLimits(),
                engineFactory = { _, _ -> engine },
            )
        }
        try {
            var controller: ScrollController? = null
            var width by mutableStateOf(400)
            setContent {
                Box(Modifier.size(width.dp, 320.dp)) {
                    Terminal(session, Modifier.fillMaxSize().testTag(TAG)) {
                        // The input layer of Tasks 4 and 5 reaches the controller exactly here.
                        controller = LocalTerminalScroll.current
                    }
                }
            }
            waitForIdle()
            val fixture = Fixture(
                engine = engine,
                session = session,
                scroll = assertNotNull(controller, "Terminal did not publish its scroll controller"),
                setWidth = { width = it },
            )
            fixture.emit(lines)
            waitUntil(timeoutMillis = TIMEOUT) { screenText().any { it.startsWith("line-") } }
            waitForIdle()
            body(fixture)
        } finally {
            runBlocking { session.close() }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.screenText(): List<String> =
        onNodeWithTag(TAG).fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
            .orEmpty()
            .lines()

    /** A drag that ends standing still, so the velocity tracker reports no fling. */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.dragDown(pixels: Float = 200f) {
        onNodeWithTag(TAG).performTouchInput {
            down(topCenter + Offset(0f, 8f))
            repeat(8) {
                advanceEventTime(24)
                moveBy(Offset(0f, pixels / 8f))
            }
            repeat(4) {
                advanceEventTime(80)
                moveBy(Offset.Zero)
            }
            up()
        }
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.flingDown(pixels: Float = 200f, velocity: Float = 2000f) {
        onNodeWithTag(TAG).performTouchInput {
            swipeWithVelocity(
                start = topCenter + Offset(0f, 8f),
                end = topCenter + Offset(0f, 8f + pixels),
                endVelocity = velocity,
            )
        }
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aHistoryGestureNeverReachesTheProgramAndAsksOncePerRow() = withTerminal(lines = 300) { fixture ->
        assertTrue(fixture.scroll.following)
        val bottom = screenText().first()

        dragDown(96f) // the finger goes DOWN, the viewport walks back into history

        assertEquals(emptyList(), fixture.engine.hostInput.toList(), "a history drag reached the pty")
        assertTrue(!fixture.scroll.following, "the surface is still pinned to the bottom")
        assertTrue(screenText().first() != bottom, "the visible text did not move")

        val requests = fixture.engine.scrolls.toList()
        assertTrue(requests.isNotEmpty(), "the engine was never asked for a viewport")
        assertTrue(
            requests.zipWithNext().none { (a, b) -> a == b },
            "the same row was requested twice in a row: $requests",
        )
        val rowsTravelled = 96.0 / fixture.scroll.cellHeightPx
        assertTrue(
            requests.size <= rowsTravelled + 2,
            "${requests.size} requests for $rowsTravelled rows of travel: $requests",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aReleasedFingerKeepsMoving() = withTerminal(lines = 4000) { fixture ->
        val cell = fixture.scroll.cellHeightPx
        val start = fixture.scroll.position.absolutePx(cell)

        flingDown(pixels = 200f, velocity = 2000f)

        val moved = start - fixture.scroll.position.absolutePx(cell)
        assertTrue(moved > 300.0, "no inertia: $moved pixels for a 200px throw at 2000 px/s")
        assertTrue(fixture.scroll.position.row > 0, "the fling ran into the top; widen the fixture")
        assertEquals(emptyList(), fixture.engine.hostInput.toList(), "a fling reached the pty")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aFlingStopsAtTheOldestRetainedRow() = withTerminal(lines = 60) { fixture ->
        repeat(4) { flingDown(pixels = 240f, velocity = 4000f) }
        assertEquals(ScrollPosition(0, 0.0), fixture.scroll.position, "the decay ran past the oldest row")
        assertEquals(fixture.engine.lineAt(0), screenText().first())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun outputDoesNotMoveTextTheUserIsReading() = withTerminal(lines = 300) { fixture ->
        dragDown(96f)
        val anchored = fixture.scroll.position.row
        val reading = screenText()
        assertTrue(reading.first().isNotEmpty())

        fixture.emit(500, "more")
        waitUntil(timeoutMillis = TIMEOUT) { fixture.engine.historyRows > anchored + 100 }
        waitForIdle()

        assertEquals(anchored, fixture.scroll.position.row, "new output moved the anchor")
        assertEquals(reading, screenText(), "new output moved the text under the user")
        assertTrue(!fixture.scroll.following)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun evictionRenumbersTheRowsAndTheAnchorFollowsTheText() = withTerminal(lines = 300) { fixture ->
        repeat(4) { dragDown(200f) }
        val anchored = fixture.scroll.position.row
        assertTrue(anchored > 20, "the drag did not reach far enough into history: $anchored")
        val reading = screenText()

        // Ten pages dropped: every surviving row's number falls by ten, and the engine's pin comes
        // with them. The anchor follows the pin, so the same TEXT is still on screen.
        fixture.evict(10)
        waitUntil(timeoutMillis = TIMEOUT) { fixture.scroll.position.row == anchored - 10 }
        waitForIdle()
        assertEquals(reading, screenText(), "eviction moved the text the user was reading")

        // Now take the anchored rows themselves, leaving history behind them: this is a clamp to
        // the oldest retained row, not a fall back to the bottom.
        fixture.evict((fixture.scroll.position.row + 2).toInt())
        waitUntil(timeoutMillis = TIMEOUT) { fixture.scroll.position.row == 0L }
        waitForIdle()
        assertEquals(fixture.engine.lineAt(0), screenText().first(), "not the oldest retained row")
        assertTrue(!fixture.scroll.following, "clamping to the oldest row is not following the bottom")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aReflowKeepsTheUserWhereTheyWereAndTypingBringsThemBack() = withTerminal(lines = 300) { fixture ->
        dragDown(96f)
        val anchored = fixture.scroll.position.row
        assertTrue(!fixture.scroll.following)

        fixture.setWidth(520)
        waitUntil(timeoutMillis = TIMEOUT) { fixture.engine.resizes.size > 1 }
        waitForIdle()
        assertEquals(anchored, fixture.scroll.position.row, "the logical position was lost in the reflow")
        assertEquals(0.0, fixture.scroll.position.remainderPx, "pixels from the old geometry survived")

        // What the input layer of Tasks 4/5 calls on every key and paste.
        fixture.scroll.followBottom()
        waitForIdle()
        assertTrue(fixture.scroll.following)
        assertEquals(fixture.engine.historyRows, fixture.scroll.position.row)
        assertEquals(emptyList(), fixture.engine.hostInput.toList())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun applicationMouseModeKeepsTheGestureForTheProgram() = withTerminal(lines = 300) { fixture ->
        fixture.engine.mouseTracking = true
        fixture.emit(1, "mode")
        waitUntil(timeoutMillis = TIMEOUT) { screenText().any { it.startsWith("mode-") } }
        waitForIdle()
        val before = fixture.scroll.position

        dragDown(96f)

        assertEquals(before, fixture.scroll.position, "the surface scrolled while the program owned the mouse")
    }

    private companion object {
        const val TAG = "terminal-scroll"
        const val TIMEOUT = 10_000L
    }
}
