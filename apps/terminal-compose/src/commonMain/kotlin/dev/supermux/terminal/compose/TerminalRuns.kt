package dev.supermux.terminal.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.supermux.terminal.CellFlags
import dev.supermux.terminal.TerminalCell
import dev.supermux.terminal.Underline

/**
 * A cell's style after the theme has been applied: no [dev.supermux.terminal.TerminalColor.DEFAULT],
 * no inverse to undo, no palette index left to look up. The painter draws exactly what is in here.
 */
@Immutable
data class ResolvedStyle(
    val foreground: Color,
    val background: Color,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Int = Underline.NONE,
    val strikethrough: Boolean = false,
    val overline: Boolean = false,
    /** SGR 8: the cell keeps its background and its decorations but draws no glyph. */
    val invisible: Boolean = false,
)

/** A stretch of columns on one row filled with one colour. */
@Immutable
data class BackgroundRun(val row: Int, val column: Int, val columns: Int, val color: Color)

/**
 * A stretch of columns on one row drawn with one text layout. [columns] is the TERMINAL advance the
 * run occupies — never the width the font would like — so `columns` cells starting at [column] are
 * this run's and nothing else's.
 */
@Immutable
data class TextRun(
    val row: Int,
    val column: Int,
    val columns: Int,
    val text: String,
    val style: ResolvedStyle,
    /** True when the run is plain ASCII, one char per cell: it can be split per cell if it misfits. */
    val monospaced: Boolean,
)

/** Underline / strikethrough / overline over a stretch of columns. */
@Immutable
data class DecorationRun(
    val row: Int,
    val column: Int,
    val columns: Int,
    val underline: Int,
    val strikethrough: Boolean,
    val overline: Boolean,
    val color: Color,
)

/**
 * One frame turned into draw work, in painting order: [backgrounds], then [selections] on top of
 * them, then [texts], then [decorations]. The cursor is drawn last and is not a run (it needs the
 * cell under it).
 *
 * Why selection sits with the backgrounds rather than after the text: a selection colour is usually
 * translucent, and painting it OVER the glyphs tints them instead of highlighting them. Painting it
 * over the cell background and under the text gives the identical result for an opaque colour and
 * the readable one for a translucent one.
 */
@Immutable
data class FrameRuns(
    val backgrounds: List<BackgroundRun>,
    val selections: List<BackgroundRun>,
    val texts: List<TextRun>,
    val decorations: List<DecorationRun>,
)

/**
 * Turns a [TerminalFrame] into [FrameRuns]. Pure and free of Compose geometry on purpose: what
 * lands in which cell is decided here and asserted in tests that no font can influence.
 *
 * Batching rule: adjacent cells merge into ONE text run only when every one of them is a single
 * printable ASCII character in a narrow (width 1) cell with the same resolved style. Everything
 * else — wide cells, combining sequences, emoji, anything outside ASCII — becomes a run of its own
 * spanning exactly the cells the terminal gave it. That keeps the common case (a line of code) at
 * one layout per style while making it impossible for a font's own advances to shift a column.
 *
 * Width-0 cells (the continuation of a wide glyph, a wrap spacer) never produce text: their glyph
 * was already drawn by the leading cell. They DO take part in background and decoration runs, which
 * is what makes a wide glyph's background and underline cover both of its cells.
 */
object TerminalRuns {

    fun build(frame: TerminalFrame, theme: TerminalTheme): FrameRuns {
        val backgrounds = mutableListOf<BackgroundRun>()
        val selections = mutableListOf<BackgroundRun>()
        val texts = mutableListOf<TextRun>()
        val decorations = mutableListOf<DecorationRun>()

        for (row in 0 until frame.size.rows) {
            val cells = frame.rows.getOrNull(row)?.cells ?: continue
            val selected = selectedColumns(frame, row)
            buildBackgrounds(row, cells, theme, backgrounds)
            if (selected != null) {
                val first = selected.first.coerceIn(0, frame.size.columns - 1)
                val last = selected.last.coerceIn(0, frame.size.columns - 1)
                if (last >= first) {
                    selections += BackgroundRun(row, first, last - first + 1, theme.selectionBackground)
                }
            }
            buildTexts(row, cells, theme, selected, texts)
            buildDecorations(row, cells, theme, selected, decorations)
        }
        return FrameRuns(backgrounds, selections, texts, decorations)
    }

    /**
     * The columns of viewport row [row] covered by the frame's selection, or null. The selection is
     * linear and its ends are ABSOLUTE rows (history included), so it is mapped through
     * [TerminalFrame.viewportTop] and clipped to the visible rows.
     */
    fun selectedColumns(frame: TerminalFrame, row: Int): IntRange? {
        val selection = frame.selection ?: return null
        val a = selection.start
        val b = selection.end
        val startsFirst = a.row < b.row || (a.row == b.row && a.column <= b.column)
        val start = if (startsFirst) a else b
        val end = if (startsFirst) b else a
        val absolute = frame.viewportTop + row
        if (absolute < start.row || absolute > end.row) return null
        val first = if (absolute == start.row) start.column else 0
        val last = if (absolute == end.row) end.column else frame.size.columns - 1
        if (last < first) return null
        return first..last
    }

    private fun buildBackgrounds(
        row: Int,
        cells: List<TerminalCell>,
        theme: TerminalTheme,
        out: MutableList<BackgroundRun>,
    ) {
        var runStart = -1
        var runColor = Color.Unspecified
        for (column in cells.indices) {
            val color = resolve(cells[column], theme).background
            // The surface already painted the default background; only deviations cost a rect.
            val paint = color != theme.background
            if (runStart >= 0 && (!paint || color != runColor)) {
                out += BackgroundRun(row, runStart, column - runStart, runColor)
                runStart = -1
            }
            if (paint && runStart < 0) {
                runStart = column
                runColor = color
            }
        }
        if (runStart >= 0) out += BackgroundRun(row, runStart, cells.size - runStart, runColor)
    }

    private fun buildTexts(
        row: Int,
        cells: List<TerminalCell>,
        theme: TerminalTheme,
        selected: IntRange?,
        out: MutableList<TextRun>,
    ) {
        val batch = StringBuilder()
        var batchStart = -1
        var batchStyle: ResolvedStyle? = null

        fun flush(endExclusive: Int) {
            val style = batchStyle
            if (batchStart < 0 || style == null) return
            // Leading/trailing spaces draw nothing; dropping them keeps the measured run tight
            // without moving a single column (the run's own start moves with it).
            var first = 0
            var last = batch.length
            while (first < last && batch[first] == ' ') first++
            while (last > first && batch[last - 1] == ' ') last--
            if (last > first) {
                out += TextRun(
                    row = row,
                    column = batchStart + first,
                    columns = last - first,
                    text = batch.substring(first, last),
                    style = style,
                    monospaced = true,
                )
            }
            batch.clear()
            batchStart = -1
            batchStyle = null
            check(endExclusive >= 0)
        }

        for (column in cells.indices) {
            val cell = cells[column]
            if (cell.width == 0) {
                flush(column)
                continue
            }
            val style = resolve(cell, theme, selected?.contains(column) == true)
            val text = cell.text
            if (style.invisible || text.isEmpty()) {
                flush(column)
                continue
            }
            if (cell.width == 1 && text.length == 1 && text[0] in ASCII_FIRST..ASCII_LAST) {
                if (batchStyle != null && batchStyle != style) flush(column)
                if (batchStart < 0) {
                    batchStart = column
                    batchStyle = style
                }
                batch.append(text)
                continue
            }
            flush(column)
            out += TextRun(row, column, cell.width, text, style, monospaced = false)
        }
        flush(cells.size)
    }

    private fun buildDecorations(
        row: Int,
        cells: List<TerminalCell>,
        theme: TerminalTheme,
        selected: IntRange?,
        out: MutableList<DecorationRun>,
    ) {
        var runStart = -1
        var run: DecorationRun? = null
        for (column in cells.indices) {
            val style = resolve(cells[column], theme, selected?.contains(column) == true)
            val decorated = style.underline != Underline.NONE || style.strikethrough || style.overline
            val here = if (decorated) {
                DecorationRun(row, column, 1, style.underline, style.strikethrough, style.overline, style.foreground)
            } else {
                null
            }
            val previous = run
            val compatible = here != null && previous != null &&
                here.underline == previous.underline &&
                here.strikethrough == previous.strikethrough &&
                here.overline == previous.overline &&
                here.color == previous.color
            if (previous != null && !compatible) {
                out += previous.copy(column = runStart, columns = column - runStart)
                run = null
                runStart = -1
            }
            if (here != null && run == null) {
                run = here
                runStart = column
            }
        }
        run?.let { out += it.copy(column = runStart, columns = cells.size - runStart) }
    }

    /**
     * The cell's style with the theme applied: defaults substituted, inverse swapped, faint
     * dimmed, selection folded in.
     *
     * Bold brightening ([TerminalTheme.boldBrightensAnsi]) can only work by VALUE — the engine
     * already resolved palette indices to RGBA — so a bold cell whose colour is exactly one of the
     * theme's ANSI 0–7 is drawn with the matching 8–15. A cell that merely happens to carry the
     * same RGBA from a 24-bit escape is brightened too; that is the price of resolving in the
     * engine, and it is why the option is off by default.
     */
    fun resolve(cell: TerminalCell, theme: TerminalTheme, selected: Boolean = false): ResolvedStyle {
        val flags = cell.style.flags
        val bold = flags and CellFlags.BOLD != 0
        var foreground = engineColorToCompose(cell.style.foreground, theme.foreground)
        var background = engineColorToCompose(cell.style.background, theme.background)
        if (bold && theme.boldBrightensAnsi) {
            val index = theme.ansi.indexOf(foreground)
            if (index in 0..7) foreground = theme.ansi[index + 8]
        }
        if (flags and CellFlags.INVERSE != 0) {
            val swap = foreground
            foreground = background
            background = swap
        }
        if (flags and CellFlags.FAINT != 0) {
            foreground = foreground.copy(alpha = foreground.alpha * theme.faintAlpha)
        }
        if (selected) {
            theme.selectionForeground?.let { foreground = it }
        }
        return ResolvedStyle(
            foreground = foreground,
            background = background,
            bold = bold,
            italic = flags and CellFlags.ITALIC != 0,
            underline = cell.style.underline,
            strikethrough = flags and CellFlags.STRIKETHROUGH != 0,
            overline = flags and CellFlags.OVERLINE != 0,
            invisible = flags and CellFlags.INVISIBLE != 0,
        )
    }

    private const val ASCII_FIRST = ' '
    private const val ASCII_LAST = '~'
}
