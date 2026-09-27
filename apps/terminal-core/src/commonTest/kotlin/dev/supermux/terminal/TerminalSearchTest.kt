package dev.supermux.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Find-in-scrollback: the per-row matcher ([findInRow]) on real cell shapes, and
 * [TerminalSession.search] on the owner loop — that it finds what is on screen, and that walking the
 * viewport leaves the session publishing and acknowledging exactly as before.
 */
class TerminalSearchTest {
    private val style = CellStyle(TerminalColor.DEFAULT, TerminalColor.DEFAULT, CellFlags.NONE, Underline.NONE)

    private fun narrow(text: String) = TerminalCell(text, 1, style)
    private fun wide(text: String) = TerminalCell(text, 2, style)
    private fun continuation() = TerminalCell("", 0, style)
    private fun cells(line: String) = line.map { narrow(it.toString()) }

    private fun spans(cells: List<TerminalCell>, query: String, ignoreCase: Boolean = true): List<IntRange> {
        val found = mutableListOf<IntRange>()
        findInRow(cells, query, ignoreCase) { first, last -> found += first..last }
        return found
    }

    @Test fun findsEveryNonOverlappingOccurrence() {
        assertEquals(listOf(0..2, 4..6), spans(cells("abc abc"), "abc"))
        assertEquals(listOf(0..1, 2..3), spans(cells("aaaaa"), "aa"))
    }

    @Test fun caseIsIgnoredUnlessAskedNotTo() {
        assertEquals(listOf(6..10), spans(cells("hello World"), "world"))
        assertEquals(emptyList(), spans(cells("hello World"), "world", ignoreCase = false))
    }

    @Test fun anEmptyCellIsASpaceSoWordsDoNotRunTogether() {
        val row = listOf(narrow("a"), narrow(""), narrow("b"))
        assertEquals(listOf(0..2), spans(row, "a b"))
        assertEquals(emptyList(), spans(row, "ab"))
    }

    @Test fun aWideGlyphMatchCoversBothOfItsCells() {
        // "x日本y": 日 and 本 each take two columns.
        val row = listOf(narrow("x"), wide("日"), continuation(), wide("本"), continuation(), narrow("y"))
        assertEquals(listOf(1..4), spans(row, "日本"))
        assertEquals(listOf(3..5), spans(row, "本y"))
        assertEquals(listOf(0..2), spans(row, "x日"))
    }

    @Test fun aMultiCharacterClusterMapsBackToItsOneCell() {
        // A flag is two code points (four UTF-16 units) in one wide cell.
        val row = listOf(narrow("a"), wide("🇹🇷"), continuation(), narrow("b"))
        assertEquals(listOf(1..3), spans(row, "🇹🇷b"))
    }

    @Test fun anEmptyQueryFindsNothing() {
        assertEquals(emptyList(), spans(cells("abc"), ""))
    }

    // ------------------------------------------------------------------ session ----

    private val size = TerminalSize(20, 4, 8, 16)

    @Test fun searchFindsWhatIsOnScreenAndLeavesTheSessionPublishing() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = TerminalSession.open(
            size = size,
            limits = TerminalLimits(),
            colors = null,
            config = TerminalSessionConfig(),
            context = clock,
            effects = {},
            onEngineError = {},
            engineFactory = { _, _ -> engine },
            timeSource = clock,
        )
        session.acknowledge(session.viewports.value.generation)
        session.receive("error: one\nok\nERROR two".encodeToByteArray())
        clock.advanceUntilIdle()
        // A frame is published and deliberately NOT acknowledged: the search must not leave the
        // loop waiting for an ack of a frame it superseded.
        val before = session.viewports.value.sequence

        val result = session.search("error")
        assertFalse(result.truncated)
        assertEquals(
            listOf(TerminalSearchMatch(0, 0, 4), TerminalSearchMatch(2, 0, 4)),
            result.matches,
        )
        assertEquals(listOf(TerminalSearchMatch(2, 0, 4)), session.search("ERROR", ignoreCase = false).matches)
        assertTrue(engine.calls.contains("scrollTo(0)"), "the viewport is put back after the walk")

        clock.advanceUntilIdle()
        val after = session.viewports.value
        assertTrue(after.sequence > before, "the session publishes again after a search")
        assertTrue(after.full, "the first frame after a search is full")
        session.acknowledge(after.generation)
        session.receive("\nmore".encodeToByteArray())
        clock.advanceUntilIdle()
        assertTrue(session.viewports.value.sequence > after.sequence, "acknowledging still releases the next frame")
        session.close()
    }

    @Test fun anEmptyQueryNeverReachesTheEngine() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = TerminalSession.open(
            size = size,
            limits = TerminalLimits(),
            colors = null,
            config = TerminalSessionConfig(),
            context = clock,
            effects = {},
            onEngineError = {},
            engineFactory = { _, _ -> engine },
            timeSource = clock,
        )
        val reads = engine.viewportReads
        assertEquals(emptyList(), session.search("").matches)
        assertEquals(reads, engine.viewportReads)
        session.close()
    }

    // ------------------------------------------------------------------ real engine ----

    private fun feedLines(engine: TerminalEngine, count: Int) {
        val text = (0 until count).joinToString("\r\n") { "line $it${if (it % 50 == 7) " needle" else ""}" }
        engine.feed(text.encodeToByteArray(), OutputOrigin.LIVE)
    }

    @Test fun theRealEngineIsSearchedThroughItsWholeScrollbackAndLeftFollowing() {
        val engine = createTerminalEngine(TerminalSize(40, 10, 8, 16), TerminalLimits())
        try {
            feedLines(engine, 300)
            val before = engine.viewport(forceFull = true)
            assertTrue(before.historyRows > 0)
            assertEquals(before.historyRows, before.viewportTop, "following the bottom")

            val slice = searchSlice(engine, "NEEDLE", ignoreCase = true, fromRow = 0, maxRows = 10_000, maxMatches = 100)
            assertEquals(listOf(7L, 57L, 107L, 157L, 207L, 257L), slice.matches.map { it.row })
            // "line 7 needle": the needle starts after the line number, whose width varies.
            assertTrue(
                slice.matches.all { it.firstColumn == "line ${it.row} ".length && it.lastColumn == it.firstColumn + 5 },
                "${slice.matches}",
            )
            assertEquals(null, slice.nextRow)
            assertTrue(slice.complete)

            // Put back at the bottom AND still following it: new output keeps scrolling in.
            engine.feed("\r\nafter".encodeToByteArray(), OutputOrigin.LIVE)
            val after = engine.viewport(forceFull = true)
            assertEquals(after.historyRows, after.viewportTop)
            assertEquals(before.historyRows + 1, after.historyRows)
        } finally {
            engine.close()
        }
    }

    @Test fun aRealEngineSliceStopsWhereItWasToldAndRestoresAScrolledView() {
        val engine = createTerminalEngine(TerminalSize(40, 10, 8, 16), TerminalLimits())
        try {
            feedLines(engine, 300)
            engine.scrollTo(40)
            assertEquals(40L, engine.viewport(forceFull = true).viewportTop)

            val first = searchSlice(engine, "needle", ignoreCase = true, fromRow = 0, maxRows = 100, maxMatches = 100)
            assertEquals(listOf(7L, 57L), first.matches.map { it.row })
            assertEquals(100L, first.nextRow)
            val rest = searchSlice(engine, "needle", ignoreCase = true, fromRow = 100, maxRows = 10_000, maxMatches = 100)
            assertEquals(listOf(107L, 157L, 207L, 257L), rest.matches.map { it.row })

            assertEquals(40L, engine.viewport(forceFull = true).viewportTop, "the reader's place is kept")
        } finally {
            engine.close()
        }
    }

    @Test fun theMatchLimitCutsASliceShort() {
        val engine = createTerminalEngine(TerminalSize(40, 10, 8, 16), TerminalLimits())
        try {
            feedLines(engine, 300)
            val slice = searchSlice(engine, "needle", ignoreCase = true, fromRow = 0, maxRows = 10_000, maxMatches = 2)
            assertEquals(listOf(7L, 57L), slice.matches.map { it.row })
            assertFalse(slice.complete)
            assertEquals(58L, slice.nextRow)
        } finally {
            engine.close()
        }
    }
}
