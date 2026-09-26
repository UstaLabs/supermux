package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.decorationsFacet

/**
 * One frame, in this order: background, the current line (only while every range is a bare
 * cursor), `LineStyle` backgrounds, selections (all ranges), text, cursors, and the gutter.
 *
 * Only the visible lines plus [EditorDefaults.OVERSCAN_LINES] are laid out; laying them out records
 * their measured heights, so the visible range is computed again after measuring (a wrapped line
 * that turned out taller pushes the ones below it down in the same frame).
 */
internal fun DrawScope.paintEditor(c: EditorController, theme: EditorTheme, state: EditorState, focused: Boolean, cursorOn: Boolean) {
    val g = c.geometry
    val doc = state.doc
    // The IME's composing text: underlined, on top of the state's own decorations.
    val composing = c.composition
    g.extraMarks = if (composing == null || composing.isEmpty() || composing.last >= doc.length) null
    else RangeSet.of(listOf(Ranged(composing.first, composing.last + 1, Decoration.Mark(setOf(EditorTheme.COMPOSITION_CLASS)))))
    val height = size.height
    val overscan = EditorDefaults.OVERSCAN_LINES

    // Lay out what is visible, then look again: measured heights may have moved lines in or out
    // (and the anchor keeps the text on screen where it was).
    c.beginAnchor()
    c.scroll.clamp()
    val guess = g.visibleLines(c.scroll.y, height, overscan)
    for (l in guess) g.lineLayout(l)
    c.restoreAnchor()
    c.scroll.clamp()
    val lines = g.visibleLines(c.scroll.y, height, overscan)
    for (l in lines) if (l !in guess) g.lineLayout(l)
    c.drawnLines = lines
    c.recordAnchor()
    if (lines.isEmpty()) return drawRect(theme.background)

    val viewStart = doc.lineStart(lines.first)
    val viewEnd = if (lines.last + 1 < doc.lineCount) doc.lineStart(lines.last + 1) - 1 else doc.length
    c.view.publishViewport(viewStart until viewEnd)

    val scrollX = c.scroll.x
    val scrollY = c.scroll.y
    val left = c.textLeft - scrollX
    fun top(line: Int) = g.lineTop(line) - scrollY

    drawRect(theme.background)

    clipRect(left = c.gutterWidth) {
        val ranges = state.selection.ranges
        // The current line, only while nothing is selected.
        if (ranges.all { it.empty }) {
            var last = -1
            for (r in ranges) {
                val line = doc.lineIndexAt(r.head)
                if (line == last || line !in lines) continue
                last = line
                drawRect(theme.currentLine, Offset(c.gutterWidth, g.heights.top(line) - scrollY), Size(size.width, g.heights.height(line)))
            }
        }
        // Line decorations.
        if (theme.lineClassBackgrounds.isNotEmpty()) {
            for (set in state.facet(decorationsFacet)) for (r in set.between(viewStart, viewEnd)) {
                val v = r.value as? Decoration.LineStyle ?: continue
                val color = v.classes.firstNotNullOfOrNull { theme.lineClassBackgrounds[it] } ?: continue
                val line = doc.lineIndexAt(r.from)
                drawRect(color, Offset(c.gutterWidth, g.heights.top(line) - scrollY), Size(size.width, g.heights.height(line)))
            }
        }
        // Selections, clipped to the laid-out lines (a select-all never lays out the whole document).
        for (r in ranges) {
            if (r.empty || r.to < viewStart || r.from > viewEnd) continue
            val clipped = SelectionRange(maxOf(r.from, viewStart), minOf(r.to, minOf(doc.length, viewEnd + 1)))
            for (rect in g.selectionRects(clipped)) {
                drawRect(theme.selection, Offset(left + rect.left, rect.top - scrollY), Size(rect.width, rect.height))
            }
        }
        // Text.
        for (l in lines) drawText(g.lineLayout(l), topLeft = Offset(left, top(l)))
        // Cursors.
        if (focused && cursorOn) {
            val w = maxOf(2f, 1.5f * density)
            for (r in ranges) {
                if (r.head < viewStart || r.head > viewEnd) continue
                val rect = g.rectFor(r.head)
                drawRect(theme.cursor, Offset(left + rect.left - w / 2, rect.top - scrollY), Size(w, rect.height))
            }
        }
    }

    // The gutter: right-aligned line numbers, the main cursor's line brighter.
    if (c.gutterWidth > 0f) {
        drawRect(theme.gutterBackground, Offset.Zero, Size(c.gutterWidth, height))
        val active = doc.lineIndexAt(state.selection.main.head)
        val cw = g.layouts.charWidthPx
        for (l in lines) {
            val n = c.numberLayout(l + 1)
            val color = if (l == active) theme.gutterActiveForeground else theme.gutterForeground
            val y = top(l) + (g.layouts.lineHeightPx - n.size.height) / 2
            drawText(n, color = color, topLeft = Offset(c.gutterWidth - cw - n.size.width, y))
        }
    }
}
