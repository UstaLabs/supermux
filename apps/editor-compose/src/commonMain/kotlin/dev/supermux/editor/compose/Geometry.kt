package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.SelectionRange
import kotlin.math.ceil

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
 *
 * **Lines longer than [LONG_LINE] units** (minified files, a 1 MB JSON line) are never laid out,
 * sliced or hashed whole: a keystroke on one would cost ~100 ms. Such a line is cut into pieces
 * (at grapheme boundaries) laid out one by one, only where they are looked at:
 * - without wrapping, pieces of [PIECE] units side by side: each starts where the previous one's
 *   MEASURED width ends (an unmeasured one is estimated at [PIECE] cells), so CJK and other wide
 *   text neither overlaps nor misses its clicks; the widths are remembered per line and dropped
 *   from an edit onwards ([onChanges]);
 * - with wrapping, rows of as many units as cells fit the width, so the line's height is known
 *   without shaping anything (a wide character may overhang its row).
 */
class Geometry(
    private val state: () -> EditorState,
    val heights: HeightMap,
    val layouts: LineLayouts,
) {
    /** Marks the surface adds on top of the state's decorations (the IME composition underline). */
    var extraMarks: RangeSet<Decoration>? = null

    private fun lineFrom(line: Int) = state().doc.lineStart(line)
    private fun lineTo(line: Int): Int {
        val doc = state().doc
        return if (line + 1 < doc.lineCount) doc.lineStart(line + 1) - 1 else doc.length
    }

    /** True when [line] is too long to lay out whole. */
    fun isLong(line: Int): Boolean = lineTo(line) - lineFrom(line) > LONG_LINE

    /** [line]'s layout (a line of at most [LONG_LINE] units); records its measured height. */
    fun lineLayout(line: Int): TextLayoutResult {
        val st = state()
        if (heights.lineCount != st.doc.lineCount) heights.reset(st.doc.lineCount)
        val layout = layouts.layout(st, line, extraMarks)
        heights.setMeasured(line, layout.multiParagraph.height)
        return layout
    }

    /** Measure [line] (short: its layout; long: its rows, without shaping) and record its height. */
    fun measure(line: Int) {
        if (!isLong(line)) { lineLayout(line); return }
        val st = state()
        if (heights.lineCount != st.doc.lineCount) heights.reset(st.doc.lineCount)
        val len = lineTo(line) - lineFrom(line)
        val cols = wrapCols()
        heights.setMeasured(line, if (cols > 0) pieceCount(len) * layouts.lineHeightPx else layouts.lineHeightPx)
        if (cols == 0) layouts.noteWidth(len * layouts.charWidthPx)
    }

    /** Where [line]'s text starts (below any block widget above it). */
    fun lineTop(line: Int): Float = heights.top(line) + heights.blockAbove(line)

    /** [line]'s text height (without its block widgets). */
    fun textHeight(line: Int): Float = heights.textHeight(line)

    /**
     * A vertical move's target [y] (content pixels) moved off block widgets (and lines with no
     * height): on a line's text it stays; on a widget, the nearest text row in direction [dir]
     * (the first row of the next line down, the last row of the one up). Null: no text that way.
     */
    fun textRowY(y: Float, dir: Int): Float? {
        val n = heights.lineCount
        if (n == 0) return null
        val line = heights.lineAt(y)
        val top = lineTop(line)
        val th = textHeight(line)
        if (th > 0f && y >= top && y < top + th) return y
        val onAbove = y < top
        if (dir > 0) {
            var l = if (onAbove) line else line + 1
            while (l < n && textHeight(l) <= 0f) l++
            return if (l < n) lineTop(l) + minOf(1f, textHeight(l) / 2) else null
        }
        var l = if (onAbove) line - 1 else line
        while (l >= 0 && textHeight(l) <= 0f) l--
        return if (l >= 0) lineTop(l) + textHeight(l) - minOf(1f, textHeight(l) / 2) else null
    }

    /**
     * The layouts to draw for [line] and where (line-local: from the line's text top-left), limited
     * to line-local x in [xFrom, xTo] and y in [yFrom, yTo] for a long line. A short line is one
     * layout at (0, 0).
     */
    fun visiblePieces(line: Int, xFrom: Float, xTo: Float, yFrom: Float, yTo: Float): List<Pair<Offset, TextLayoutResult>> {
        if (!isLong(line)) return listOf(Offset.Zero to lineLayout(line))
        measure(line)
        val from = lineFrom(line)
        val len = lineTo(line) - from
        val n = pieceCount(len)
        val (k0, k1) = if (wrapCols() > 0) {
            val lh = layouts.lineHeightPx
            (yFrom / lh).toInt().coerceIn(0, n - 1) to (yTo / lh).toInt().coerceIn(0, n - 1)
        } else {
            (pieceAtX(from, n, xFrom) - 1).coerceIn(0, n - 1) to (pieceAtX(from, n, xTo) + 1).coerceIn(0, n - 1)
        }
        return (k0..k1).map { k ->
            val (a, b) = pieceBounds(from, len, k)
            pieceOrigin(from, k, a) to pieceLayout(from, k, a, b)
        }
    }

    /** The document offset nearest to content position [position]. */
    fun offsetAt(position: Offset): Int {
        val doc = state().doc
        val line = heights.lineAt(position.y)
        if (isLong(line)) return longOffsetAt(line, position)
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
        if (isLong(line)) return longRectFor(line, offset)
        val layout = lineLayout(line)
        val r = layout.getCursorRect(offset - doc.lineStart(line))
        val top = lineTop(line)
        return Rect(r.left, r.top + top, r.left, r.bottom + top)
    }

    /**
     * The rects [range] covers, one per visual row it touches (all its lines, wrapped rows
     * included), limited to content y in [yFrom, yTo]. A row whose line break is selected reaches
     * one cell past its text.
     */
    fun selectionRects(range: SelectionRange, yFrom: Float = Float.NEGATIVE_INFINITY, yTo: Float = Float.POSITIVE_INFINITY): List<Rect> {
        if (range.empty) return emptyList()
        val doc = state().doc
        val out = ArrayList<Rect>()
        val first = doc.lineIndexAt(range.from)
        val last = doc.lineIndexAt(range.to)
        for (line in first..last) {
            val lineFrom = doc.lineStart(line)
            val lineTo = lineTo(line)
            val includesBreak = range.to > lineTo
            if (isLong(line)) { longSelection(line, lineFrom, lineTo, range, includesBreak, yFrom, yTo, out); continue }
            val layout = lineLayout(line)
            val top = lineTop(line)
            val s = maxOf(range.from, lineFrom) - lineFrom
            val e = minOf(range.to, lineTo) - lineFrom
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

    /**
     * The visual row holding [offset], as document offsets `start to end`: the whole line without
     * wrapping; with wrapping, the row's first offset and its last VISIBLE one (a row broken at a
     * space ends before the space, so a caret there stays on this row). The last row ends at the
     * line's end. At a wrap point an offset belongs to the row it starts.
     */
    fun rowBounds(offset: Int): Pair<Int, Int> {
        val doc = state().doc
        val at = offset.coerceIn(0, doc.length)
        val line = doc.lineIndexAt(at)
        val from = lineFrom(line)
        val to = lineTo(line)
        if (isLong(line)) {
            if (wrapCols() == 0) return from to to
            val len = to - from
            val k = pieceOf(from, len, at)
            val (a, b) = pieceBounds(from, len, k)
            // A row's end offset is the next row's start: the caret there shows on the next row.
            return a to (if (k == pieceCount(len) - 1) to else maxOf(a, TextBoundaries.prevGrapheme(state().doc, b)))
        }
        val layout = lineLayout(line)
        if (layout.lineCount <= 1) return from to to
        val row = layout.getLineForOffset(at - from)
        val start = from + layout.getLineStart(row)
        var end = if (row == layout.lineCount - 1) to else from + layout.getLineEnd(row, visibleEnd = true)
        // A row broken inside a token (no space to end it) ends at the next row's first offset, where a
        // caret shows at the next row's start: the row's end is the last position still on it.
        if (row < layout.lineCount - 1 && end == from + layout.getLineStart(row + 1)) end = TextBoundaries.prevGrapheme(state().doc, end)
        return start to maxOf(start, end)
    }

    /** Forget every long line's measured piece widths (another document). */
    fun clearPieceWidths() = pieceWidths.clear()

    /** The lines to lay out for a viewport at [scrollY] of [viewportHeight] pixels, plus [overscan] each side. */
    fun visibleLines(scrollY: Float, viewportHeight: Float, overscan: Int): IntRange {
        val n = heights.lineCount
        if (n == 0) return IntRange.EMPTY
        val first = maxOf(0, heights.lineAt(scrollY) - overscan)
        val last = minOf(n - 1, heights.lineAt(scrollY + maxOf(0f, viewportHeight)) + overscan)
        return first..last
    }

    // ------------------------------------------------------------------ long lines --

    /** Units per row while wrapping (0: not wrapping). */
    private fun wrapCols(): Int = layouts.wrapWidthPx?.let { maxOf(1, (it / layouts.charWidthPx).toInt()) } ?: 0

    private fun pieceSize(): Int = wrapCols().takeIf { it > 0 } ?: PIECE

    private fun pieceCount(len: Int): Int = maxOf(1, ceil(len.toDouble() / pieceSize()).toInt())

    /** Piece [k]'s document range, its edges moved back to grapheme boundaries. */
    private fun pieceBounds(from: Int, len: Int, k: Int): Pair<Int, Int> {
        val size = pieceSize()
        val n = pieceCount(len)
        val doc = state().doc
        fun edge(i: Int): Int = when {
            i <= 0 -> from
            i >= n -> from + len
            else -> TextBoundaries.snap(doc, from + i * size)
        }
        return edge(k) to edge(k + 1)
    }

    /** The piece holding [offset] (at a piece edge: the piece it starts). */
    private fun pieceOf(from: Int, len: Int, offset: Int): Int {
        val n = pieceCount(len)
        var k = ((offset - from) / pieceSize()).coerceIn(0, n - 1)
        val (a, b) = pieceBounds(from, len, k)
        if (offset < a && k > 0) k--
        else if (offset >= b && k < n - 1) k++
        return k
    }

    private fun pieceOrigin(from: Int, k: Int, @Suppress("UNUSED_PARAMETER") start: Int): Offset =
        if (wrapCols() > 0) Offset(0f, k * layouts.lineHeightPx) else Offset(originX(from, k), 0f)

    /** Measured widths of unwrapped long lines' pieces, by the line's start offset: piece -> width. */
    private val pieceWidths = HashMap<Int, HashMap<Int, Float>>()

    /** Piece [k]'s layout, its width remembered for the running sum of origins. */
    private fun pieceLayout(from: Int, k: Int, a: Int, b: Int): TextLayoutResult {
        val layout = layouts.layoutRange(state(), a, b, extraMarks)
        if (wrapCols() == 0) pieceWidths.getOrPut(from) { HashMap() }[k] = layout.multiParagraph.maxIntrinsicWidth
        return layout
    }

    private fun widthOf(from: Int, k: Int): Float = pieceWidths[from]?.get(k) ?: (PIECE * layouts.charWidthPx)

    /** Where piece [k] starts: the pieces before it laid side by side. */
    private fun originX(from: Int, k: Int): Float {
        var x = 0f
        for (i in 0 until k) x += widthOf(from, i)
        return x
    }

    /** The piece under line-local [x] (unwrapped). */
    private fun pieceAtX(from: Int, n: Int, x: Float): Int {
        var acc = 0f
        for (k in 0 until n) {
            acc += widthOf(from, k)
            if (x < acc) return k
        }
        return n - 1
    }

    /**
     * Follow an edit: a long line's remembered piece widths move with its start, and only the
     * pieces before the edit's first change in it stay (the others' boundaries moved).
     */
    fun onChanges(changes: dev.supermux.editor.core.ChangeSet) {
        if (pieceWidths.isEmpty() || changes.isEmpty) return
        val edits = changes.iterChanges()
        val next = HashMap<Int, HashMap<Int, Float>>()
        for ((start, widths) in pieceWidths) {
            if (edits.any { it.fromA < start && it.toA > start }) continue // its start was deleted
            val firstInside = edits.firstOrNull { it.toA >= start }?.fromA
            val keep = if (firstInside == null) widths else {
                val kEdit = (firstInside - start) / PIECE
                HashMap(widths.filterKeys { it < kEdit })
            }
            if (keep.isNotEmpty()) next[changes.mapPos(start, -1)] = keep
        }
        pieceWidths.clear()
        pieceWidths.putAll(next)
    }

    private fun longRectFor(line: Int, offset: Int): Rect {
        measure(line)
        val from = lineFrom(line)
        val len = lineTo(line) - from
        val k = pieceOf(from, len, offset)
        val (a, b) = pieceBounds(from, len, k)
        val o = pieceOrigin(from, k, a)
        val r = pieceLayout(from, k, a, b).getCursorRect(offset - a)
        val top = lineTop(line) + o.y
        return Rect(o.x + r.left, top + r.top, o.x + r.left, top + r.bottom)
    }

    private fun longOffsetAt(line: Int, position: Offset): Int {
        measure(line)
        val from = lineFrom(line)
        val len = lineTo(line) - from
        val n = pieceCount(len)
        val lh = layouts.lineHeightPx
        val localY = position.y - lineTop(line)
        val k = if (wrapCols() > 0) (localY / lh).toInt().coerceIn(0, n - 1) else pieceAtX(from, n, position.x)
        val (a, b) = pieceBounds(from, len, k)
        val o = pieceOrigin(from, k, a)
        val layout = pieceLayout(from, k, a, b)
        val local = layout.getOffsetForPosition(Offset(position.x - o.x, (localY - o.y).coerceIn(0f, lh - 0.01f)))
        return a + local
    }

    private fun longSelection(
        line: Int, lineFrom: Int, lineTo: Int, range: SelectionRange, includesBreak: Boolean,
        yFrom: Float, yTo: Float, out: MutableList<Rect>,
    ) {
        val s = maxOf(range.from, lineFrom)
        val e = minOf(range.to, lineTo)
        val cw = layouts.charWidthPx
        val lh = layouts.lineHeightPx
        val top = lineTop(line)
        val start = rectFor(s)
        val end = rectFor(e)
        val extra = if (includesBreak) cw else 0f
        if (wrapCols() == 0) {
            out += Rect(start.left, top, maxOf(start.left, end.left + extra), top + lh)
            return
        }
        // One rect per row, only the rows in [yFrom, yTo].
        val rowOf = { r: Rect -> ((r.top - top) / lh).toInt() }
        val r0 = rowOf(start)
        val r1 = rowOf(end)
        val rowWidth = wrapCols() * cw
        val first = maxOf(r0, ((yFrom - top) / lh).toInt() - 1)
        val last = minOf(r1, ((yTo - top) / lh).toInt() + 1)
        for (row in first..last) {
            val left = if (row == r0) start.left else 0f
            val right = if (row == r1) end.left + extra else rowWidth
            out += Rect(left, top + row * lh, maxOf(left, right), top + (row + 1) * lh)
        }
    }

    companion object {
        /** Longer lines (UTF-16 units) are laid out in pieces, never whole. */
        const val LONG_LINE = 10_000

        /** A long line's piece without wrapping, in units. */
        const val PIECE = 2048
    }
}
