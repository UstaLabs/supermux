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
    /** Squiggles (a lint diagnostic's underline): the style and the text rect it underlines. */
    val squiggles: List<Pair<SquiggleStyle, Rect>> = emptyList(),
) {
    companion object {
        fun empty(size: Size, scrollX: Float, scrollY: Float, gutterWidth: Float, caret: Rect) = SurfaceFrame(
            size, IntRange.EMPTY, scrollX, scrollY, gutterWidth, emptyList(), emptyList(), emptyList(),
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
    // The IME's composing text and the Mod-hovered word: underlined, on top of the state's own decorations.
    val extra = ArrayList<Ranged<Decoration>>(2)
    val composing = composition
    if (composing != null && !composing.isEmpty() && composing.last < doc.length) {
        extra += Ranged(composing.first, composing.last + 1, Decoration.Mark(setOf(EditorTheme.COMPOSITION_CLASS)))
    }
    val link = modLink
    if (link != null && !link.isEmpty() && link.last < doc.length) {
        extra += Ranged(link.first, link.last + 1, Decoration.Mark(setOf(EditorTheme.MOD_LINK_CLASS)))
    }
    g.extraMarks = if (extra.isEmpty()) null else RangeSet.of(extra)
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
    // Line decorations.
    val backgrounds = ArrayList<Pair<Color, Rect>>()
    run {
        for (set in state.facet(decorationsFacet)) for (r in set.between(viewStart, viewEnd)) {
            val v = r.value as? Decoration.LineStyle ?: continue
            // The active line falls back to the theme's currentLine when it has no class for it.
            val color = v.classes.firstNotNullOfOrNull { theme.lineClassBackgrounds[it] ?: if (it == EditorTheme.ACTIVE_LINE_CLASS) theme.currentLine else null } ?: continue
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
    // Squiggles: marks whose class the theme underlines (lint), per row of their text.
    val squiggles = ArrayList<Pair<SquiggleStyle, Rect>>()
    if (theme.squiggles.isNotEmpty()) {
        for (set in state.facet(decorationsFacet)) for (r in set.between(viewStart, viewEnd)) {
            val m = r.value as? Decoration.Mark ?: continue
            val style = m.classes.firstNotNullOfOrNull { theme.squiggles[it] } ?: continue
            val a = maxOf(r.from, viewStart)
            val b = minOf(r.to, viewEnd, doc.length)
            if (b <= a) continue
            for (rect in g.selectionRects(SelectionRange(a, b), scrollY, scrollY + height)) squiggles += style to rect.translate(left, -scrollY)
        }
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
            aboveY = g.heights.boxTop(e.line) - scrollY
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
    return SurfaceFrame(size, lines, scrollX, scrollY, gutterWidth, backgrounds, selections, text, cursors, numbers, spots, caret, markers, widgets, chips, glyph, squiggles)
}

/**
 * The draw pass: [frame] as it is, in this order: background, `LineStyle` (the active line, ...)
 * backgrounds, selections (all ranges), text, cursors, the gutter, the touch handles. Nothing here
 * measures or scrolls ([DrawGuard]).
 */
internal fun DrawScope.drawFrame(frame: SurfaceFrame, theme: EditorTheme, focused: Boolean, cursorOn: Boolean) {
    drawRect(theme.background)
    if (frame.lines.isEmpty()) return
    val gutter = frame.gutterWidth
    clipRect(left = gutter) {
        for ((color, r) in frame.lineBackgrounds) drawRect(color, r.topLeft, r.size)
        for (r in frame.selections) drawRect(theme.selection, r.topLeft, r.size)
        for (t in frame.text) drawText(t.layout, topLeft = t.topLeft)
        for ((style, r) in frame.squiggles) drawSquiggle(style, r)
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
}

/** The touch handles, drawn by an overlay above the widgets (a handle hangs below its line, over whatever is there). */
internal fun DrawScope.drawHandleLayer(frame: SurfaceFrame, theme: EditorTheme) {
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
        GutterMarkerShape.REVERT -> {
            // A hook: a half-circle open to the left, its arrowhead at the upper end pointing back.
            val s = unit * 0.26f
            val stroke = maxOf(1.5f, 1.6f * density)
            drawArc(style.color, startAngle = -90f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(c.x - s, c.y - s), size = Size(2 * s, 2 * s),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
            val head = Path().apply {
                moveTo(c.x - s * 0.9f, c.y - s)
                lineTo(c.x + s * 0.05f, c.y - s * 1.55f)
                lineTo(c.x + s * 0.05f, c.y - s * 0.45f)
                close()
            }
            drawPath(head, style.color)
        }
    }
}

/** A squiggle under [r]'s bottom: a wave two px high (one period per 4 dp), or dots. */
private fun DrawScope.drawSquiggle(style: SquiggleStyle, r: Rect) {
    val w = maxOf(r.width, 3f * density)
    val y = r.bottom - 2f * density
    if (style.dotted) {
        var x = r.left
        while (x < r.left + w) { drawCircle(style.color, radius = 0.8f * density, center = Offset(x + density, y + density)); x += 3f * density }
        return
    }
    val period = 4f * density
    val amp = 1.2f * density
    val p = Path().apply {
        moveTo(r.left, y + amp)
        var x = r.left
        var up = true
        while (x < r.left + w) {
            val nx = minOf(x + period / 2, r.left + w)
            lineTo(nx, if (up) y - amp else y + amp)
            up = !up
            x = nx
        }
    }
    drawPath(p, style.color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = maxOf(1f, 1f * density)))
}
