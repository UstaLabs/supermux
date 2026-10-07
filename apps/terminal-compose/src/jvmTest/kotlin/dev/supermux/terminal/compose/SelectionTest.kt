package dev.supermux.terminal.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeDown
import dev.supermux.terminal.TerminalLimits
import dev.supermux.terminal.TerminalPoint
import dev.supermux.terminal.TerminalSelection
import dev.supermux.terminal.TerminalSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Selecting text: what gets selected, what gets copied, and what the program hears (nothing).
 *
 * The selection is not a UI decoration — it is state in the ENGINE, in absolute row coordinates, and
 * the text it yields comes from the engine too. So these tests assert on three things and no
 * internals: the selection the session publishes, the string `selectedText()` returns, and the bytes
 * the recorder saw (which must stay empty for every local gesture).
 *
 * The pure geometry — wide-cell snapping, word spans, handle placement, eviction drift — is asserted
 * against hand-built frames, where a font cannot influence the answer.
 */
class SelectionTest {

    private val size = TerminalSize(20, 4, 8, 16)
    private val metrics = CellMetrics(width = 8f, height = 16f, baseline = 12f, lineHeight = 14f)

    private fun frameWith(
        lines: List<String>,
        selection: TerminalSelection? = null,
        viewportTop: Long = 0,
        historyRows: Long = 0,
    ): TerminalFrame {
        val model = ViewportModel()
        model.apply(
            ViewportFixtures.full(
                generation = 1,
                size = size,
                lines = lines,
                selection = selection,
                viewportTop = viewportTop,
                historyRows = historyRows,
            ),
        )
        return assertNotNull(model.frame)
    }

    // ----------------------------------------------------------------- geometry ----

    @Test fun aWideGlyphIsNeverCutInHalf() {
        // "a" then a wide 世 (two cells: a width-2 leading cell and a width-0 continuation).
        val row = ViewportFixtures.row(0, "", size.columns).let { blank ->
            val cells = blank.cells.toMutableList()
            cells[0] = cells[0].copy(text = "a", width = 1)
            cells[1] = cells[1].copy(text = "世", width = 2)
            cells[2] = cells[2].copy(text = "", width = 0)
            cells[3] = cells[3].copy(text = "b", width = 1)
            dev.supermux.terminal.TerminalRow(0, cells)
        }
        val model = ViewportModel()
        model.apply(ViewportFixtures.viewport(1, size, listOf(row) + (1 until size.rows).map {
            ViewportFixtures.row(it, "", size.columns)
        }, full = true))
        val frame = assertNotNull(model.frame)

        // A press on the RIGHT half of 世 starts at the glyph's leading cell...
        assertEquals(1, snapColumn(frame, 0, column = 2, toEnd = false))
        // ...and an end that lands on the LEADING cell still covers the continuation.
        assertEquals(2, snapColumn(frame, 0, column = 1, toEnd = true))
        // A narrow cell is left alone in both directions.
        assertEquals(3, snapColumn(frame, 0, column = 3, toEnd = false))
        assertEquals(3, snapColumn(frame, 0, column = 3, toEnd = true))
        // The continuation cell belongs to the word its leading cell is in.
        assertEquals(0..3, wordSpanAt(frame, 0, 2))
    }

    @Test fun aWordIsTheRunOfNonBlanksUnderTheFinger() {
        val frame = frameWith(listOf("cd /usr/local/bin  x"))
        assertEquals(0..1, wordSpanAt(frame, 0, 0))
        assertEquals(3..16, wordSpanAt(frame, 0, 8), "a path is one word: it is what the user meant")
        assertNull(wordSpanAt(frame, 0, 2), "a blank is not a word")
        assertNull(wordSpanAt(frame, 0, 18))
    }

    @Test fun handlesSitAtTheEndsOfTheSelectionAndRideTheScrollOffset() {
        val frame = frameWith(
            lines = listOf("one", "two", "three", "four"),
            selection = TerminalSelection(TerminalPoint(1, 2), TerminalPoint(2, 4)),
        )
        val spots = selectionHandles(frame, metrics, scrollOffsetPx = 0f)
        assertEquals(listOf(SelectionHandle.START, SelectionHandle.END), spots.map { it.handle })
        // Start: the top-left corner of its cell. End: past the last cell's right edge and below it.
        assertEquals(Offset(2 * 8f, 1 * 16f), spots[0].position)
        assertEquals(Offset(5 * 8f, 3 * 16f), spots[1].position)

        // Mid-scroll the grid is drawn 6px up, and so are the handles.
        val shifted = selectionHandles(frame, metrics, scrollOffsetPx = 6f)
        assertEquals(Offset(2 * 8f, 1 * 16f - 6f), shifted[0].position)

        // The hit test is a radius around the spot, nearest first.
        assertEquals(SelectionHandle.START, handleAt(Offset(18f, 18f), spots, radiusPx = 12f))
        assertEquals(SelectionHandle.END, handleAt(Offset(42f, 50f), spots, radiusPx = 12f))
        assertNull(handleAt(Offset(200f, 200f), spots, radiusPx = 12f))
    }

    @Test fun aSelectionEntirelyInHistoryHasNoHandlesOnScreen() {
        val frame = frameWith(
            lines = listOf("a", "b", "c", "d"),
            selection = TerminalSelection(TerminalPoint(1, 0), TerminalPoint(2, 3)),
            viewportTop = 50,
            historyRows = 50,
        )
        assertTrue(selectionHandles(frame, metrics, 0f).isEmpty())
    }

    @Test fun evictionDriftIsRecognisedOnlyWhenBothEndsMovedTogether() {
        val sent = TerminalSelection(TerminalPoint(100, 2), TerminalPoint(104, 7))
        // Ghostty dropped a page: every retained row lost 40 from its number.
        assertEquals(-40L, driftBetween(sent, TerminalSelection(TerminalPoint(60, 2), TerminalPoint(64, 7))))
        // The same selection handed back the other way round is still the same selection: its
        // (row, column) PAIRS are the ones that were sent, each shifted by the same amount.
        assertEquals(-40L, driftBetween(sent, TerminalSelection(TerminalPoint(64, 7), TerminalPoint(60, 2))))
        // Nothing moved.
        assertEquals(0L, driftBetween(sent, sent))
        // One end moved: that is the user dragging, not eviction, and must not be mistaken for it.
        assertNull(driftBetween(sent, TerminalSelection(TerminalPoint(100, 2), TerminalPoint(120, 7))))
        // A column changed: the engine clamped an end, so the anchor is not simply renumbered.
        assertNull(driftBetween(sent, TerminalSelection(TerminalPoint(60, 0), TerminalPoint(64, 7))))
    }

    @Test fun theSemanticsSelectionRangeIsClippedToTheVisibleRows() {
        val frame = frameWith(
            lines = listOf("one", "two", "three", "four"),
            selection = TerminalSelection(TerminalPoint(1, 1), TerminalPoint(2, 2)),
        )
        // "one\ntwo\nthree\nfour": row 1 starts at 4, so "wo\nthr" is 5..11.
        val range = selectionRangeOf(frame)
        assertEquals("wo\nthr", frame.plainText().substring(range.start, range.end))

        // A selection that runs off the top of the viewport starts at the first visible row.
        val deep = frameWith(
            lines = listOf("one", "two", "three", "four"),
            selection = TerminalSelection(TerminalPoint(0, 0), TerminalPoint(11, 1)),
            viewportTop = 10,
            historyRows = 10,
        )
        val clipped = selectionRangeOf(deep)
        assertEquals("one\ntw", deep.plainText().substring(clipped.start, clipped.end))
    }

    // ----------------------------------------------------------------- the real surface ----

    @OptIn(ExperimentalTestApi::class)
    @Test fun aMouseDragSelectsWithoutSendingAByte() = terminalInputTest { fixture ->
        fixture.feed("alpha beta gamma\r\ndelta epsilon")
        waitForIdle()

        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(0, 0))
            press()
            moveTo(fixture.centreOf(4, 0))
            release()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selection() != null }
        waitForIdle()

        assertEquals(TerminalSelection(TerminalPoint(0, 0), TerminalPoint(0, 4)), fixture.selection())
        assertEquals("alpha", fixture.selectedText())
        assertEquals(0, fixture.engine.mouseCalls.get(), "a local selection became a mouse event")
        fixture.assertSilence("a local selection")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aReverseDragSelectsTheSameSpanAsAForwardOne() = terminalInputTest { fixture ->
        fixture.feed("alpha beta gamma")
        waitForIdle()

        // Right to left, and upward: the anchor is where the press landed, not where it ended.
        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(9, 0))
            press()
            moveTo(fixture.centreOf(6, 0))
            release()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText().isNotEmpty() }
        waitForIdle()

        assertEquals("beta", fixture.selectedText(), "a reverse drag selected something else")
        fixture.assertSilence("a reverse drag")

        // And a reverse drag across rows, which is where an un-normalized span breaks.
        fixture.feed("\r\nsecond row here")
        waitForIdle()
        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(5, 1))
            press()
            moveTo(fixture.centreOf(11, 0))
            release()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText().contains("\n") }
        waitForIdle()
        assertEquals("gamma\nsecond", fixture.selectedText())
        fixture.assertSilence("a reverse drag across rows")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun copiedTextFollowsSoftAndHardWraps() = terminalInputTest { fixture ->
        // Longer than the grid is wide, so it wraps SOFTLY onto the next row with no newline in it.
        // The grid is whatever fits the surface, so the line is built from it rather than assumed.
        val wrapped = "W".repeat(fixture.columns - 5) + "-continues-here"
        fixture.feed(wrapped + "\r\nhard")
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.session.viewports.value.cursor.row >= 2 }
        waitForIdle()

        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(0, 0))
            press()
            moveTo(fixture.centreOf(3, 2))
            release()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText().endsWith("hard") }
        waitForIdle()

        val copied = fixture.selectedText()
        // The soft wrap is NOT a newline — the shell would run two commands if it were — while the
        // hard one the program printed is. The engine is the only thing that knows the difference.
        assertEquals(wrapped + "\nhard", copied)
        assertEquals(1, copied.count { it == '\n' }, "a soft wrap became a newline: $copied")
        fixture.assertSilence("selecting a wrapped line")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aWideCharacterIsCopiedWholeOrNotAtAll() = terminalInputTest { fixture ->
        // Each 世 takes two cells, so the three of them span columns 0..5.
        fixture.feed("世界語x")
        waitUntil(timeoutMillis = INPUT_TIMEOUT) {
            fixture.session.viewports.value.rows.first().cells.first().text == "世"
        }
        waitForIdle()

        // Press on the RIGHT half of the first glyph and release on the LEFT half of the second.
        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(1, 0))
            press()
            moveTo(fixture.centreOf(2, 0))
            release()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText().isNotEmpty() }
        waitForIdle()

        // Both glyphs, whole: no half-character, no replacement character, no dropped cell.
        assertEquals("世界", fixture.selectedText())
        fixture.assertSilence("selecting wide characters")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aSelectionSurvivesNewOutputAndACancelledFling() = terminalInputTest { fixture ->
        fixture.feed((0 until 200).joinToString("") { "line-$it\r\n" })
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.session.viewports.value.historyRows > 100 }
        waitForIdle()

        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(0, 0))
            press()
            moveTo(fixture.centreOf(6, 0))
            release()
            // The harness keeps a cursor "entered" after a mouse gesture and refuses a touch one
            // on top of it; this test needs both, so the mouse leaves properly.
            exit()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText().length > 6 }
        waitForIdle()
        val selected = fixture.selectedText()
        val anchored = assertNotNull(fixture.selection())

        // A fling, cancelled halfway by a new finger: the classic way a UI-held anchor gets lost.
        onNodeWithTag(INPUT_TAG).performTouchInput {
            swipeDown(startY = centerY - 60f, endY = centerY + 60f, durationMillis = 60)
        }
        // A second finger that drags rather than taps: a TAP would dismiss the selection, which is
        // what a tap is for. This is the gesture that cancels a fling without ending a selection.
        onNodeWithTag(INPUT_TAG).performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 12f))
            up()
        }
        waitForIdle()

        // And more output on top of it.
        val historyBefore = fixture.session.viewports.value.historyRows
        fixture.feed("more output\r\n")
        waitUntil(timeoutMillis = INPUT_TIMEOUT) {
            fixture.session.viewports.value.historyRows > historyBefore
        }
        waitForIdle()

        assertEquals(selected, fixture.selectedText(), "new output or a fling changed the selected text")
        assertEquals(anchored, fixture.selection(), "the anchor moved while its rows were retained")
        fixture.assertSilence("scrolling with a selection up")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun evictionTakesTheSelectionWithTheRowsItWasOn() =
        // Scrollback OFF. Ghostty evicts a PAGE at a time, so a small line limit is not small — a
        // few hundred rows still fit one page. With no scrollback at all, a row that leaves the
        // screen is really gone, which is the case this test is about.
        terminalInputTest(limits = TerminalLimits(historyLines = 0, historyBytes = 0)) { fixture ->
            fixture.feed("SELECT-ME here\r\n")
            waitForIdle()
            onNodeWithTag(INPUT_TAG).performMouseInput {
                moveTo(fixture.centreOf(0, 0))
                press()
                moveTo(fixture.centreOf(8, 0))
                release()
            }
            waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText().startsWith("SELECT-ME") }
            waitForIdle()

            // Enough output to push the selected row off a screen that keeps no history.
            fixture.feed((0 until fixture.rows * 3).joinToString("") { "filler-$it\r\n" })
            waitUntil(timeoutMillis = INPUT_TIMEOUT) { "SELECT-ME" !in fixture.selectedText() }
            waitForIdle()
            waitForIdle()

            // The selected TEXT is gone with its rows — cleared or clamped onto the oldest row the
            // engine still has, but never silently pointing at somebody else's text under the old
            // number. Both outcomes are visible; what must not happen is stale text.
            assertTrue("SELECT-ME" !in fixture.selectedText(), "the selection survived its rows")
            fixture.selection()?.let { live ->
                val top = fixture.session.viewports.value
                assertTrue(
                    live.start.row in 0..(top.historyRows + fixture.rows) &&
                        live.end.row in 0..(top.historyRows + fixture.rows),
                    "the selection points outside the rows the engine still has: $live",
                )
            }
            fixture.assertSilence("eviction")

            // And a new selection still works: the controller did not keep a dead anchor.
            onNodeWithTag(INPUT_TAG).performMouseInput {
                moveTo(fixture.centreOf(0, 9))
                press()
                moveTo(fixture.centreOf(5, 9))
                release()
            }
            waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText().startsWith("filler") }
            waitForIdle()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aLongPressSelectsAWordAndItsHandlesExtendIt() = terminalInputTest { fixture ->
        fixture.feed("alpha beta gamma")
        waitForIdle()

        // A finger held still: the one gesture a touch user always has.
        onNodeWithTag(INPUT_TAG).performTouchInput {
            down(fixture.centreOf(7, 0))
            advanceEventTime(900)
            move()
            up()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText().isNotEmpty() }
        waitForIdle()
        assertEquals("beta", fixture.selectedText(), "a long press did not select the word under it")
        assertEquals(0, fixture.engine.mouseCalls.get())
        fixture.assertSilence("a long press")

        // The END handle sits just past the last selected cell, one row down; pull it right.
        onNodeWithTag(INPUT_TAG).performTouchInput {
            down(Offset(10 * 8f, 1 * 16f))
            moveTo(fixture.centreOf(15, 0))
            up()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selectedText().length > 4 }
        waitForIdle()
        assertEquals("beta gamma", fixture.selectedText())
        fixture.assertSilence("dragging a selection handle")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aTouchDragStillScrollsRatherThanSelecting() = terminalInputTest { fixture ->
        fixture.feed((0 until 200).joinToString("") { "line-$it\r\n" })
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.session.viewports.value.historyRows > 100 }
        waitForIdle()

        onNodeWithTag(INPUT_TAG).performTouchInput {
            swipeDown(startY = centerY - 40f, endY = centerY + 40f, durationMillis = 100)
        }
        waitForIdle()

        assertNull(fixture.selection(), "a finger drag selected instead of scrolling")
        assertFalse(fixture.scroll.following, "a finger drag did not walk into history")
        fixture.assertSilence("a finger drag")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aPlainClickDismissesTheSelection() = terminalInputTest { fixture ->
        fixture.feed("alpha beta")
        waitForIdle()
        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(0, 0))
            press()
            moveTo(fixture.centreOf(4, 0))
            release()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selection() != null }
        waitForIdle()

        onNodeWithTag(INPUT_TAG).performMouseInput { click(fixture.centreOf(8, 2)) }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.selection() == null }
        waitForIdle()
        assertNull(fixture.selection())
        fixture.assertSilence("clicking away a selection")
    }
}

/** The text of viewport row [row] of the newest published frame, or "" when there is none. */
internal fun dev.supermux.terminal.TerminalViewport.rowTextOrEmpty(row: Int): String =
    rows.firstOrNull { it.index == row }
        ?.cells
        ?.filter { it.width != 0 }
        ?.joinToString("") { it.text.ifEmpty { " " } }
        ?.trimEnd()
        .orEmpty()
