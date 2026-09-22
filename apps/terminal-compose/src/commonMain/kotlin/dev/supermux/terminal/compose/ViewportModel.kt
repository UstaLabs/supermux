package dev.supermux.terminal.compose

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.terminal.TerminalCell
import dev.supermux.terminal.TerminalCursor
import dev.supermux.terminal.TerminalLink
import dev.supermux.terminal.TerminalModes
import dev.supermux.terminal.TerminalRow
import dev.supermux.terminal.TerminalSelection
import dev.supermux.terminal.TerminalSize
import dev.supermux.terminal.TerminalViewport

/**
 * One complete, immutable screen: every row is present, `rows[i].index == i`, and nothing in here
 * is ever mutated after it is built. The painter and the semantics both read this and only this —
 * no draw pass ever reaches back into the engine.
 *
 * [frameNumber] increases on every applied update (it is this package's frame generation, NOT the
 * engine's [generation]) and [epoch] increases on every [ViewportModel.reset]: together they name a
 * screen uniquely even across a reset that restarts the engine's generations from zero.
 */
@Immutable
data class TerminalFrame(
    val frameNumber: Long,
    val epoch: Long,
    val generation: Long,
    val size: TerminalSize,
    val rows: List<TerminalRow>,
    val cursor: TerminalCursor,
    val modes: TerminalModes,
    val historyRows: Long,
    val viewportTop: Long,
    val links: List<TerminalLink>,
    val selection: TerminalSelection?,
    val held: Boolean,
) {
    /** The cell at ([row], [column]) in viewport coordinates, or null when there is none. */
    fun cellAt(row: Int, column: Int): TerminalCell? =
        rows.getOrNull(row)?.cells?.getOrNull(column)

    /**
     * The plain text of one viewport row: every cell's text in column order. Width-0 continuation
     * cells contribute nothing, so the string is exactly the printable content of the row and never
     * doubles a wide glyph.
     */
    fun rowText(row: Int): String {
        val cells = rows.getOrNull(row)?.cells ?: return ""
        val text = StringBuilder(cells.size)
        for (cell in cells) if (cell.width != 0) text.append(cell.text.ifEmpty { " " })
        return text.toString().trimEnd()
    }

    /** Every row's [rowText], newest geometry first — the a11y/text view of the screen. */
    fun plainText(): String = (0 until size.rows).joinToString("\n") { rowText(it) }
}

/** Why [ViewportModel.apply] refused an update. */
enum class ViewportRejection {
    /** There is nothing to patch (no full frame in this epoch yet) or the grid changed size. */
    NEEDS_FULL,

    /** The update is older than (or the same as) the frame already applied. */
    STALE_GENERATION,

    /** The update describes a grid it cannot fill: a row outside the grid, a row too wide, a gap. */
    IMPOSSIBLE_GEOMETRY,
}

/** Outcome of [ViewportModel.apply]. */
sealed interface ViewportUpdate {
    data class Applied(val frame: TerminalFrame) : ViewportUpdate

    /** [diagnostic] is for logs and test failures; it never reaches the screen. */
    data class Rejected(val reason: ViewportRejection, val diagnostic: String) : ViewportUpdate
}

/**
 * The reducer between `TerminalSession.viewports` and the painter.
 *
 * The session publishes a FULL frame first and PARTIAL ones after it (only the rows that changed
 * since the last acknowledged frame), so somebody has to keep the rows. This is that somebody: it
 * applies updates in order, replaces only the rows an update names, and refuses — with a diagnostic
 * instead of an exception or an out-of-range index — anything it cannot place.
 *
 * Not thread-safe and not meant to be: [apply] and [reset] run on the UI owner (the composable's
 * collector), which is also where [frame] is read from during layout and draw. [frame] is snapshot
 * state, so writing it invalidates exactly the draw pass that reads it.
 */
class ViewportModel {
    /** The current screen, or null before the first full frame of this [epoch] arrives. */
    var frame: TerminalFrame? by mutableStateOf(null)
        private set

    /** Increases on every [reset]; the namespace that makes a generation meaningful. */
    var epoch: Long = 0L
        private set

    private var frames = 0L

    /**
     * Fold [update] into the current screen.
     *
     * - a FULL update replaces every row (and is accepted even when its generation repeats the
     *   current one — the engine re-serializes the same generation when a renderer asks for a full
     *   frame without anything having changed),
     * - a PARTIAL update replaces only the rows it names and needs a current screen of the SAME
     *   size and a strictly newer generation,
     * - anything that cannot be placed is [ViewportUpdate.Rejected]; the previous screen stays.
     */
    fun apply(update: TerminalViewport): ViewportUpdate {
        val current = frame
        val columns = update.size.columns
        val rowCount = update.size.rows

        for (row in update.rows) {
            if (row.index !in 0 until rowCount) {
                return reject(
                    ViewportRejection.IMPOSSIBLE_GEOMETRY,
                    "row ${row.index} is outside a ${columns}x$rowCount grid",
                )
            }
            if (row.cells.size > columns) {
                return reject(
                    ViewportRejection.IMPOSSIBLE_GEOMETRY,
                    "row ${row.index} carries ${row.cells.size} cells in a $columns-column grid",
                )
            }
        }

        val rows: List<TerminalRow>
        if (update.full) {
            if (current != null && update.generation < current.generation) {
                return reject(
                    ViewportRejection.STALE_GENERATION,
                    "full generation ${update.generation} is older than ${current.generation}",
                )
            }
            val placed = arrayOfNulls<TerminalRow>(rowCount)
            for (row in update.rows) placed[row.index] = row
            val missing = placed.indexOfFirst { it == null }
            if (missing >= 0) {
                return reject(
                    ViewportRejection.IMPOSSIBLE_GEOMETRY,
                    "full frame ${update.generation} does not carry row $missing of $rowCount",
                )
            }
            @Suppress("UNCHECKED_CAST")
            rows = (placed as Array<TerminalRow>).asList()
        } else {
            if (current == null) {
                return reject(ViewportRejection.NEEDS_FULL, "no full frame in epoch $epoch yet")
            }
            if (update.size != current.size) {
                return reject(
                    ViewportRejection.NEEDS_FULL,
                    "grid changed from ${current.size.columns}x${current.size.rows} to ${columns}x$rowCount",
                )
            }
            if (update.generation <= current.generation) {
                return reject(
                    ViewportRejection.STALE_GENERATION,
                    "generation ${update.generation} is not newer than ${current.generation}",
                )
            }
            val patched = current.rows.toMutableList()
            for (row in update.rows) patched[row.index] = row
            rows = patched
        }

        val next = TerminalFrame(
            frameNumber = frames++,
            epoch = epoch,
            generation = update.generation,
            size = update.size,
            rows = rows,
            cursor = update.cursor,
            modes = update.modes,
            historyRows = update.historyRows,
            viewportTop = update.viewportTop,
            links = update.links,
            selection = update.selection,
            held = update.held,
        )
        frame = next
        return ViewportUpdate.Applied(next)
    }

    /**
     * Forget the screen and start a new generation namespace.
     *
     * Used when the session is reset (RIS) or when this surface is bound to a different session:
     * the next update must be FULL, so no cell of the previous screen can ever be shown again — and
     * a generation counter that restarts at zero is not mistaken for an out-of-order update.
     */
    fun reset() {
        frame = null
        epoch++
    }

    private fun reject(reason: ViewportRejection, diagnostic: String) =
        ViewportUpdate.Rejected(reason, diagnostic)
}
