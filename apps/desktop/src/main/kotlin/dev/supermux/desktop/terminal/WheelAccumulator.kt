package dev.supermux.desktop.terminal

import dev.supermux.net.linesFromPixels
import dev.supermux.net.wheelEventsFromLines

/**
 * Pure glue between raw wheel/trackpad pixel deltas and the shared tmux-SGR wheel math
 * (`dev.supermux.net.TerminalScroll`, also used by the Android touch-drag path).
 *
 * JediTerm's own [com.jediterm.terminal.ui.TerminalPanel] wheel handling scrolls its LOCAL
 * scrollback buffer, which is inert under our sessions — every supermux terminal runs inside tmux's
 * alternate screen with `mouse on`, so there is no local scrollback to move and JediTerm's handler
 * is a silent no-op. [DesktopTerminalPanel] therefore intercepts wheel events itself and, through
 * this class, forwards them to the pty as SGR-1006 mouse-wheel escapes for tmux to interpret.
 *
 * Kept free of AWT types (`java.awt.event.MouseWheelEvent` etc.) so it is trivially unit-testable —
 * [DesktopTerminalPanel] is the only place that touches AWT.
 *
 * ### Delta convention: pixels, not notches
 * Callers feed a PIXEL delta per event, not a raw wheel-notch count. AWT's
 * `MouseWheelEvent.getPreciseWheelRotation()` gives fractional notches, and
 * `getScrollAmount()` gives the OS-configured "units per notch" — multiplying
 * `rotation * scrollAmount * cellHeightPx` converts a wheel/trackpad event into the SAME pixel
 * space Android's touch-drag path already feeds into `linesFromPixels`. That lets this class reuse
 * the shared remainder-carry accumulator instead of inventing a second, notch-counting one, and it
 * keeps high-resolution/trackpad sub-notch deltas smooth instead of rounding every event to a whole
 * multiple of `scrollAmount` lines.
 *
 * ### Sign convention: no flip needed on desktop
 * AWT reports a *positive* wheel rotation when the wheel is rotated down/toward the user — the
 * gesture that should reveal newer output — which is exactly [linesFromPixels]'s positive
 * convention ("positive scrolls down toward newer output"). So a positive pixel delta here maps
 * straight through to a positive line count and `WHEEL_DOWN` (button 65), and a negative delta maps
 * to `WHEEL_UP` (button 64), with no negation. (Contrast Android's touch-drag path, which DOES
 * negate: a finger dragging *up* the screen should reveal *older* content, the opposite of
 * `linesFromPixels`'s "up is positive" sense — see `TerminalPanel.kt`'s `accumPx += -d.y`.)
 */
class WheelAccumulator {
    private var accumPx = 0.0

    /**
     * Accumulate one wheel event's pixel delta and return the SGR wheel-escape bytes for whatever
     * whole number of lines the running total has now crossed, targeting the 1-based pointer cell
     * ([col], [row]) — callers pass the center cell (`cols/2`, `rows/2`, clamped >= 1; the shared
     * [wheelEventsFromLines] clamps too, so pre-clamping is only for Android-parity clarity, not a
     * correctness requirement here).
     *
     * Returns an empty array when:
     * - the running total hasn't reached a full line yet (the sub-line remainder is carried into
     *   the next call, so a slow trackpad drag still eventually scrolls — see [linesFromPixels]);
     * - [pixelDelta] is non-finite (NaN/Infinite) — a safe no-op that leaves any prior carry intact;
     * - [cellHeightPx] is non-finite or <= 0 — [linesFromPixels]'s own guard fires, which drops the
     *   accumulated pixels entirely (documented behaviour of the shared fn: "a non-finite or
     *   non-positive cell height is a safe no-op").
     */
    fun accumulate(pixelDelta: Double, cellHeightPx: Double, col: Int, row: Int): ByteArray {
        if (!pixelDelta.isFinite()) return EMPTY
        accumPx += pixelDelta
        val step = linesFromPixels(accumPx, cellHeightPx)
        accumPx = step.remainderPx
        if (step.lines == 0) return EMPTY
        return wheelEventsFromLines(step.lines, col, row)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
