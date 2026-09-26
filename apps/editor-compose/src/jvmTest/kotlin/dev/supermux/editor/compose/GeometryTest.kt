package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.decorationsFacet
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeometryTest {
    /** Tabs at several columns, an emoji (a surrogate pair), CJK, and e + a combining acute. */
    private val sample = listOf(
        "fun main() {",
        "\tval x = 1\t// tab",
        "ab\tc\t\td",
        "emoji 😀 and 🎉!",
        "日本語のテキスト",
        "café résumé",
        "",
        "last",
    ).joinToString("\n")

    /** Every grapheme boundary of [text] (the JDK's break iterator: test-only ground truth). */
    private fun boundaries(text: String): List<Int> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = ArrayList<Int>()
        var b = it.first()
        while (b != BreakIterator.DONE) { out += b; b = it.next() }
        return out
    }

    @Test fun everyCaretPositionRoundTripsThroughItsRect() = withMeasure { m ->
        val state = EditorState.create(sample)
        val g = m.geometry({ state })
        val valid = boundaries(sample).toSet()
        for (o in valid.sorted()) {
            val r = g.rectFor(o)
            assertEquals(o, g.offsetAt(r.center), "offset $o (rect $r) did not round-trip")
        }
        // And any pointer position lands on a boundary, never inside a pair or a cluster.
        for (line in 0 until state.doc.lineCount) {
            val y = g.lineTop(line) + g.layouts.lineHeightPx / 2
            var x = -5f
            while (x < 40 * g.layouts.charWidthPx) {
                val o = g.offsetAt(Offset(x, y))
                assertTrue(o in valid, "x=$x on line $line gave $o, inside a grapheme")
                x += g.layouts.charWidthPx / 3
            }
        }
    }

    @Test fun tabsAdvanceToTheNextTabStop() = withMeasure { m ->
        val state = EditorState.create(sample)
        val g = m.geometry({ state })
        val cw = g.layouts.charWidthPx
        val line2 = state.doc.lineStart(2) // "ab\tc\t\td"
        fun x(o: Int) = g.rectFor(line2 + o).left
        assertClose(2 * cw, x(2), "before the tab")
        assertClose(4 * cw, x(3), "after the first tab (col 2 -> 4)")
        assertClose(5 * cw, x(4), "after c")
        assertClose(8 * cw, x(5), "col 5 -> 8")
        assertClose(12 * cw, x(6), "a full tab: col 8 -> 12")
        val line1 = state.doc.lineStart(1) // "\tval x = 1\t// tab"
        assertClose(4 * cw, g.rectFor(line1 + 1).left, "a leading tab")
    }

    @Test fun selectionRectsCoverEachLineAndEachWrappedRow() = withMeasure { m ->
        val text = "first line\nsecond\nthird line"
        val state = EditorState.create(text)
        val g = m.geometry({ state })
        val rects = g.selectionRects(SelectionRange(3, text.indexOf("third") + 3))
        assertEquals(3, rects.size, "one rect per line: $rects")
        assertTrue(rects[0].top < rects[1].top && rects[1].top < rects[2].top)
        // The line break is part of the selection: the first two rects reach past their text.
        assertTrue(rects[0].right > g.rectFor(10).left)

        val long = "word ".repeat(40).trim()
        val wrapped = EditorState.create("$long\nnext")
        val wg = m.geometry({ wrapped }, wrapWidthPx = (30 * m.layouts().charWidthPx).toInt())
        val layout = wg.lineLayout(0)
        assertTrue(layout.lineCount > 3, "the long line wraps (${layout.lineCount} rows)")
        val wrects = wg.selectionRects(SelectionRange(0, long.length + 2))
        assertEquals(layout.lineCount + 1, wrects.size, "one rect per visual row, plus the next line")
        // The measured height fed the height map: the next line starts below every row.
        assertClose(layout.size.height.toFloat(), wg.lineTop(1), "the next line's top")
    }

    @Test fun visibleLinesComeFromTheHeightMap() = withMeasure { m ->
        val state = EditorState.create((0 until 1000).joinToString("\n") { "line $it" })
        val g = m.geometry({ state })
        val lh = g.layouts.lineHeightPx
        val range = g.visibleLines(scrollY = 100 * lh + 1, viewportHeight = 10 * lh, overscan = 2)
        assertEquals(98, range.first)
        assertEquals(112, range.last)
        assertEquals(0..3, g.visibleLines(0f, 2 * lh - 1, overscan = 2))
        assertEquals(997..999, g.visibleLines(999 * lh, 100 * lh, overscan = 2))
    }

    @Test fun changingMarksRemeasuresOnlyTheLineTheyAreOn() = withMeasure { m ->
        val setMarks = StateEffectType<List<Ranged<Decoration>>>("marks")
        val marks = StateField<RangeSet<Decoration>>(
            name = "marks",
            create = { RangeSet.empty() },
            update = { v, tr -> tr.effects.firstNotNullOfOrNull { it.valueIf(setMarks) }?.let { RangeSet.of(it) } ?: v.map(tr.changes) },
            provide = { f -> decorationsFacet.compute(FacetDep.field(f)) { it.field(f) } },
        )
        var state = EditorState.create("alpha\nbeta\ngamma", extensions = marks)
        val g = m.geometry({ state })
        val kw = Decoration.Mark(setOf("tok-keyword"))
        state = state.update(TransactionSpec(effects = listOf(setMarks.of(listOf(Ranged(0, 5, kw), Ranged(6, 10, kw)))))).state
        for (i in 0..2) g.lineLayout(i)
        val before = g.layouts.measureCount
        for (i in 0..2) g.lineLayout(i)
        assertEquals(before, g.layouts.measureCount, "an unchanged frame measures nothing")
        // Move the mark on line 1 only.
        state = state.update(TransactionSpec(effects = listOf(setMarks.of(listOf(Ranged(0, 5, kw), Ranged(6, 8, kw)))))).state
        for (i in 0..2) g.lineLayout(i)
        assertEquals(before + 1, g.layouts.measureCount, "only line 1 was measured again")
        // And the mark really colours its text.
        val layout = g.lineLayout(1)
        val kwColor = m.theme.tokens.getValue("tok-keyword").color
        assertEquals(kwColor, layout.layoutInput.text.spanStyles.single().item.color)
    }

    private val longLine = (0 until 30_000).joinToString("") { ('a' + it % 26).toString() }

    @Test fun aHugeLineIsLaidOutOnlyWhereItIsLookedAt() = withMeasure { m ->
        val state = EditorState.create("short\n$longLine\nend")
        val g = m.geometry({ state })
        val cw = g.layouts.charWidthPx
        val base = state.doc.lineStart(1)
        for (o in listOf(0, 1, 2047, 2048, 2049, 15_000, 29_999, 30_000)) {
            val r = g.rectFor(base + o)
            assertClose(o * cw, r.left, "x of $o")
            assertEquals(base + o, g.offsetAt(r.center), "offset $o did not round-trip")
        }
        assertTrue(g.layouts.measureCount < 12, "${g.layouts.measureCount} layouts for a handful of lookups")
        assertEquals(g.layouts.lineHeightPx, g.heights.height(1))
        // A selection over the whole line is one rect, laid out at its two ends only.
        val rects = g.selectionRects(SelectionRange(base, base + 30_000))
        assertEquals(1, rects.size)
        assertClose(30_000 * cw, rects[0].right, "the selection's end")
    }

    @Test fun aHugeLineWrapsIntoRowsOfWholeCells() = withMeasure { m ->
        val state = EditorState.create("short\n$longLine\nend")
        val cols = 100
        val g = m.geometry({ state }, wrapWidthPx = (cols * m.layouts().charWidthPx).toInt() + 1)
        val lh = g.layouts.lineHeightPx
        g.measure(1)
        assertEquals(300 * lh, g.heights.height(1), 0.5f, "30,000 units in rows of $cols")
        val base = state.doc.lineStart(1)
        for (o in listOf(0, 99, 100, 12_345, 29_999)) {
            val r = g.rectFor(base + o)
            assertClose((o / cols) * lh + g.lineTop(1), r.top, "row of $o")
            assertEquals(base + o, g.offsetAt(r.center))
        }
        assertTrue(g.layouts.measureCount < 12, "${g.layouts.measureCount} rows laid out")
        // A selection is clipped to the rows asked for.
        val rects = g.selectionRects(SelectionRange(base, base + 30_000), yFrom = g.lineTop(1), yTo = g.lineTop(1) + 10 * lh)
        assertTrue(rects.size in 10..12, "${rects.size} rects (the 10 rows asked for, a row of margin each side)")
    }

    private fun assertClose(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 0.75f, "$what: expected $expected, got $actual")
}
