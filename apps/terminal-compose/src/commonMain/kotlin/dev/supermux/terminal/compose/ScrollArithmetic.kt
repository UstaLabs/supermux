package dev.supermux.terminal.compose

import androidx.compose.runtime.Immutable
import kotlin.math.floor
import kotlin.math.min

/**
 * Where the top edge of the viewport sits, in the engine's ABSOLUTE row space.
 *
 * [row] is a scrollbar row (`TerminalViewport.viewportTop` / `TerminalPoint.row`): 0 is the oldest
 * retained history row and the number grows toward newer output, so `row == historyRows` is the
 * live bottom. [remainderPx] is how far BELOW the top of [row] the viewport edge has slid — always
 * in `0.0 until cellHeightPx`, never negative, so a position names exactly one row plus a
 * sub-row displacement and never two rows at once.
 *
 * The pair is the whole trick behind smooth scrolling: [row] is what the engine is asked for (once
 * per boundary crossing) and [remainderPx] is what the painter translates by (every frame, for
 * free).
 */
@Immutable
data class ScrollPosition(val row: Long, val remainderPx: Double) {
    /** This position as one number of pixels in the absolute row space. */
    fun absolutePx(cellHeightPx: Double): Double = row * cellHeightPx + remainderPx
}

/**
 * [position] moved by [deltaPx] pixels, clamped to `0 .. newestTop` rows.
 *
 * Units, explicitly: [deltaPx] is PIXELS in the surface's own coordinate space (already normalized
 * by whatever produced it — a touch drag amount, a fling sample, a platform-converted wheel notch),
 * and a POSITIVE delta moves toward NEWER output, i.e. increases [ScrollPosition.row]. [newestTop]
 * is `TerminalViewport.historyRows`: the largest viewport top that exists, which is also the live
 * bottom.
 *
 * A non-finite delta is ignored rather than turned into a NaN anchor, and both boundaries are hard:
 * at `row = 0` (the oldest retained row) and at `row = newestTop` (the bottom) the remainder is
 * exactly 0, so no phantom sub-row displacement survives a clamp.
 */
fun moveViewport(
    position: ScrollPosition,
    deltaPx: Double,
    cellHeightPx: Double,
    newestTop: Long,
): ScrollPosition {
    require(cellHeightPx.isFinite() && cellHeightPx > 0)
    require(newestTop >= 0)
    if (!deltaPx.isFinite()) return position
    val absolute = (position.row * cellHeightPx + position.remainderPx + deltaPx)
        .coerceIn(0.0, newestTop * cellHeightPx)
    val row = floor(absolute / cellHeightPx).toLong()
    return ScrollPosition(row, absolute - row * cellHeightPx)
}

/**
 * [position] brought back inside `0 .. newestTop`, dropping the remainder when the row itself moved.
 *
 * This is the EVICTION rule. Absolute row numbers are not stable: Ghostty evicts whole history
 * pages oldest-first, and every surviving row's absolute number DECREASES when it does. An anchor
 * the UI holds does not follow that renumbering (only the engine's own selection does), so an
 * anchor drifts toward newer content and, once the rows it named are gone, ends up outside the
 * range the engine still has. Clamping it here pins it to row 0 — the OLDEST RETAINED row — instead
 * of letting it point above the top of the buffer, and drops the sub-row displacement because the
 * row it was measured against no longer exists.
 */
fun clampViewport(position: ScrollPosition, newestTop: Long): ScrollPosition {
    require(newestTop >= 0)
    val row = position.row.coerceIn(0L, newestTop)
    return if (row == position.row) position else ScrollPosition(row, 0.0)
}

/**
 * How far up the painter shifts the frame it was handed, in pixels — `drawTerminalFrame`'s
 * `scrollOffsetPx`.
 *
 * The published frame is anchored at [frameTopRow] and carries exactly the grid's rows, so a
 * fractional shift always needs ONE row the frame does not have: shifting up (a positive offset)
 * exposes the bottom edge and needs `frameTopRow + frameRows`, shifting down (a negative offset,
 * which happens while the engine is still catching up with the anchor) exposes the top edge and
 * needs `frameTopRow - 1`. [rowAboveAvailable] and [rowBelowAvailable] say whether the surface can
 * actually paint those rows (see `ScrollStrip`).
 *
 * The result is clamped to what the available rows can COVER, which is the no-blank-strip
 * guarantee: with the row below in hand the offset may reach a whole cell up, with the row above in
 * hand a whole cell down, and with neither the frame is painted exactly where the engine put it.
 * A frame that lags the anchor by several rows (a fast fling) therefore paints one row off for one
 * frame instead of tearing a gap into the grid.
 */
fun paintOffsetPx(
    position: ScrollPosition,
    frameTopRow: Long,
    cellHeightPx: Double,
    rowAboveAvailable: Boolean,
    rowBelowAvailable: Boolean,
): Double {
    require(cellHeightPx.isFinite() && cellHeightPx > 0)
    val raw = (position.row - frameTopRow) * cellHeightPx + position.remainderPx
    if (!raw.isFinite()) return 0.0
    val lower = if (rowAboveAvailable) -cellHeightPx else 0.0
    val upper = if (rowBelowAvailable) cellHeightPx else 0.0
    return raw.coerceIn(lower, upper)
}

/**
 * The pixel span the painter actually covers for [offsetPx], measured from the top of the grid
 * rectangle — the proof obligation behind [paintOffsetPx].
 *
 * The frame covers `-offset .. frameRows * cellHeight - offset`; each available overscan row adds
 * one cell at its end. `0 .. frameRows * cellHeight` must be inside the result or the surface would
 * show a blank strip.
 */
fun paintedSpanPx(
    offsetPx: Double,
    frameRows: Int,
    cellHeightPx: Double,
    rowAboveAvailable: Boolean,
    rowBelowAvailable: Boolean,
): ClosedFloatingPointRange<Double> {
    val top = -offsetPx - (if (rowAboveAvailable) cellHeightPx else 0.0)
    val bottom = frameRows * cellHeightPx - offsetPx + (if (rowBelowAvailable) cellHeightPx else 0.0)
    return top..bottom
}

/** The height of the grid rectangle a frame of [frameRows] rows owns, never more than [canvasPx]. */
fun gridHeightPx(frameRows: Int, cellHeightPx: Float, canvasPx: Float): Float =
    min(frameRows * cellHeightPx, canvasPx)
