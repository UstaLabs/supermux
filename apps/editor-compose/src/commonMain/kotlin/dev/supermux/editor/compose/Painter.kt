package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.decorationsFacet

/** One text layout to draw, its top-left in surface pixels. */
internal class DrawnText(val layout: TextLayoutResult, val topLeft: Offset, val color: Color = Color.Unspecified)

/**
 * What one layout pass decided, in SURFACE pixels: the draw pass paints exactly this and measures,
 * lays out and scrolls nothing (M3c: measure before draw). Built by [buildFrame].
 */
internal class SurfaceFrame(
    val size: Size,
    val lines: IntRange,
    val scrollX: Float,
    val scrollY: Float,
    val gutterWidth: Float,
    val currentLines: List<Rect>,
    val lineBackgrounds: List<Pair<Color, Rect>>,
    val selections: List<Rect>,
    val text: List<DrawnText>,
    val cursors: List<Rect>,
    val numbers: List<DrawnText>,
    val handles: List<HandleSpot>,
    /** The main caret (the hidden field and its pointer shield sit there). */
    val caret: Rect,
) {
    companion object {
        fun empty(size: Size, scrollX: Float, scrollY: Float, gutterWidth: Float, caret: Rect) = SurfaceFrame(
            size, IntRange.EMPTY, scrollX, scrollY, gutterWidth, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), caret,
        )
    }
}

/**
 * The layout pass: position the scroll (the anchor, clamping), lay out the visible lines plus
 * [EditorDefaults.OVERSCAN_LINES], and decide everything the frame paints.
 *
 * Laying lines out records their measured heights, so the visible range is computed again after
 * measuring (a wrapped line that turned out taller pushes the ones below it down in the same
 * frame), and the anchor keeps the text on screen where it was.
 */
internal fun EditorController.buildFrame(state: EditorState, theme: EditorTheme): SurfaceFrame {
    val g = geometry
    val doc = state.doc
    // The IME's composing text: underlined, on top of the state's own decorations.
    val composing = composition
    g.extraMarks = if (composing == null || composing.isEmpty() || composing.last >= doc.length) null
    else RangeSet.of(listOf(Ranged(composing.first, composing.last + 1, Decoration.Mark(setOf(EditorTheme.COMPOSITION_CLASS)))))
    val size = viewportSize
    val height = size.height
    val overscan = EditorDefaults.OVERSCAN_LINES

    beginAnchor()
    scroll.clamp()
    val guess = g.visibleLines(scroll.y, height, overscan)
    for (l in guess) g.measure(l)
    // The lines the frame looks at off screen too (the caret the hidden field sits at, the touch
    // handles' ends): measured BEFORE the anchor is restored, so their real heights move nothing.
    val main = state.selection.main
    for (at in intArrayOf(main.head, main.from, main.to)) doc.lineIndexAt(at).let { if (it !in guess) g.measure(it) }
    restoreAnchor()
    scroll.clamp()
    val lines = g.visibleLines(scroll.y, height, overscan)
    for (l in lines) if (l !in guess) g.measure(l)
    drawnLines = lines
    recordAnchor()

    val scrollX = scroll.x
    val scrollY = scroll.y
    val caret = caretRectOnScreen(main.head)
    if (lines.isEmpty()) return SurfaceFrame.empty(size, scrollX, scrollY, gutterWidth, caret)

    val viewStart = doc.lineStart(lines.first)
    val viewEnd = if (lines.last + 1 < doc.lineCount) doc.lineStart(lines.last + 1) - 1 else doc.length
    view.publishViewport(viewStart until viewEnd)

    val left = textLeft - scrollX
    fun top(line: Int) = g.lineTop(line) - scrollY
    fun textRow(line: Int) = Rect(gutterWidth, top(line), size.width, top(line) + g.textHeight(line))

    val ranges = state.selection.ranges
    // The current line, only while nothing is selected.
    val current = ArrayList<Rect>()
    if (ranges.all { it.empty }) {
        var last = -1
        for (r in ranges) {
            val line = doc.lineIndexAt(r.head)
            if (line == last || line !in lines) continue
            last = line
            current += textRow(line)
        }
    }
    // Line decorations.
    val backgrounds = ArrayList<Pair<Color, Rect>>()
    if (theme.lineClassBackgrounds.isNotEmpty()) {
        for (set in state.facet(decorationsFacet)) for (r in set.between(viewStart, viewEnd)) {
            val v = r.value as? Decoration.LineStyle ?: continue
            val color = v.classes.firstNotNullOfOrNull { theme.lineClassBackgrounds[it] } ?: continue
            backgrounds += color to textRow(doc.lineIndexAt(r.from))
        }
    }
    // Selections, clipped to the laid-out lines (a select-all never lays out the whole document).
    val selections = ArrayList<Rect>()
    for (r in ranges) {
        if (r.empty || r.to < viewStart || r.from > viewEnd) continue
        val clipped = SelectionRange(maxOf(r.from, viewStart), minOf(r.to, minOf(doc.length, viewEnd + 1)))
        for (rect in g.selectionRects(clipped, scrollY, scrollY + height)) selections += rect.translate(left, -scrollY)
    }
    // Text (a long line: only its pieces in view).
    val text = ArrayList<DrawnText>()
    val areaWidth = size.width - textLeft
    val cellW = g.layouts.charWidthPx
    for (l in lines) {
        val t = top(l)
        val lineY = g.lineTop(l)
        for ((o, layout) in g.visiblePieces(l, scrollX - 4 * cellW, scrollX + areaWidth, scrollY - lineY, scrollY + height - lineY)) {
            text += DrawnText(layout, Offset(left + o.x, t + o.y))
        }
    }
    // Cursors (drawn only while focused and in the blink's on phase: the draw pass decides).
    val cursors = ArrayList<Rect>()
    for (r in ranges) {
        if (r.head < viewStart || r.head > viewEnd) continue
        cursors += g.rectFor(r.head).translate(left, -scrollY)
    }
    // The gutter: right-aligned line numbers, the main cursor's line brighter.
    val numbers = ArrayList<DrawnText>()
    if (gutterWidth > 0f) {
        val active = doc.lineIndexAt(state.selection.main.head)
        val cw = g.layouts.charWidthPx
        for (l in lines) {
            val n = numberLayout(l + 1)
            val color = if (l == active) theme.gutterActiveForeground else theme.gutterForeground
            val y = top(l) + (g.layouts.lineHeightPx - n.size.height) / 2
            numbers += DrawnText(n, Offset(gutterWidth - cw - n.size.width, y), color)
        }
    }
    val spots = if (handles != TouchHandles.NONE) handleSpots() else emptyList()
    return SurfaceFrame(size, lines, scrollX, scrollY, gutterWidth, current, backgrounds, selections, text, cursors, numbers, spots, caret)
}

/**
 * The draw pass: [frame] as it is, in this order: background, the current line, `LineStyle`
 * backgrounds, selections (all ranges), text, cursors, the gutter, the touch handles. Nothing here
 * measures or scrolls ([DrawGuard]).
 */
internal fun DrawScope.drawFrame(frame: SurfaceFrame, theme: EditorTheme, focused: Boolean, cursorOn: Boolean) {
    drawRect(theme.background)
    if (frame.lines.isEmpty()) return
    val gutter = frame.gutterWidth
    clipRect(left = gutter) {
        for (r in frame.currentLines) drawRect(theme.currentLine, r.topLeft, r.size)
        for ((color, r) in frame.lineBackgrounds) drawRect(color, r.topLeft, r.size)
        for (r in frame.selections) drawRect(theme.selection, r.topLeft, r.size)
        for (t in frame.text) drawText(t.layout, topLeft = t.topLeft)
        if (focused && cursorOn) {
            val w = maxOf(2f, 1.5f * density)
            for (r in frame.cursors) drawRect(theme.cursor, Offset(r.left - w / 2, r.top), Size(w, r.height))
        }
    }
    if (gutter > 0f) {
        drawRect(theme.gutterBackground, Offset.Zero, Size(gutter, size.height))
        for (n in frame.numbers) drawText(n.layout, color = n.color, topLeft = n.topLeft)
    }
    // The touch handles, over everything (they hang below the text they mark).
    if (frame.handles.isNotEmpty()) drawHandles(frame.handles, theme.selectionHandle, density)
}
