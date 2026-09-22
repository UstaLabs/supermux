package dev.supermux.terminal.compose

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.terminal.TerminalRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Local shell history, scrolled at pixel resolution and without a single byte crossing the wire.
 *
 * This is the point of the whole terminal package: the scrollback is already in the engine, in this
 * process, so a drag is arithmetic and a paint — never a request to the host and never an input
 * event to the program on the other end of the pty.
 *
 * **What it holds.** A live-follow flag, a logical anchor in ABSOLUTE row space and a sub-row pixel
 * displacement ([position]). While [following] the anchor tracks the bottom on every published
 * frame, so new output keeps scrolling in. Once a gesture moves the anchor off the bottom it stays
 * where it is: new output no longer moves the visible text.
 *
 * **What it asks the engine for.** `scrollTo(row)` EXACTLY ONCE per row boundary crossed — never
 * for the fractional part. A 3-pixel drag inside one 16-pixel row changes nothing the engine knows
 * about; it changes only [paintOffset], which the painter translates by. This keeps the engine's
 * frame rate independent of the gesture's, which is what makes the motion smooth.
 *
 * **Gestures.** [scrollableState] is driven by `Modifier.scrollable`, so touch drags, the desktop
 * mouse wheel and a trackpad all arrive here as PIXELS that the platform's own event adapter
 * already normalized — a JVM wheel notch is converted by Compose's mouse-wheel node against the
 * density and the platform's scroll configuration, never assumed to be a pixel count here. The same
 * modifier tracks release velocity in pixels/second and runs the fling through Compose's decay
 * spec; [consumePx] returning less than it was given is what stops the decay at a history boundary.
 *
 * **Cancellation.** [cancelFling] takes the scroll mutex at [MutatePriority.PreventUserInput],
 * which interrupts a running fling; the surface calls it on resize, on reset, on going inactive, on
 * a transition into application mouse mode and on disposal. A new touch cancels it through the same
 * mutex, inside `Modifier.scrollable`.
 *
 * Not thread-safe, and not meant to be: everything here runs on the composition's own dispatcher.
 */
@Stable
class ScrollController(
    private val scope: CoroutineScope,
    private val scrollTo: (Long) -> Unit,
) {
    /** The top edge of the viewport: an absolute row plus a sub-row pixel displacement. */
    var position: ScrollPosition by mutableStateOf(ScrollPosition(0L, 0.0))
        private set

    /** True while the viewport is pinned to the bottom and new output scrolls in. */
    var following: Boolean by mutableStateOf(true)
        private set

    /** `TerminalViewport.historyRows` of the newest frame: the largest viewport top that exists. */
    var newestTop: Long by mutableStateOf(0L)
        private set

    /** The cell height every pixel here is measured against; set from the surface's metrics. */
    var cellHeightPx: Double = 1.0
        private set

    private val strip = ScrollStrip()
    private var requestedRow: Long? = null

    /** True once a frame has come back carrying [requestedRow]; see [onFrame]'s eviction rule. */
    private var answered = false

    /**
     * The scrollable this surface's gestures drive. Positive deltas move toward newer output; the
     * surface passes `reverseDirection` so that dragging the finger DOWN walks back into history.
     */
    val scrollableState: ScrollableState = ScrollableState { delta -> consumePx(delta) }

    /** True while a drag or a fling is running. */
    val scrolling: Boolean get() = scrollableState.isScrollInProgress

    // ----------------------------------------------------------------- gesture input ----

    /**
     * Move by [deltaPx] normalized pixels (positive = toward newer output) and return how many were
     * actually consumed. A boundary returns less than it was given, which is how the fling learns
     * it has arrived.
     */
    fun consumePx(deltaPx: Float): Float {
        val cell = cellHeightPx
        if (!deltaPx.isFinite() || cell <= 0.0) return 0f
        val before = position
        val next = moveViewport(before, deltaPx.toDouble(), cell, newestTop)
        if (next == before) return 0f
        settle(next)
        return (next.absolutePx(cell) - before.absolutePx(cell)).toFloat()
    }

    /** Interrupt a running fling (and any drag): a resize, a reset, mouse mode, disposal. */
    fun cancelFling() {
        if (!scrollableState.isScrollInProgress) return
        scope.launch { scrollableState.scroll(MutatePriority.PreventUserInput) { } }
    }

    /**
     * Back to the bottom and to live-follow. Every key, paste or other input the user sends returns
     * here: a program that is being typed at has to show what it prints.
     */
    fun followBottom() {
        cancelFling()
        val bottom = ScrollPosition(newestTop, 0.0)
        following = true
        position = bottom
        request(bottom.row)
    }

    // ----------------------------------------------------------------- engine feedback ----

    /**
     * Fold a published frame in: remember its rows for overscan, learn the new bottom, and keep the
     * anchor honest.
     *
     * While [following] the anchor simply becomes the new bottom.
     *
     * **Eviction.** Absolute row numbers are not stable — Ghostty drops whole history pages
     * oldest-first and every surviving row's number decreases when it does — but the ENGINE's own
     * viewport pin follows its row across that renumbering, and an anchor this class holds does
     * not. So once a request has been answered (a frame arrived carrying the row that was asked
     * for) and no gesture is running, the engine's `viewportTop` becomes the truth and the anchor
     * adopts it: the same TEXT stays on screen under a new number. When eviction takes the anchored
     * row itself, the engine's pin lands on the oldest row it still has and the anchor follows it
     * there — the viewport clamps to the oldest retained text instead of jumping to the bottom or
     * pointing above the top of the buffer. [clampViewport] is the backstop for anything left
     * outside `0 .. historyRows` after that.
     *
     * While a drag or a fling IS running the user's input wins: a frame that is simply a few rows
     * behind the anchor must not drag the anchor backwards.
     */
    fun onFrame(frame: TerminalFrame) {
        val previous = strip.newest
        if (previous != null && (previous.size != frame.size || previous.epoch != frame.epoch)) {
            // The grid changed under us without the surface asking (the host resized the session,
            // or it was reset): the remembered rows and the sub-row pixels are both stale.
            onGridChanged()
        }
        strip.record(frame)
        newestTop = frame.historyRows
        if (following) {
            position = ScrollPosition(frame.historyRows, 0.0)
            // The engine follows the bottom by itself; nothing to ask for.
            requestedRow = null
            answered = false
            return
        }
        if (frame.viewportTop == requestedRow) {
            answered = true
        } else if (answered && !scrolling && frame.viewportTop != position.row) {
            position = ScrollPosition(frame.viewportTop, position.remainderPx)
            requestedRow = frame.viewportTop
        }
        val clamped = clampViewport(position, frame.historyRows)
        if (clamped != position) {
            position = clamped
            request(clamped.row)
        }
        // A bottom that shrank down onto the anchor (a reset, a cleared screen) IS the bottom.
        following = position.row >= frame.historyRows
    }

    /** The cell box changed (font, density). Pixels measured against the old one are meaningless. */
    fun onCellHeight(cellHeightPx: Float) {
        val next = cellHeightPx.toDouble()
        if (!next.isFinite() || next <= 0.0 || next == this.cellHeightPx) return
        this.cellHeightPx = next
        cancelFling()
        position = ScrollPosition(position.row, 0.0)
    }

    /**
     * The grid changed shape (a resize, a reflow). The LOGICAL anchor survives — the row the user
     * was reading is the whole reason they scrolled there — but the sub-row displacement and every
     * remembered row do not: a reflow rewraps the text those pixels were measured against.
     */
    fun onGridChanged() {
        cancelFling()
        strip.clear()
        position = ScrollPosition(position.row, 0.0)
        if (following) return
        // The reflow moved the anchor's content; ask for it again rather than trusting the row the
        // engine happens to be sitting on.
        requestedRow = null
        answered = false
        request(position.row)
    }

    /** The session was reset (RIS) or this surface was bound to another one. */
    fun onReset() {
        cancelFling()
        strip.clear()
        newestTop = 0L
        following = true
        position = ScrollPosition(0L, 0.0)
        requestedRow = null
        answered = false
    }

    // ----------------------------------------------------------------- painting ----

    /** How far up the painter shifts [frame]; see [paintOffsetPx]. */
    fun paintOffset(frame: TerminalFrame): Float = paintOffsetPx(
        position = position,
        frameTopRow = frame.viewportTop,
        cellHeightPx = cellHeightPx,
        rowAboveAvailable = rowAbove(frame) != null,
        rowBelowAvailable = rowBelow(frame) != null,
    ).toFloat()

    /** The overscan row just above [frame], if some recent frame still carries it. */
    fun rowAbove(frame: TerminalFrame): TerminalRow? = strip.rowAt(frame, frame.viewportTop - 1)

    /** The overscan row just below [frame], if some recent frame still carries it. */
    fun rowBelow(frame: TerminalFrame): TerminalRow? =
        strip.rowAt(frame, frame.viewportTop + frame.size.rows)

    // ----------------------------------------------------------------- internals ----

    private fun settle(next: ScrollPosition) {
        position = next
        following = next.row >= newestTop
        request(next.row)
    }

    /** `scrollTo` ONCE per row, never for the fractional part. */
    private fun request(row: Long) {
        if (requestedRow == row) return
        requestedRow = row
        answered = false
        scrollTo(row)
    }
}

/**
 * The few most recent frames, kept so the painter can draw ONE row beyond the grid the engine gave
 * it.
 *
 * The engine publishes exactly `rows` rows — asking it for more would change the pty's window size,
 * which would change where every program wraps — so the row that a fractional translation exposes
 * at the bottom (or, while the engine catches up with the anchor, at the top) has to come from
 * somewhere else. It comes from here: scrolling walks the anchor one row at a time, and a frame
 * anchored one row away carries the neighbour the current one is missing. Rows are immutable and
 * shared between frames, so holding [DEPTH] of them costs a few list headers.
 *
 * Frames of a different grid or a different epoch are dropped rather than reinterpreted — their
 * rows would be the wrong width or from another screen entirely.
 */
internal class ScrollStrip(private val depth: Int = DEPTH) {
    private val frames = ArrayDeque<TerminalFrame>()

    /** The most recently recorded frame, or null before the first one. */
    val newest: TerminalFrame? get() = frames.firstOrNull()

    fun record(frame: TerminalFrame) {
        val newest = frames.firstOrNull()
        if (newest != null && (newest.size != frame.size || newest.epoch != frame.epoch)) frames.clear()
        frames.addFirst(frame)
        while (frames.size > depth) frames.removeLast()
    }

    fun clear() = frames.clear()

    /** The row at ABSOLUTE index [absolute], from the newest frame that still carries it. */
    fun rowAt(current: TerminalFrame, absolute: Long): TerminalRow? {
        if (absolute < 0) return null
        for (frame in frames) {
            if (frame.size != current.size || frame.epoch != current.epoch) continue
            val index = absolute - frame.viewportTop
            if (index in 0 until frame.size.rows) return frame.rows.getOrNull(index.toInt())
        }
        return null
    }

    private companion object {
        /**
         * Three frames: the current one, the one the anchor came from, and one of slack for a frame
         * published between two boundary crossings. Deeper would only keep rows that eviction may
         * already have renumbered.
         */
        const val DEPTH = 3
    }
}

/**
 * The scroll controller of the enclosing [Terminal], for the input layer to reach.
 *
 * Any key, paste or other input must return the surface to the bottom — see
 * [ScrollController.followBottom]. Input itself lands with Plan 2 Tasks 4 and 5; this is the seam
 * it plugs into, and it is null outside a [Terminal].
 */
val LocalTerminalScroll = staticCompositionLocalOf<ScrollController?> { null }
