package dev.supermux.terminal.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import dev.supermux.terminal.Modifiers
import dev.supermux.terminal.MouseButton
import dev.supermux.terminal.TerminalModes
import kotlin.math.abs
import kotlin.math.floor

/**
 * What a pointer gesture is, as far as routing is concerned.
 *
 * [DRAG] is a pointer moving with a button (or a finger) down; [HOVER] is one moving without.
 * [LONG_PRESS] is a press held in place past the platform's long-press timeout — on touch it is the
 * only gesture a user has that no program can take away, which is why it is routed like a
 * Shift-modified one.
 */
enum class PointerIntent { WHEEL, PRESS, DRAG, RELEASE, HOVER, LONG_PRESS }

/** Which kind of pointer produced a gesture. A finger drag scrolls; a mouse drag selects. */
enum class PointerDevice { MOUSE, TOUCH }

/**
 * Where a pointer gesture goes.
 *
 * - [LOCAL_HISTORY]: the surface's own scrollback. Nothing crosses the wire — see [ScrollController].
 * - [LOCAL_SELECTION]: the surface's own selection. Nothing crosses the wire either; see
 *   [TerminalSelectionController], which asks the ENGINE to hold the selection but never sends the
 *   program anything.
 * - [REMOTE_MOUSE]: a [dev.supermux.terminal.TerminalMouse] for the engine to encode and the program
 *   to read.
 * - [REMOTE_SCROLL_KEYS]: alternate scroll (DECSET 1007). The program gets CURSOR KEYS, not a mouse
 *   report — see rule 4.
 */
enum class PointerRoute { LOCAL_HISTORY, LOCAL_SELECTION, REMOTE_MOUSE, REMOTE_SCROLL_KEYS }

/**
 * Who gets a pointer gesture: this surface, or the program on the other end of the pty.
 *
 * It is a PURE function of the terminal's negotiated modes, the gesture and the modifier state —
 * no session, no composition, no Compose types — because this is the one decision that everything
 * else in the input layer follows from, and it has to be readable and testable on its own
 * (`InputPolicyTest`).
 *
 * The rules, in the order they are applied:
 *
 * 1. **A long press is always local.** It is the touch equivalent of holding Shift: the way a user
 *    with no keyboard reaches the selection of a program that grabbed the mouse.
 * 2. **Shift forces local.** Every terminal does this, and programs expect it: Shift is how the user
 *    overrides an application that turned mouse reporting on. Shift + wheel is history, Shift +
 *    button is selection.
 * 3. **Mouse tracking makes pointer events remote.** If the program asked for the mouse (modes 1000
 *    / 1002 / 1003 / …, reported as [TerminalModes.mouseTracking]), it gets the mouse: buttons,
 *    motion and the wheel alike. The ENGINE's encoder decides what that turns into on the wire —
 *    which format (X10, SGR, SGR-pixels), whether motion is reported at all, whether a wheel notch
 *    produces anything — from the modes the program negotiated. This layer never writes an escape
 *    sequence.
 * 4. **On the alternate screen the wheel is alternate scroll's** ([TerminalModes.alternateScroll],
 *    DECSET 1007 — on by default), even with tracking off. There is no scrollback there to scroll
 *    (`historyRows` is 0 on the alternate screen), and what a pager wants instead is cursor keys:
 *    that is what 1007 means and what every terminal does with it. The route is
 *    [PointerRoute.REMOTE_SCROLL_KEYS] and the bytes are still not written here — the input layer
 *    hands the engine [dev.supermux.terminal.TerminalKeys.ARROW_UP] / `ARROW_DOWN` and its KEY
 *    encoder decides what those are under the modes that program set, so application-cursor mode
 *    (DECCKM) is respected without this layer knowing it exists. With 1007 OFF the wheel does
 *    nothing at all, which is also what every terminal does: there is nothing to scroll and
 *    fabricating an escape sequence would be inventing a protocol.
 * 5. **Everything else is local**: in the shell, a wheel notch and a finger drag scroll this
 *    surface's history, and a mouse press or drag is a selection.
 */
object TerminalInputPolicy {

    fun route(
        modes: TerminalModes,
        intent: PointerIntent,
        device: PointerDevice = PointerDevice.MOUSE,
        modifiers: Int = Modifiers.NONE,
    ): PointerRoute {
        if (intent == PointerIntent.LONG_PRESS) return PointerRoute.LOCAL_SELECTION
        val shift = modifiers and Modifiers.SHIFT != 0
        if (shift) return localRoute(intent, device)
        if (modes.mouseTracking) return PointerRoute.REMOTE_MOUSE
        if (modes.alternateScreen && intent == PointerIntent.WHEEL) {
            return if (modes.alternateScroll) PointerRoute.REMOTE_SCROLL_KEYS else PointerRoute.LOCAL_HISTORY
        }
        return localRoute(intent, device)
    }

    /**
     * The local half: the wheel and a FINGER drag walk the history, a mouse button selects.
     *
     * A hover with no button down is nobody's business once the program is not asking for motion,
     * so it is reported as a selection-track (a no-op today) rather than as history.
     */
    private fun localRoute(intent: PointerIntent, device: PointerDevice): PointerRoute = when (intent) {
        PointerIntent.WHEEL -> PointerRoute.LOCAL_HISTORY
        PointerIntent.DRAG -> if (device == PointerDevice.TOUCH) PointerRoute.LOCAL_HISTORY else PointerRoute.LOCAL_SELECTION
        PointerIntent.PRESS, PointerIntent.RELEASE, PointerIntent.HOVER, PointerIntent.LONG_PRESS ->
            PointerRoute.LOCAL_SELECTION
    }

    /**
     * The xterm wheel "button" for a scroll delta, or [MouseButton.NONE] when it is not a scroll.
     *
     * Compose's scroll deltas follow the scrollable convention (positive Y = content moves toward
     * the start, i.e. the wheel turned DOWN), the same sign `Modifier.scrollable` consumes. The
     * larger axis wins so a diagonal trackpad flick reports one direction rather than two.
     */
    fun wheelButton(delta: Offset): Int = when {
        !delta.x.isFinite() || !delta.y.isFinite() -> MouseButton.NONE
        abs(delta.y) >= abs(delta.x) -> when {
            delta.y > 0f -> MouseButton.WHEEL_DOWN
            delta.y < 0f -> MouseButton.WHEEL_UP
            else -> MouseButton.NONE
        }
        delta.x > 0f -> MouseButton.WHEEL_RIGHT
        delta.x < 0f -> MouseButton.WHEEL_LEFT
        else -> MouseButton.NONE
    }

    /**
     * How many wheel events one scroll delta is worth: one per notch, at least one, and never a
     * burst — a trackpad reports fractions of a notch and a broken device could report a thousand.
     */
    fun wheelNotches(delta: Offset): Int {
        val magnitude = maxOf(abs(delta.x), abs(delta.y))
        if (!magnitude.isFinite()) return 0
        return kotlin.math.ceil(magnitude).toInt().coerceIn(1, MAX_NOTCHES)
    }

    /** Never send more than this many wheel events for one platform scroll event. */
    const val MAX_NOTCHES: Int = 8

    /**
     * Cursor-key presses one wheel notch is worth under alternate scroll (DECSET 1007).
     *
     * Three, like xterm's own alternate-scroll and like the three lines a wheel notch scrolls
     * everywhere else; one would make a pager crawl and the user would reach for the scrollbar that
     * a full-screen program does not have.
     */
    const val ALTERNATE_SCROLL_LINES: Int = 3
}

/** A cell of the VIEWPORT: column 0 is the left edge, row 0 the top VISIBLE row. */
@Immutable
data class TerminalCellPosition(val column: Int, val row: Int)

/**
 * The cell a pointer at [position] is over, in the coordinates [dev.supermux.terminal.TerminalMouse]
 * wants (viewport cells of the frame the engine published).
 *
 * The painter shifts the grid up by `scrollOffsetPx` for the sub-row displacement of smooth
 * scrolling, so the hit test has to shift the pointer the same way — otherwise every click would be
 * off by up to a row exactly while the user is scrolling. The result is CLAMPED to the grid: a
 * pointer in the chrome strip below the last row reports the last row, which is what every terminal
 * does with a drag that leaves the window.
 */
fun cellAt(
    position: Offset,
    metrics: CellMetrics,
    scrollOffsetPx: Float,
    columns: Int,
    rows: Int,
): TerminalCellPosition {
    val column = floor(position.x / metrics.width).toInt().coerceIn(0, maxOf(0, columns - 1))
    val row = floor((position.y + scrollOffsetPx) / metrics.height).toInt().coerceIn(0, maxOf(0, rows - 1))
    return TerminalCellPosition(column, row)
}
