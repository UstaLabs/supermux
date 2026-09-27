package dev.supermux.terminal.compose

import androidx.compose.runtime.Immutable
import dev.supermux.terminal.TerminalSize
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The pixel size of one cell and where its baseline sits inside it.
 *
 * [width] and [height] are whole pixels on purpose: a fractional advance would drift across 200
 * columns and put glyphs in the wrong cells. [baseline] is measured from the top of the cell, and
 * every run is drawn against it, so cells never wobble vertically when a run happens to be taller
 * (an accent, a CJK glyph, an emoji).
 */
@Immutable
data class CellMetrics(
    val width: Float,
    val height: Float,
    val baseline: Float,
    /** Height of the measured line box; the cell may be taller ([TerminalTheme.lineHeightScale]). */
    val lineHeight: Float,
) {
    init {
        require(width > 0f && height > 0f) { "cell must be positive, got ${width}x$height" }
    }

    val widthPx: Int get() = max(1, width.roundToInt())
    val heightPx: Int get() = max(1, height.roundToInt())
}

/**
 * How many cells fit, and when to tell the engine about it.
 *
 * Rules, all of them deliberate:
 * - `columns = floor(availableWidth / cellWidth)`, `rows = floor(availableHeight / cellHeight)`.
 *   Flooring is the whole point: a partially visible column would make a program draw into pixels
 *   that are not there.
 * - A layout with room for less than one cell (a hidden pane, a 0x0 first measurement, a collapsed
 *   split) produces NO size at all — the session is never resized to zero and never resized twice
 *   just because a view was briefly offscreen.
 * - The result is clamped to the engine's limits: `1..TerminalSize.MAX_DIMENSION` per axis, and —
 *   because `columns * rows` must stay within [TerminalSize.MAX_CELLS] (100 000 cells, the codec's
 *   hard limit) — ROWS are reduced until the product fits. Columns win because they decide where
 *   every program wraps its output; losing scrollback rows off the bottom of an absurdly large
 *   window is the cheaper compromise. Both stay at least 1.
 * - [nextSize] coalesces: it returns null when the computed size equals the one already requested,
 *   so a rotation produces exactly one resize and a re-layout that changes nothing produces none.
 */
object TerminalGeometry {

    /** The grid that fits [widthPx] x [heightPx], or null when not even one cell does. */
    fun gridFor(widthPx: Float, heightPx: Float, cell: CellMetrics): TerminalGrid? {
        if (!widthPx.isFinite() || !heightPx.isFinite() || widthPx <= 0f || heightPx <= 0f) return null
        val columns = floor(widthPx / cell.width).toInt()
        val rows = floor(heightPx / cell.height).toInt()
        if (columns < 1 || rows < 1) return null
        return clamp(columns, rows)
    }

    /** [columns] x [rows] brought inside the engine's limits (see the class documentation). */
    fun clamp(columns: Int, rows: Int): TerminalGrid {
        val boundedColumns = columns.coerceIn(1, TerminalSize.MAX_DIMENSION)
        var boundedRows = rows.coerceIn(1, TerminalSize.MAX_DIMENSION)
        if (boundedColumns.toLong() * boundedRows > TerminalSize.MAX_CELLS) {
            boundedRows = max(1, TerminalSize.MAX_CELLS / boundedColumns)
        }
        return TerminalGrid(boundedColumns, boundedRows)
    }

    /**
     * The size to send to the session for a layout of [widthPx] x [heightPx], or null when there is
     * nothing to send — either the layout has no room for a cell, or the size is the one
     * [requested] already carries.
     *
     * The host forwards the accepted size to its transport (a pty `TIOCSWINSZ`, the broker's resize
     * message) from the frames it observes: every published frame carries `TerminalViewport.size`,
     * so the transport follows the engine rather than racing it.
     */
    fun nextSize(
        requested: TerminalSize?,
        widthPx: Float,
        heightPx: Float,
        cell: CellMetrics,
    ): TerminalSize? {
        val grid = gridFor(widthPx, heightPx, cell) ?: return null
        val size = TerminalSize(grid.columns, grid.rows, cell.widthPx, cell.heightPx)
        return if (size == requested) null else size
    }
}

/** A grid size in cells. */
@Immutable
data class TerminalGrid(val columns: Int, val rows: Int)
