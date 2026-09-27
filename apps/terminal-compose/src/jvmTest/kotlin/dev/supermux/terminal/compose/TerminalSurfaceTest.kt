package dev.supermux.terminal.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.supermux.terminal.CellFlags
import dev.supermux.terminal.CellStyle
import dev.supermux.terminal.CursorShape
import dev.supermux.terminal.OutputOrigin
import dev.supermux.terminal.TerminalCell
import dev.supermux.terminal.TerminalColor
import dev.supermux.terminal.TerminalCursor
import dev.supermux.terminal.TerminalEffect
import dev.supermux.terminal.TerminalEngine
import dev.supermux.terminal.TerminalKey
import dev.supermux.terminal.TerminalLimits
import dev.supermux.terminal.TerminalMouse
import dev.supermux.terminal.TerminalRow
import dev.supermux.terminal.TerminalSelection
import dev.supermux.terminal.TerminalSession
import dev.supermux.terminal.TerminalSize
import dev.supermux.terminal.Underline
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The surface contract: what lands in which cell, how many cells fit, and what the surface tells
 * the session about it.
 *
 * Everything about occupancy is asserted on the RUNS the painter will draw — (row, column, span,
 * style) — not on pixels, so the suite says the same thing under any font. A screenshot may differ
 * between platforms; a glyph in the wrong column, a wide glyph drawn twice or an accent clipped
 * away would change these numbers, which is exactly what they are here to catch.
 *
 * The composable itself is driven through Compose's own test harness against a real Skia canvas,
 * with no Swing/AWT, DOM or UIKit terminal widget anywhere in the path — the only text rendering is
 * `DrawScope.drawText` inside `TerminalPainter`.
 */
class TerminalSurfaceTest {
    private val theme = TerminalTheme()

    // ------------------------------------------------------------------ layout fixture ----

    private val fixtureSize = TerminalSize(10, 4, 8, 16)

    private fun style(
        flags: Int = CellFlags.NONE,
        underline: Int = Underline.NONE,
        foreground: Long = TerminalColor.DEFAULT,
        background: Long = TerminalColor.DEFAULT,
    ) = CellStyle(foreground, background, flags, underline)

    private fun cells(vararg cells: TerminalCell): List<TerminalCell> {
        val blank = TerminalCell("", 1, style())
        return List(fixtureSize.columns) { cells.getOrElse(it) { blank } }
    }

    /**
     * A 10x4 screen with one of everything that can go wrong: a narrow ASCII glyph, a WIDE CJK
     * glyph and its continuation cell, a combining accent, a multi-codepoint emoji cluster, batched
     * styled ASCII, an inverse-video run, decorations over blank cells, and a cursor.
     */
    private fun fixtureFrame(selection: TerminalSelection? = null): TerminalFrame {
        val wide = style()
        val row0 = cells(
            TerminalCell("A", 1, style()),
            TerminalCell("界", 2, wide),
            TerminalCell("", 0, wide),
            TerminalCell("é", 1, style()),
            TerminalCell("👨‍👩‍👧", 2, style()),
            TerminalCell("", 0, style()),
        )
        val bold = style(CellFlags.BOLD)
        val italic = style(CellFlags.ITALIC)
        val row1 = cells(
            TerminalCell("b", 1, bold), TerminalCell("o", 1, bold),
            TerminalCell("l", 1, bold), TerminalCell("d", 1, bold),
            TerminalCell(" ", 1, style()),
            TerminalCell("i", 1, italic), TerminalCell("t", 1, italic),
        )
        val inverse = style(CellFlags.INVERSE)
        val row2 = cells(
            TerminalCell("", 1, style()), TerminalCell("", 1, style()),
            TerminalCell("i", 1, inverse), TerminalCell("n", 1, inverse),
            TerminalCell("v", 1, inverse), TerminalCell(" ", 1, inverse),
        )
        val underlined = style(underline = Underline.SINGLE)
        val row3 = cells(
            TerminalCell("a", 1, underlined), TerminalCell("b", 1, underlined),
            TerminalCell("c", 1, underlined),
            TerminalCell(" ", 1, style()),
            TerminalCell("", 1, underlined), TerminalCell("", 1, underlined),
            TerminalCell("", 1, style()),
            TerminalCell("o", 1, style(CellFlags.OVERLINE or CellFlags.STRIKETHROUGH)),
        )
        val model = ViewportModel()
        val applied = model.apply(
            ViewportFixtures.viewport(
                generation = 1,
                size = fixtureSize,
                rows = listOf(TerminalRow(0, row0), TerminalRow(1, row1), TerminalRow(2, row2), TerminalRow(3, row3)),
                full = true,
                cursor = TerminalCursor(column = 3, row = 1, shape = CursorShape.BLOCK, visible = true),
                selection = selection,
            ),
        )
        assertTrue(applied is ViewportUpdate.Applied, "fixture must be applicable: $applied")
        return assertNotNull(model.frame)
    }

    private fun List<TextRun>.onRow(row: Int) = filter { it.row == row }.sortedBy { it.column }

    @Test fun everyRunOwnsTheCellsTheTerminalGaveItAndNoOthers() {
        val runs = TerminalRuns.build(fixtureFrame(), theme)

        // Row 0: one run per cluster; the wide glyph spans two columns and its continuation cell
        // produces nothing at all.
        assertEquals(
            listOf(Triple(0, 1, "A"), Triple(1, 2, "界"), Triple(3, 1, "é"), Triple(4, 2, "👨‍👩‍👧")),
            runs.texts.onRow(0).map { Triple(it.column, it.columns, it.text) },
        )
        // Row 1: compatible ASCII is batched into ONE run per style, and the blank between them is
        // not drawn at all (spacing is geometry, never literal spaces).
        assertEquals(
            listOf(Triple(0, 4, "bold"), Triple(5, 2, "it")),
            runs.texts.onRow(1).map { Triple(it.column, it.columns, it.text) },
        )
        assertTrue(runs.texts.onRow(1).first().style.bold)
        assertTrue(runs.texts.onRow(1).last().style.italic)

        // No two runs may claim the same column, on any row.
        for (row in 0 until fixtureSize.rows) {
            val owned = mutableSetOf<Int>()
            for (run in runs.texts.onRow(row)) {
                for (column in run.column until run.column + run.columns) {
                    assertTrue(owned.add(column), "column $column of row $row is claimed twice")
                    assertTrue(column < fixtureSize.columns, "run leaves the grid at column $column")
                }
            }
        }
    }

    @Test fun theLogicalGridIsWhatTheSemanticsAndTheRunsAgreeOn() {
        val frame = fixtureFrame()
        // A wide glyph occupies two cells but is ONE character of text: a continuation cell that
        // repeated it would show up here as a doubled glyph.
        assertEquals("A界é👨‍👩‍👧", frame.rowText(0))
        assertEquals("bold it", frame.rowText(1))
        assertEquals("  inv", frame.rowText(2))
        assertEquals("abc    o", frame.rowText(3))
    }

    @Test fun inverseVideoSwapsTheResolvedColoursAndPaintsItsBackground() {
        val runs = TerminalRuns.build(fixtureFrame(), theme)
        val background = runs.backgrounds.filter { it.row == 2 }
        assertEquals(1, background.size, "the inverse cells are one background run: $background")
        assertEquals(2, background.first().column)
        assertEquals(4, background.first().columns, "the trailing inverse blank is part of the run")
        assertEquals(theme.foreground, background.first().color)

        val text = runs.texts.onRow(2).single()
        assertEquals(theme.background, text.style.foreground)
        // A blank cell never produces text even when it is styled.
        assertEquals("inv", text.text)
    }

    @Test fun decorationsCoverBlankCellsAndBreakWhereTheStyleDoes() {
        val runs = TerminalRuns.build(fixtureFrame(), theme).decorations.filter { it.row == 3 }
        assertEquals(
            listOf(Triple(0, 3, Underline.SINGLE), Triple(4, 2, Underline.SINGLE), Triple(7, 1, Underline.NONE)),
            runs.map { Triple(it.column, it.columns, it.underline) },
        )
        assertTrue(runs.last().overline && runs.last().strikethrough)
    }

    @Test fun aSelectionCoversTheColumnsItNamesAndNothingElse() {
        val selection = TerminalSelection(
            start = dev.supermux.terminal.TerminalPoint(row = 1, column = 2),
            end = dev.supermux.terminal.TerminalPoint(row = 2, column = 3),
        )
        val runs = TerminalRuns.build(fixtureFrame(selection), theme)
        assertEquals(
            listOf(Triple(1, 2, 8), Triple(2, 0, 4)),
            runs.selections.map { Triple(it.row, it.column, it.columns) },
        )
        assertTrue(runs.selections.all { it.color == theme.selectionBackground })
    }

    @Test fun widthZeroCellsAreNeverDrawnAndNeverBreakAnASCIIBatch() {
        val runs = TerminalRuns.build(fixtureFrame(), theme)
        assertTrue(runs.texts.none { it.text.isEmpty() }, "an empty run would still cost a layout")
        assertTrue(runs.texts.none { it.column == 2 && it.row == 0 }, "the continuation cell drew something")
        assertTrue(runs.texts.none { it.column == 5 && it.row == 0 }, "the emoji continuation cell drew something")
    }

    // ------------------------------------------------------------------ run reuse ----

    @Test fun anUnchangedFrameIsNeverTurnedIntoRunsTwice() {
        val cache = FrameRunsCache()
        val frame = fixtureFrame()
        val first = cache.runs(frame, theme)
        // A repaint with no new frame — a scroll offset, a blink, a neighbour invalidating.
        assertSame(first, cache.runs(frame, theme), "an unchanged frame walked every cell again")
        // A host that builds its theme inline hands the composable an equal-but-new object every
        // recomposition; that is not a change either.
        assertSame(first, cache.runs(frame, TerminalTheme()), "an equal theme was treated as a new one")

        // A new frame IS a new screen, whatever it contains: frames are immutable and published one
        // per update, so identity is the cheap and correct key.
        assertNotSame(first, cache.runs(fixtureFrame(), theme), "a new frame reused stale runs")

        // And a theme that actually differs rebuilds — with different colours, not just new objects.
        val inverted = TerminalTheme(foreground = theme.background, background = theme.foreground)
        val themed = cache.runs(frame, inverted)
        assertNotSame(first, themed, "a theme change kept the old colours")
        assertEquals(inverted.background, themed.texts.first { it.row == 2 }.style.foreground)
    }

    @Test fun anOverscanRowKeepsItsDerivedFrameAcrossPaints() {
        val cache = OverscanRunsCache()
        val frame = fixtureFrame()
        val row = frame.rows[0]
        val strip = cache.frame(frame, row, absoluteRow = 7)
        assertEquals(1, strip.size.rows, "the strip is one row")
        assertEquals(7L, strip.viewportTop)
        // The fling case: the same row, repainted at a different offset many times per second.
        assertSame(strip, cache.frame(frame, row, absoluteRow = 7), "the strip was rebuilt per paint")
        assertSame(cache.runs(strip, theme), cache.runs(strip, theme), "the strip's runs were rebuilt")

        // Everything its runs depend on is part of the key: the row, and where it sits.
        assertNotSame(strip, cache.frame(frame, frame.rows[1], absoluteRow = 7))
        assertNotSame(strip, cache.frame(frame, row, absoluteRow = 8))
    }

    // ------------------------------------------------------------------ geometry ----

    private val cell = CellMetrics(width = 10f, height = 20f, baseline = 15f, lineHeight = 18f)

    @Test fun theGridIsTheFlooredQuotientOfTheAvailableSpace() {
        assertEquals(TerminalGrid(80, 30), TerminalGeometry.gridFor(800f, 600f, cell))
        assertEquals(TerminalGrid(80, 30), TerminalGeometry.gridFor(809f, 619f, cell))
        assertEquals(TerminalGrid(1, 1), TerminalGeometry.gridFor(10f, 20f, cell))
    }

    @Test fun aHiddenLayoutProducesNoSizeAtAll() {
        assertNull(TerminalGeometry.gridFor(0f, 0f, cell))
        assertNull(TerminalGeometry.gridFor(9f, 600f, cell), "less than one column is no terminal")
        assertNull(TerminalGeometry.gridFor(800f, 19f, cell), "less than one row is no terminal")
        assertNull(TerminalGeometry.gridFor(Float.NaN, 600f, cell))
        assertNull(TerminalGeometry.nextSize(null, 0f, 0f, cell), "a 0x0 layout must not resize the session")
    }

    @Test fun theGridIsClampedToWhatTheEngineAccepts() {
        val huge = TerminalGeometry.clamp(10_000, 10_000)
        assertEquals(TerminalSize.MAX_DIMENSION, huge.columns, "columns survive: they decide where output wraps")
        assertTrue(huge.columns.toLong() * huge.rows <= TerminalSize.MAX_CELLS, "clamped to $huge")
        assertEquals(TerminalSize.MAX_CELLS / TerminalSize.MAX_DIMENSION, huge.rows)
        // The clamped grid is one the engine's own contract accepts (this constructor would throw).
        TerminalSize(huge.columns, huge.rows, 10, 20)
        assertEquals(TerminalGrid(1, 1), TerminalGeometry.clamp(0, 0))
    }

    @Test fun equalSizesAreCoalescedAndARotationIsNot() {
        val first = assertNotNull(TerminalGeometry.nextSize(null, 800f, 600f, cell))
        assertEquals(TerminalSize(80, 30, 10, 20), first)
        assertNull(TerminalGeometry.nextSize(first, 800f, 600f, cell), "the same layout must not resize again")
        assertNull(TerminalGeometry.nextSize(first, 805f, 609f, cell), "same cell count, same size, no resize")
        val rotated = assertNotNull(TerminalGeometry.nextSize(first, 600f, 800f, cell))
        assertEquals(TerminalSize(60, 40, 10, 20), rotated)
    }

    // ------------------------------------------------------------------ the run cache ----

    @Test fun theLayoutCacheIsBoundedByEntriesAndByMeasuredText() {
        val byEntries = TextRunCache<String>(maxEntries = 4, maxMeasuredChars = 1_000_000)
        repeat(1000) { index -> byEntries.put(key("unique-line-$index"), "layout-$index") }
        assertEquals(4, byEntries.size, "a unique line per frame must not grow the cache")
        assertNull(byEntries.get(key("unique-line-0")), "the oldest entries are gone")
        assertNotNull(byEntries.get(key("unique-line-999")), "the newest survived")

        val byChars = TextRunCache<String>(maxEntries = 1_000_000, maxMeasuredChars = 50)
        repeat(1000) { index -> byChars.put(key("0123456789-$index"), "layout") }
        assertTrue(byChars.measuredChars <= 50, "measured ${byChars.measuredChars} chars")
        assertTrue(byChars.size <= 5, "held ${byChars.size} entries")

        // One line longer than the whole budget is never stored (and never evicts everything else).
        val long = "x".repeat(51)
        byChars.put(key(long), "layout")
        assertNull(byChars.get(key(long)))
    }

    @Test fun theCacheEvictsLeastRecentlyUsedAndClearsWhenTheFontChanges() {
        val cache = TextRunCache<String>(maxEntries = 2, maxMeasuredChars = 1000)
        cache.put(key("a"), "A")
        cache.put(key("b"), "B")
        assertEquals("A", cache.get(key("a")), "a hit refreshes 'a'")
        cache.put(key("c"), "C")
        assertNull(cache.get(key("b")), "'b' was the least recently used")
        assertNotNull(cache.get(key("a")))

        val signature = CacheSignature(theme.fontFamily, 13f, 10f, 20f, theme.hashCode())
        cache.retune(signature)
        cache.put(key("a"), "A")
        cache.retune(signature)
        assertNotNull(cache.get(key("a")), "an unchanged signature keeps the geometry")
        cache.retune(signature.copy(fontSizePx = 26f))
        assertEquals(0, cache.size, "a font or scale change clears the geometry cache")
        assertEquals(0, cache.measuredChars)
    }

    private fun key(text: String) = TextRunKey(text, 13f, theme.fontFamily, bold = false, italic = false)

    // ------------------------------------------------------------------ the composable ----

    /**
     * Engine stand-in: it records what the surface asks of it and answers with a full frame of
     * whatever text was fed. Partial frames are the reducer's business ([ViewportModelTest]); what
     * matters here is the size the surface asks for and how often.
     */
    private class FixtureEngine(initial: TerminalSize) : TerminalEngine {
        @Volatile var size: TerminalSize = initial
        val resizes: MutableList<TerminalSize> = Collections.synchronizedList(mutableListOf())

        @Volatile private var generation = 1L

        @Volatile private var lines: List<String> = emptyList()

        override fun feed(bytes: ByteArray, origin: OutputOrigin) {
            lines = bytes.decodeToString().split("\n")
            generation++
        }

        override fun reset() {
            lines = emptyList()
            generation++
        }

        override fun resize(size: TerminalSize) {
            this.size = size
            resizes += size
            generation++
        }

        override fun colors(colors: dev.supermux.terminal.TerminalColors) {
            generation++
        }

        override fun viewport(forceFull: Boolean, breakHold: Boolean) =
            ViewportFixtures.full(generation, size, lines)

        override fun acknowledge(generation: Long) = Unit
        override fun scrollTo(row: Long) = Unit
        override fun key(key: TerminalKey) = Unit
        override fun mouse(mouse: TerminalMouse) = Unit
        override fun paste(text: String, allowUnsafe: Boolean) = true
        override fun focus(focused: Boolean) = Unit
        override fun select(selection: TerminalSelection?) = Unit
        override fun selectedText() = ""
        override fun drainEffects(): List<TerminalEffect> = emptyList()
        override fun close() = Unit
    }

    private fun openSession(engine: FixtureEngine): TerminalSession = runBlocking {
        TerminalSession.open(
            size = engine.size,
            limits = TerminalLimits(),
            engineFactory = { _, _ -> engine },
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun theSurfaceResizesOncePerLayoutAndNeverWhenItIsNotThere() = runComposeUiTest {
        val engine = FixtureEngine(TerminalSize(80, 24, 8, 16))
        val session = openSession(engine)
        try {
            var portrait by mutableStateOf(true)
            var visible by mutableStateOf(false)
            var boxPx = IntSize.Zero
            setContent {
                val width = if (portrait) 400.dp else 300.dp
                val height = if (portrait) 300.dp else 400.dp
                Box(
                    Modifier
                        .size(if (visible) width else 0.dp, if (visible) height else 0.dp)
                        .onSizeChanged { boxPx = it },
                ) {
                    Terminal(session, Modifier.fillMaxSize().testTag(TAG))
                }
            }
            waitForIdle()
            assertEquals(emptyList(), engine.resizes.toList(), "a 0x0 layout must not resize the engine")

            visible = true
            waitUntil(timeoutMillis = TIMEOUT) { engine.resizes.isNotEmpty() }
            waitForIdle()
            assertEquals(1, engine.resizes.size, "one layout, one resize")
            val first = engine.resizes.first()
            assertTrue(first.columns > 1 && first.rows > 1, "got $first")
            // floor(): the grid fits the box, and one more cell would not.
            assertTrue(first.columns * first.cellWidthPx <= boxPx.width, "columns overflow $boxPx: $first")
            assertTrue(first.rows * first.cellHeightPx <= boxPx.height, "rows overflow $boxPx: $first")
            assertTrue((first.columns + 1) * first.cellWidthPx > boxPx.width, "a whole column was left unused: $first")

            portrait = false
            waitUntil(timeoutMillis = TIMEOUT) { engine.resizes.size > 1 }
            waitForIdle()
            assertEquals(2, engine.resizes.size, "a rotation is exactly one more resize")
            val rotated = engine.resizes.last()
            assertTrue(rotated.columns < first.columns && rotated.rows > first.rows, "got $rotated after $first")
        } finally {
            runBlocking { session.close() }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun theSurfacePublishesTheGridItDrawsAsSemantics() = runComposeUiTest {
        val engine = FixtureEngine(TerminalSize(20, 4, 8, 16))
        val session = openSession(engine)
        try {
            setContent {
                Box(Modifier.size(400.dp, 300.dp)) {
                    Terminal(session, Modifier.fillMaxSize().testTag(TAG))
                }
            }
            waitForIdle()
            runBlocking { session.receive("hello\nwide 界 here".encodeToByteArray()) }
            waitUntil(timeoutMillis = TIMEOUT) { screenText().startsWith("hello") }

            val screen = screenText()
            assertEquals("hello", screen.lineSequence().first())
            assertEquals("wide 界 here", screen.lineSequence().drop(1).first())
        } finally {
            runBlocking { session.close() }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun oneSurfaceGoingInactiveDoesNotFreezeAnotherOnTheSameSession() = runComposeUiTest {
        val engine = FixtureEngine(TerminalSize(20, 4, 8, 16))
        val session = openSession(engine)
        try {
            var firstActive by mutableStateOf(true)
            setContent {
                Column {
                    Box(Modifier.size(400.dp, 150.dp)) {
                        Terminal(session, Modifier.fillMaxSize().testTag(TAG), active = firstActive)
                    }
                    Box(Modifier.size(400.dp, 150.dp)) {
                        Terminal(session, Modifier.fillMaxSize().testTag(SECOND_TAG))
                    }
                }
            }
            waitForIdle()
            runBlocking { session.receive("both".encodeToByteArray()) }
            waitUntil(timeoutMillis = TIMEOUT) { textOf(SECOND_TAG).startsWith("both") }

            // The first surface goes off-screen and releases ITS lease. Rendering is reference
            // counted, so the surface still on screen keeps getting frames — a session-wide flag
            // here would freeze it on "both" forever.
            firstActive = false
            waitForIdle()
            runBlocking { session.receive("alone".encodeToByteArray()) }
            waitUntil(timeoutMillis = TIMEOUT) { textOf(SECOND_TAG).startsWith("alone") }
        } finally {
            runBlocking { session.close() }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.textOf(tag: String): String =
        onNodeWithTag(tag).fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
            .orEmpty()

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.screenText(): String =
        onNodeWithTag(TAG).fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
            .orEmpty()

    private companion object {
        const val TAG = "terminal-surface"
        const val SECOND_TAG = "terminal-surface-2"
        const val TIMEOUT = 10_000L
    }

    // ------------------------------------------------------------------ two surfaces ----

    /**
     * Two surfaces on one session must both end up showing the SAME screen.
     *
     * They each collect the same conflated `viewports` flow and each acknowledge. The session
     * publishes the next frame as soon as ANY of them acknowledges, so the slower one can be handed
     * a partial frame whose base it never saw — and a partial frame is a DELTA. Patched onto the
     * wrong base it corrupts cells silently: no exception, no wrong size, just stale text that
     * survives until something else forces a full frame.
     *
     * [TerminalViewport.sequence] is what makes that visible, and the assertion is the one a user
     * would make: both surfaces read back the same thing as the engine's own screen.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test fun twoSurfacesOnOneSessionConvergeOnTheSameScreen() = runComposeUiTest {
        val size = TerminalSize(30, 6, 8, 16)
        val session = runBlocking { TerminalSession.open(size, TerminalLimits()) }
        try {
            setContent {
                Column(Modifier.size(400.dp, 600.dp)) {
                    Box(Modifier.size(400.dp, 300.dp)) {
                        Terminal(session, Modifier.fillMaxSize().testTag("first"))
                    }
                    Box(Modifier.size(400.dp, 300.dp)) {
                        Terminal(session, Modifier.fillMaxSize().testTag("second"))
                    }
                }
            }
            waitForIdle()

            // Many small writes: every one of them is a frame, and the two collectors are never in
            // step, which is exactly the situation a skipped publication comes out of.
            repeat(60) { step ->
                runBlocking { session.receive("\u001b[H\u001b[2Kstep-$step".encodeToByteArray()) }
                waitForIdle()
            }
            runBlocking { session.receive("\u001b[H\u001b[2Kfinal line".encodeToByteArray()) }
            waitUntil(timeoutMillis = 10_000) {
                screenOf("first").startsWith("final line") && screenOf("second").startsWith("final line")
            }
            waitForIdle()

            assertEquals(
                screenOf("first"),
                screenOf("second"),
                "the two surfaces on one session drifted apart",
            )
        } finally {
            runBlocking { session.close() }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.screenOf(tag: String): String =
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
            .orEmpty()
}
