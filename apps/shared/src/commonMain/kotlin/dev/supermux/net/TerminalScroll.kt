package dev.supermux.net

/**
 * Touch-drag → scrollback math for the NATIVE terminal panes (iOS SwiftTerm,
 * Android ConnectBot termlib). The web PWA solves this in TypeScript
 * (`src/web-app/src/lib/touch-scroll.ts`); this is the shared-Kotlin port so the
 * two native apps drive the SAME, tested logic instead of each reinventing it.
 *
 * Neither native terminal lib turns a finger drag into scrollback movement:
 * SwiftTerm forwards a one-finger drag to the app as a pressed-button drag (tmux
 * reads it as a selection, not a scroll), and termlib's drag only scrolls its
 * own LOCAL scrollback — which is empty because our terminals live in tmux's
 * alternate screen. We bridge the gap exactly like web: convert the drag into
 * whole-row deltas and forward SGR mouse-wheel events to the pty, letting tmux
 * scroll its own history.
 *
 * Our terminals always run with tmux `mouse on` — the broker sets `set -g mouse
 * on` for the scratch server (core/terminal/manager.ts) and `set-option mouse
 * on` for the agent viewer session (core/terminal/agent-tmux.ts) — so the
 * wheel-forwarding path is always the correct one. The platform callers
 * therefore forward wheel events unconditionally rather than detecting
 * mouse-tracking mode (termlib exposes no way to query it anyway).
 */

/** Whole rows to scroll now, plus the sub-row pixels to carry into the next move. */
data class ScrollStep(val lines: Int, val remainderPx: Double)

/**
 * Convert an accumulated vertical pixel delta into a whole number of rows,
 * carrying the remainder back so a slow drag still scrolls eventually — without
 * the carry, every move smaller than one row would round to zero forever and the
 * buffer would never budge.
 *
 * Sign convention matches [wheelEventsFromLines] / xterm's scrollLines():
 * positive scrolls down toward newer output (finger moving up), negative scrolls
 * back into history (finger moving down). A non-finite or non-positive cell
 * height is a safe no-op (returns 0 lines and drops the remainder).
 */
fun linesFromPixels(accumPx: Double, cellHeightPx: Double): ScrollStep {
    if (!cellHeightPx.isFinite() || cellHeightPx <= 0.0) return ScrollStep(0, 0.0)
    val lines = (accumPx / cellHeightPx).toInt() // Double.toInt() truncates toward zero (== Math.trunc)
    return ScrollStep(lines, accumPx - lines * cellHeightPx)
}

// SGR (1006) mouse button codes for the wheel. tmux negotiates SGR encoding on
// attach, so this is the format it expects on input.
private const val WHEEL_UP = 64 // scroll back into history
private const val WHEEL_DOWN = 65 // scroll toward newer output

/**
 * Encode a whole-row scroll delta as the SGR mouse-wheel escape sequence a
 * mouse-tracking app (e.g. tmux) expects, one wheel event per row.
 *
 * Sign matches [linesFromPixels]: negative scrolls back into history (wheel up,
 * button 64), positive scrolls toward newer output (wheel down, button 65).
 * `col`/`row` are 1-based pointer cell coordinates; any in-pane coordinate works
 * for our single-pane windows, and values below 1 are clamped (tmux ignores
 * 0/out-of-range). Returns an empty array for a zero delta.
 */
fun wheelEventsFromLines(lines: Int, col: Int, row: Int): ByteArray {
    val count = if (lines < 0) -lines else lines
    if (count == 0) return ByteArray(0)
    val button = if (lines < 0) WHEEL_UP else WHEEL_DOWN
    val c = if (col < 1) 1 else col
    val r = if (row < 1) 1 else row
    return "\u001B[<$button;$c;${r}M".repeat(count).encodeToByteArray()
}
