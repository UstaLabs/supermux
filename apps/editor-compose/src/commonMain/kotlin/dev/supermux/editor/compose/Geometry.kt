package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.SelectionRange

/**
 * Document offsets <-> positions, in CONTENT coordinates: x from the left edge of the text area,
 * y from the top of the document (scrolling and the gutter are the surface's to add).
 *
 * Built on the [HeightMap] (where each line is) and the [LineLayouts] (where each character is in
 * its line). Any line it lays out has its measured height recorded in the height map, so what it
 * answers and what the painter draws always agree.
 *
 * Positions snap to what Compose's layout reports as caret positions, so a caret never lands inside
 * a surrogate pair or a grapheme cluster.
 */
class Geometry(
    private val state: () -> EditorState,
    val heights: HeightMap,
    val layouts: LineLayouts,
) {
    /** Marks the surface adds on top of the state's decorations (the IME composition underline). */
    var extraMarks: RangeSet<Decoration>? = null

    /** [line]'s layout; records its measured height. */
    fun lineLayout(line: Int): TextLayoutResult {
        val st = state()
        if (heights.lineCount != st.doc.lineCount) heights.reset(st.doc.lineCount)
        val layout = layouts.layout(st, line, extraMarks)
        heights.setMeasured(line, layout.multiParagraph.height)
        return layout
    }

    /** Where [line]'s text starts (below any block widget above it). */
    fun lineTop(line: Int): Float = heights.top(line) + heights.blockAbove(line)

    /** The document offset nearest to content position [position]. */
    fun offsetAt(position: Offset): Int {
        val doc = state().doc
        val line = heights.lineAt(position.y)
        val layout = lineLayout(line)
        val top = lineTop(line)
        val h = layout.multiParagraph.height
        val y = (position.y - top).coerceIn(0f, maxOf(0f, h - 0.01f))
        val local = layout.getOffsetForPosition(Offset(position.x, y))
        return doc.lineStart(line) + local
    }

    /** The caret rect at [offset] (zero width: the painter decides how thick a caret is). */
    fun rectFor(offset: Int): Rect {
        val doc = state().doc
        val line = doc.lineIndexAt(offset)
        val layout = lineLayout(line)
        val r = layout.getCursorRect(offset - doc.lineStart(line))
        val top = lineTop(line)
        return Rect(r.left, r.top + top, r.left, r.bottom + top)
    }

    /**
     * The rects [range] covers, one per visual row it touches (all its lines, wrapped rows
     * included). A row whose line break is selected reaches one cell past its text.
     */
    fun selectionRects(range: SelectionRange): List<Rect> {
        if (range.empty) return emptyList()
        val doc = state().doc
        val out = ArrayList<Rect>()
        val first = doc.lineIndexAt(range.from)
        val last = doc.lineIndexAt(range.to)
        for (line in first..last) {
            val lineFrom = doc.lineStart(line)
            val lineTo = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) - 1 else doc.length
            val layout = lineLayout(line)
            val top = lineTop(line)
            val s = maxOf(range.from, lineFrom) - lineFrom
            val e = minOf(range.to, lineTo) - lineFrom
            val includesBreak = range.to > lineTo
            val rows = layout.lineCount
            for (row in 0 until rows) {
                val rs = layout.getLineStart(row)
                val re = if (row == rows - 1) lineTo - lineFrom else layout.getLineEnd(row)
                val a = maxOf(s, rs)
                val b = minOf(e, re)
                val lastRow = row == rows - 1
                if (a > b || (a == b && !(includesBreak && lastRow))) continue
                val left = if (a == rs) layout.getLineLeft(row) else layout.getHorizontalPosition(a, true)
                var right = if (b == re && !lastRow) layout.getLineRight(row) else layout.getHorizontalPosition(b, true)
                if (includesBreak && lastRow && b == re) right += layouts.charWidthPx
                out += Rect(left, top + layout.getLineTop(row), maxOf(left, right), top + layout.getLineBottom(row))
            }
        }
        return out
    }

    /** The lines to lay out for a viewport at [scrollY] of [viewportHeight] pixels, plus [overscan] each side. */
    fun visibleLines(scrollY: Float, viewportHeight: Float, overscan: Int): IntRange {
        val n = heights.lineCount
        if (n == 0) return IntRange.EMPTY
        val first = maxOf(0, heights.lineAt(scrollY) - overscan)
        val last = minOf(n - 1, heights.lineAt(scrollY + maxOf(0f, viewportHeight)) + overscan)
        return first..last
    }
}
