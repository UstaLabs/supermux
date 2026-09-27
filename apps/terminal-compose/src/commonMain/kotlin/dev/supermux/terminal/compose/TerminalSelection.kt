package dev.supermux.terminal.compose

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.terminal.TerminalPoint
import dev.supermux.terminal.TerminalSelection
import dev.supermux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Which end of a selection a touch handle moves. */
enum class SelectionHandle { START, END }

/** A touch handle's anchor point, in the surface's own pixels. */
@Immutable
data class SelectionHandleSpot(val handle: SelectionHandle, val position: Offset)

/**
 * The user's selection: anchors in the ENGINE's absolute row space, never in pixels.
 *
 * **Why the engine holds it.** `session.select(...)` puts the selection in the terminal, and every
 * published frame carries it back in [dev.supermux.terminal.TerminalViewport.selection]. That is not
 * a detour — it is the only way the selection survives the two things that move text under it:
 *
 * - **New output.** Absolute rows do not move when the program prints, so the selected TEXT does not
 *   change while its rows are retained. Nothing here has to re-derive anything per frame.
 * - **Eviction.** Ghostty drops whole history pages oldest-first and renumbers every surviving row.
 *   The ENGINE's selection follows its rows across that renumbering; a row number this class held
 *   would not (see `native/README.md`). So the anchor is corrected from the engine's own selection
 *   on every frame ([onFrame]) — and when eviction takes the selection outright, the engine reports
 *   none and this class forgets its anchor instead of resurrecting a dead row.
 *
 * **What it draws.** Nothing: [TerminalRuns] already builds the selection runs from the frame, which
 * is what makes the highlight correct even though a selection change never marks a row dirty. This
 * class only decides WHICH cells are selected and where the touch handles sit.
 *
 * **What it sends.** Nothing, ever, to the program. A selection is a local gesture; [copy] asks the
 * ENGINE for the text (`selectedText()`), which is what makes soft wraps, hard wraps and grapheme
 * clusters come out right — the surface never re-assembles text from cells.
 */
@Stable
internal class TerminalSelectionController(
    private val session: TerminalSession,
    private val model: ViewportModel,
    private val scroll: ScrollController,
    private val scope: CoroutineScope,
) {
    /** True while a drag (or a handle drag) is building the selection. */
    var dragging: Boolean by mutableStateOf(false)
        private set

    /** The handle being dragged, or null for a plain drag. */
    var draggingHandle: SelectionHandle? by mutableStateOf(null)
        private set

    /** The end that stays put for the current gesture, in absolute coordinates. */
    private var anchor: TerminalPoint? = null

    /** The selection this controller last asked for; [onFrame] compares the engine's against it. */
    private var lastSent: TerminalSelection? = null

    /** Where the pointer is, for edge autoscroll to keep extending from. */
    private var pointer: Offset = Offset.Zero
    private var autoScrollPxPerSecond = 0f
    private var autoScroll: Job? = null

    /** True when there is something to copy. */
    val hasSelection: Boolean get() = model.frame?.selection != null

    // ----------------------------------------------------------------- gestures ----

    /** Start a selection at [cell] (a mouse press, or the cell a touch handle is pulled from). */
    fun begin(cell: TerminalCellPosition) {
        val frame = model.frame ?: return
        val point = pointAt(frame, cell, toEnd = false)
        anchor = point
        dragging = true
        draggingHandle = null
        send(point, point)
    }

    /**
     * Select the WORD under [cell] — what a long press and a double click do.
     *
     * A press that selects one cell gives the user two touch handles on top of each other; a word
     * gives them something to pull apart, which is the whole point of the gesture on a phone.
     */
    fun selectWord(cell: TerminalCellPosition) {
        val frame = model.frame ?: return
        val span = wordSpanAt(frame, cell.row, cell.column) ?: return
        val row = frame.viewportTop + cell.row
        anchor = TerminalPoint(row, span.first)
        dragging = false
        draggingHandle = null
        send(TerminalPoint(row, span.first), TerminalPoint(row, span.last))
    }

    /**
     * Start dragging one [handle] of the existing selection; the OTHER end becomes the anchor.
     *
     * Returns false when there is no selection to take a handle from.
     */
    fun beginHandle(handle: SelectionHandle): Boolean {
        val selection = model.frame?.selection ?: return false
        val ordered = selection.ordered()
        anchor = if (handle == SelectionHandle.START) ordered.end else ordered.start
        dragging = true
        draggingHandle = handle
        return true
    }

    /** Move the moving end to [cell]. */
    fun extendTo(cell: TerminalCellPosition) {
        val frame = model.frame ?: return
        val fixed = anchor ?: return
        // The moving end is the one that decides which way the span runs, so it is snapped to the
        // side of a wide glyph that keeps the glyph inside the selection.
        val row = frame.viewportTop + cell.row
        val toEnd = row > fixed.row || (row == fixed.row && cell.column >= columnOf(fixed, frame))
        send(fixed, pointAt(frame, cell, toEnd = toEnd))
    }

    /** The gesture ended; the selection stays, the anchor stops moving. */
    fun finish() {
        dragging = false
        draggingHandle = null
        stopAutoScroll()
    }

    /** Drop the selection (a plain click, a key press, focus leaving). */
    fun clear() {
        if (anchor == null && lastSent == null && model.frame?.selection == null) return
        anchor = null
        lastSent = null
        stopAutoScroll()
        dragging = false
        draggingHandle = null
        session.select(null)
    }

    // ----------------------------------------------------------------- edge autoscroll ----

    /**
     * The pointer moved to [position] inside a surface [heightPx] tall while dragging.
     *
     * Within [AUTOSCROLL_MARGIN] of an edge the history walks by itself, at a speed that grows with
     * how far past the margin the finger is — the standard "drag to the edge to keep selecting"
     * behaviour, and the only way to select more than one screen on a phone.
     */
    fun onDragPosition(position: Offset, heightPx: Float, metrics: CellMetrics) {
        pointer = position
        if (!dragging || heightPx <= 0f) {
            stopAutoScroll()
            return
        }
        val margin = AUTOSCROLL_MARGIN_CELLS * metrics.height
        val over = when {
            position.y < margin -> position.y - margin
            position.y > heightPx - margin -> position.y - (heightPx - margin)
            else -> 0f
        }
        if (over == 0f || margin <= 0f) {
            stopAutoScroll()
            return
        }
        // Positive scroll deltas move toward newer output; dragging past the BOTTOM edge does that.
        val fraction = (abs(over) / margin).coerceIn(0f, 1f)
        autoScrollPxPerSecond =
            (if (over > 0f) 1f else -1f) * fraction * AUTOSCROLL_ROWS_PER_SECOND * metrics.height
        startAutoScroll(metrics)
    }

    private fun startAutoScroll(metrics: CellMetrics) {
        if (autoScroll?.isActive == true) return
        autoScroll = scope.launch {
            var previous = 0L
            while (dragging && autoScrollPxPerSecond != 0f) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                val elapsed = if (previous == 0L) 0L else now - previous
                previous = now
                if (elapsed > 0L) {
                    val seconds = elapsed / 1_000_000_000.0
                    // Straight into the controller, not through the scrollable's mutex: a finger is
                    // already down, so there is no fling to interrupt and nothing else is scrolling.
                    scroll.consumePx((autoScrollPxPerSecond * seconds).toFloat())
                }
                // The rows moved under a pointer that did not: re-resolve the cell it is over.
                cellUnderPointer(metrics)?.let(::extendTo)
            }
        }
    }

    private fun stopAutoScroll() {
        autoScrollPxPerSecond = 0f
        autoScroll?.cancel()
        autoScroll = null
    }

    private fun cellUnderPointer(metrics: CellMetrics): TerminalCellPosition? {
        val frame = model.frame ?: return null
        return cellAt(
            position = pointer,
            metrics = metrics,
            scrollOffsetPx = scroll.paintOffset(frame),
            columns = frame.size.columns,
            rows = frame.size.rows,
        )
    }

    // ----------------------------------------------------------------- engine feedback ----

    /**
     * Correct the anchor against the selection the ENGINE reports.
     *
     * Eviction renumbers every retained row, and the engine's selection moves with its text while a
     * number held here does not. Both ends shift by the same amount, so the shift is recoverable by
     * comparing what the engine reports with what was last asked for — in either order, because
     * nothing guarantees the engine hands the ends back the way they went in.
     *
     * A selection the engine no longer has (eviction took every selected row) forgets the anchor:
     * the highlight disappears — which is the visible part — and a later drag starts fresh instead
     * of extending from a row that no longer exists.
     */
    fun onFrame(frame: TerminalFrame) {
        val sent = lastSent ?: return
        val live = frame.selection
        if (live == null) {
            anchor = null
            lastSent = null
            return
        }
        val drift = driftBetween(sent, live)
        if (drift != null && drift != 0L) {
            anchor = anchor?.let { TerminalPoint((it.row + drift).coerceAtLeast(0), it.column) }
        }
        lastSent = live
    }

    // ----------------------------------------------------------------- clipboard ----

    /**
     * Copy the selection to [clipboard], through the ENGINE's `selectedText()`.
     *
     * The engine is what knows whether a line break between two rows is a SOFT wrap (one logical
     * line, no newline in the copy) or a hard one, and what a grapheme cluster is. Re-assembling the
     * text from the cells this surface drew would get both wrong.
     */
    fun copy(clipboard: TerminalClipboard, onResult: (Boolean) -> Unit = {}) {
        if (model.frame?.selection == null) {
            onResult(false)
            return
        }
        scope.launch {
            val text = runCatching { session.selectedText() }.getOrDefault("")
            if (text.isEmpty()) {
                onResult(false)
                return@launch
            }
            val written = runCatching { clipboard.write(text) }.isSuccess
            onResult(written)
        }
    }

    // ----------------------------------------------------------------- internals ----

    private fun send(start: TerminalPoint, end: TerminalPoint) {
        val selection = TerminalSelection(start, end)
        lastSent = selection
        session.select(selection)
    }

    private fun columnOf(point: TerminalPoint, frame: TerminalFrame): Int = point.column

    /** [cell] as an absolute point, with a wide glyph's continuation cells kept inside the span. */
    private fun pointAt(frame: TerminalFrame, cell: TerminalCellPosition, toEnd: Boolean): TerminalPoint =
        TerminalPoint(frame.viewportTop + cell.row, snapColumn(frame, cell.row, cell.column, toEnd))

    private companion object {
        /** How close to an edge a drag has to get before the history starts walking. */
        val AUTOSCROLL_MARGIN: Dp = 24.dp

        /** The margin, in cells, so it scales with the font rather than with the screen. */
        const val AUTOSCROLL_MARGIN_CELLS = 1.5f

        /** Top speed of the edge autoscroll, in rows per second. */
        const val AUTOSCROLL_ROWS_PER_SECOND = 12f
    }
}

/** This selection with [TerminalSelection.start] before [TerminalSelection.end]. */
internal fun TerminalSelection.ordered(): TerminalSelection {
    val startsFirst = start.row < end.row || (start.row == end.row && start.column <= end.column)
    return if (startsFirst) this else TerminalSelection(end, start)
}

/**
 * How far [live] moved from [sent] because history was evicted, or null when the two are not the
 * same selection renumbered (the user moved an end, the engine clamped it, something else happened).
 *
 * Eviction shifts EVERY retained row by the same amount, so a drift is only believable when both
 * ends moved by it and neither column changed. The ends are compared in both orders because the
 * engine may hand a selection back the other way round.
 */
internal fun driftBetween(sent: TerminalSelection, live: TerminalSelection): Long? {
    if (live.start.column == sent.start.column && live.end.column == sent.end.column) {
        val head = live.start.row - sent.start.row
        if (head == live.end.row - sent.end.row) return head
    }
    if (live.start.column == sent.end.column && live.end.column == sent.start.column) {
        val head = live.start.row - sent.end.row
        if (head == live.end.row - sent.start.row) return head
    }
    return null
}

/**
 * [column] moved onto the cell that keeps a wide glyph whole: its LEADING cell for the start of a
 * selection, its last continuation cell for the end.
 *
 * A width-0 cell is the continuation of the wide cell to its left (or a wrap spacer), so a click on
 * the right half of a 全角 character must not cut the character in two — every terminal snaps here,
 * and the engine's `selectedText()` would otherwise be asked for half a grapheme.
 */
internal fun snapColumn(frame: TerminalFrame, row: Int, column: Int, toEnd: Boolean): Int {
    val cells = frame.rows.getOrNull(row)?.cells ?: return column
    if (cells.isEmpty()) return column
    var at = column.coerceIn(0, cells.size - 1)
    return if (toEnd) {
        while (at + 1 < cells.size && cells[at + 1].width == 0) at++
        at
    } else {
        while (at > 0 && cells[at].width == 0) at--
        at
    }
}

/**
 * The columns of the word under ([row], [column]) — a run of non-blank cells — or null on a blank.
 *
 * "Blank" is an empty cell or a space; everything else is word material, which is deliberately
 * coarse: a terminal's words are paths, flags and URLs as often as they are prose, and a user who
 * long-pressed a path wants the path.
 */
internal fun wordSpanAt(frame: TerminalFrame, row: Int, column: Int): IntRange? {
    val cells = frame.rows.getOrNull(row)?.cells ?: return null
    if (column !in cells.indices) return null
    fun blank(index: Int): Boolean {
        val cell = cells[index]
        // A continuation cell belongs to the wide glyph on its left, so it is never a boundary.
        if (cell.width == 0) return false
        return cell.text.isEmpty() || cell.text == " "
    }
    if (blank(column)) return null
    var first = column
    while (first > 0 && !blank(first - 1)) first--
    var last = column
    while (last + 1 < cells.size && !blank(last + 1)) last++
    return first..last
}

/**
 * Where the two touch handles sit for [frame]'s selection, or an empty list when there is none (or
 * when it is entirely off screen).
 *
 * Handles are pinned to the CELL, so they ride the same `scrollOffsetPx` the grid is drawn with and
 * never drift off the text while the surface is scrolling. The start handle hangs above its cell and
 * the end handle below its own, which is what keeps them apart on a one-row selection.
 */
internal fun selectionHandles(
    frame: TerminalFrame,
    metrics: CellMetrics,
    scrollOffsetPx: Float,
): List<SelectionHandleSpot> {
    val selection = frame.selection?.ordered() ?: return emptyList()
    val spots = mutableListOf<SelectionHandleSpot>()
    val startRow = selection.start.row - frame.viewportTop
    val endRow = selection.end.row - frame.viewportTop
    if (startRow in 0 until frame.size.rows) {
        spots += SelectionHandleSpot(
            SelectionHandle.START,
            Offset(
                selection.start.column * metrics.width,
                startRow * metrics.height - scrollOffsetPx,
            ),
        )
    }
    if (endRow in 0 until frame.size.rows) {
        spots += SelectionHandleSpot(
            SelectionHandle.END,
            Offset(
                (selection.end.column + 1) * metrics.width,
                (endRow + 1) * metrics.height - scrollOffsetPx,
            ),
        )
    }
    return spots
}

/** The handle within [radiusPx] of [position], nearest first, or null. */
internal fun handleAt(
    position: Offset,
    handles: List<SelectionHandleSpot>,
    radiusPx: Float,
): SelectionHandle? = handles
    .minByOrNull { (it.position - position).getDistance() }
    ?.takeIf { (it.position - position).getDistance() <= radiusPx }
    ?.handle
