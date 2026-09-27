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
import dev.supermux.editor.core.GutterMarker
import dev.supermux.editor.core.gutterMarkersFacet
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.decorationsFacet

/**
 * A gutter marker as drawn: its [column] cell on [line]'s first row ([rect], surface pixels), the
 * line's whole text height (a diff bar spans a wrapped line), and the theme's [style] (null: the
 * theme draws nothing for its kind).
 */
internal class DrawnMarker(val column: String, val line: Int, val marker: GutterMarker, val rect: Rect, val textHeight: Float, val style: GutterMarkerStyle?)

/** Subcomposes and measures a widget's content (a block's, or an inline one's); its size, null when it has none. */
internal typealias WidgetMeasurer = (key: dev.supermux.editor.core.WidgetKey, inline: Boolean, constraints: androidx.compose.ui.unit.Constraints) -> androidx.compose.ui.unit.IntSize?

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
    /** The gutter markers on the visible lines. */
    val markers: List<DrawnMarker> = emptyList(),
    /** The block widgets on the drawn lines, and the inline widgets in their rows. */
    val widgets: List<PlacedWidget> = emptyList(),
    /** The drawn placeholder chips (a fold's "⋯"), and the glyph they show. */
    val chips: List<DrawnChip> = emptyList(),
    val chipGlyph: TextLayoutResult? = null,
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
internal fun EditorController.buildFrame(state: EditorState, theme: EditorTheme, measureWidget: WidgetMeasurer?): SurfaceFrame {
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
    blocks.beginFrame()
    // Lay out what is visible (lines and block widgets), put the anchor back (measured heights
    // moved nothing on screen), and look again until nothing new comes into view.
    val measured = HashSet<Int>()
    fun measureRange(range: IntRange) {
        val shown = g.shownLines(range)
        measureInline(shown, measureWidget)
        for (l in shown) if (measured.add(l)) g.measure(l)
        if (measureWidgets(range, measureWidget)) syncBlocks(state)
    }
    var lines = g.visibleLines(scroll.y, height, overscan)
    measureRange(lines)
    // The lines the frame looks at off screen too (the caret the hidden field sits at, the touch
    // handles' ends): measured BEFORE the anchor is restored, so their real heights move nothing.
    val main = state.selection.main
    for (at in intArrayOf(main.head, main.from, main.to)) g.visualLine(doc.lineIndexAt(at)).let { if (measured.add(it)) g.measure(it) }
    restoreAnchor()
    scroll.clamp()
    for (pass in 0 until 4) {
        val next = g.visibleLines(scroll.y, height, overscan)
        if (next == lines) break
        lines = next
        measureRange(next)
        restoreAnchor()
        scroll.clamp()
    }
    lines = g.visibleLines(scroll.y, height, overscan)
    measureRange(lines)
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
    // The lines drawn: the laid-out range without a fold's hidden lines.
    val shown = g.shownLines(lines)
    fun textRow(line: Int) = Rect(gutterWidth, top(line), size.width, top(line) + g.textHeight(line))

    val ranges = state.selection.ranges
    // The current line, only while nothing is selected.
    val current = ArrayList<Rect>()
    if (ranges.all { it.empty }) {
        var last = -1
        for (r in ranges) {
            val line = g.visualLine(doc.lineIndexAt(r.head))
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
            val line = doc.lineIndexAt(r.from)
            if (g.folds.isHidden(line)) continue
            backgrounds += color to textRow(line)
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
    for (l in shown) {
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
    if (numbersRight > 0f) {
        val active = g.visualLine(doc.lineIndexAt(state.selection.main.head))
        val cw = g.layouts.charWidthPx
        for (l in shown) {
            val n = numberLayout(l + 1)
            val color = if (l == active) theme.gutterActiveForeground else theme.gutterForeground
            val y = top(l) + (g.layouts.lineHeightPx - n.size.height) / 2
            numbers += DrawnText(n, Offset(numbersRight - cw - n.size.width, y), color)
        }
    }
    // Marker columns: per line and column, the highest-precedence marker.
    val markers = ArrayList<DrawnMarker>()
    if (gutterColumns.isNotEmpty()) {
        val columns = gutterColumns.associateBy { it.id }
        val taken = HashSet<Pair<String, Int>>()
        for (set in state.facet(gutterMarkersFacet)) for (r in set.between(viewStart, viewEnd)) {
            val col = columns[r.value.column] ?: continue
            val line = doc.lineIndexAt(r.from)
            if (line !in lines || g.folds.isHidden(line) || !taken.add(col.id to line)) continue
            val t = top(line)
            markers += DrawnMarker(col.id, line, r.value, Rect(col.x, t, col.x + col.width, t + g.layouts.lineHeightPx), g.textHeight(line), theme.gutterMarkers[r.value.kind])
        }
    }
    val spots = if (handles != TouchHandles.NONE) handleSpots() else emptyList()
    // Block widgets on the drawn lines: above a line from its box's top, below it from its text's bottom.
    val widgets = ArrayList<PlacedWidget>()
    val lh = g.layouts.lineHeightPx
    var lastLine = -1
    var aboveY = 0f
    var belowY = 0f
    for (e in blocks.inLines(lines)) {
        if (g.folds.isHidden(e.line)) continue
        if (e.line != lastLine) {
            lastLine = e.line
            aboveY = g.heights.top(e.line) - scrollY
            belowY = top(e.line) + g.textHeight(e.line)
        }
        val h = blocks.heightOf(e, lh, registry)
        val y = if (e.above) aboveY.also { aboveY += h } else belowY.also { belowY += h }
        widgets += PlacedWidget(e.key, Rect(gutterWidth, y, size.width, y + h), blocks.measuredThisFrame(e.key))
    }
    // Inline widgets and placeholder chips, where their characters are in their rows.
    val chips = ArrayList<DrawnChip>()
    if (!g.folds.isEmpty) for (l in shown) {
        if (g.isLong(l)) continue
        val row = g.row(l)
        val map = row.map ?: continue
        for ((part, k) in map.widgetChars()) {
            if (k < 0) continue
            val key = part.widget ?: continue
            val box = row.layout.getBoundingBox(k)
            val r = row.layout.getLineForOffset(k)
            val y0 = top(l) + row.layout.getLineTop(r)
            val rect = Rect(left + box.left, y0, left + box.right, y0 + row.layout.getLineBottom(r) - row.layout.getLineTop(r))
            if (registry?.contains(key.type) == true) widgets += PlacedWidget(key, rect, inlineMeasuredThisFrame(key), inline = true)
            else chips += DrawnChip(key, part.from, part.to, rect)
        }
    }
    val glyph = if (chips.isNotEmpty()) chipGlyph() else null
    return SurfaceFrame(size, lines, scrollX, scrollY, gutterWidth, current, backgrounds, selections, text, cursors, numbers, spots, caret, markers, widgets, chips, glyph)
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
        val glyph = frame.chipGlyph
        for (c in frame.chips) {
            val r = c.rect.deflate(minOf(1.5f * density, c.rect.width / 8))
            drawRoundRect(theme.widgetChipBackground, r.topLeft, r.size, androidx.compose.ui.geometry.CornerRadius(r.height / 4))
            if (glyph != null) drawText(glyph, color = theme.widgetChipForeground, topLeft = Offset(r.center.x - glyph.size.width / 2f, r.center.y - glyph.size.height / 2f))
        }
        if (focused && cursorOn) {
            val w = maxOf(2f, 1.5f * density)
            for (r in frame.cursors) drawRect(theme.cursor, Offset(r.left - w / 2, r.top), Size(w, r.height))
        }
    }
    if (gutter > 0f) {
        drawRect(theme.gutterBackground, Offset.Zero, Size(gutter, size.height))
        for (n in frame.numbers) drawText(n.layout, color = n.color, topLeft = n.topLeft)
        for (m in frame.markers) m.style?.let { drawMarker(m, it) }
    }
    // The touch handles, over everything (they hang below the text they mark).
    if (frame.handles.isNotEmpty()) drawHandles(frame.handles, theme.selectionHandle, density)
}

/** One marker in its cell: a bar the line's height, a dot, a speech bubble, a fold arrow. */
private fun DrawScope.drawMarker(m: DrawnMarker, style: GutterMarkerStyle) {
    val r = m.rect
    val c = r.center
    val unit = minOf(r.width, r.height)
    when (style.shape) {
        GutterMarkerShape.BAR -> {
            val w = maxOf(2f, 3f * density).coerceAtMost(r.width)
            drawRect(style.color, Offset(c.x - w / 2, r.top), Size(w, m.textHeight))
        }
        GutterMarkerShape.DOT -> drawCircle(style.color, radius = unit * 0.28f, center = c)
        GutterMarkerShape.BUBBLE -> {
            val w = unit * 0.8f
            val h = unit * 0.58f
            val left = c.x - w / 2
            val top = c.y - h * 0.62f
            drawRoundRect(style.color, Offset(left, top), Size(w, h), CornerRadius(h * 0.3f))
            val tail = Path().apply {
                moveTo(left + w * 0.22f, top + h - 1f)
                lineTo(left + w * 0.22f, top + h + h * 0.38f)
                lineTo(left + w * 0.5f, top + h - 1f)
                close()
            }
            drawPath(tail, style.color)
        }
        GutterMarkerShape.OPEN, GutterMarkerShape.CLOSED -> {
            val s = unit * 0.24f
            val p = Path().apply {
                if (style.shape == GutterMarkerShape.OPEN) {
                    moveTo(c.x - s, c.y - s * 0.55f); lineTo(c.x + s, c.y - s * 0.55f); lineTo(c.x, c.y + s * 0.65f)
                } else {
                    moveTo(c.x - s * 0.55f, c.y - s); lineTo(c.x - s * 0.55f, c.y + s); lineTo(c.x + s * 0.65f, c.y)
                }
                close()
            }
            drawPath(p, style.color)
        }
    }
}
