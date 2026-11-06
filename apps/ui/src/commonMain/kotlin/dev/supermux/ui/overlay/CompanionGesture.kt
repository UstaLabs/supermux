// What a press on the assistant overlay means: a tap (open its chat), a double-tap (its glasses on or
// off), a hold (talk to it), or a drag (move it). Holding works like a voice message in Telegram: let go
// to send, slide up to lock and keep talking hands-free, slide toward the middle of the screen to cancel.
//
// Pure: it is fed pointer positions (screen pixels) and times (ms) and answers with intents, so every
// platform's overlay shares it and it can be tested without a screen.
package dev.supermux.ui.overlay

import kotlin.math.hypot

class CompanionGesture(
    private val holdMs: Long,
    /** Movement (px) before the hold fires that turns the press into a drag. */
    private val slop: Float,
    /** Distance (px) toward the middle of the screen that arms cancel while listening. */
    private val cancelDistance: Float,
    /** Distance (px) upward that locks listening; 0 = no lock (desktop). */
    private val lockDistance: Float,
) {
    sealed interface Intent {
        data object Tap : Intent
        /** A second tap pressed within [doubleTapMs] of the first letting go. */
        data object DoubleTap : Intent
        data object ListenStart : Intent
        /** The finger moved while listening: what letting go would do now. */
        data class ListenSlide(val cancelArmed: Boolean, val lockArmed: Boolean) : Intent
        /** Listening goes on hands-free; the press is over. */
        data object ListenLock : Intent
        data object ListenSend : Intent
        data object ListenCancel : Intent
        data object DragStart : Intent
        /** Offset from where the press started. */
        data class DragTo(val dx: Float, val dy: Float) : Intent
        data object DragEnd : Intent
    }

    /** +1 when the middle of the screen is to the right of the companion (it sits on the left edge). */
    var towardMiddle: Int = 1

    /**
     * How long a tap waits for a second one (ms), as Android counts it: from the first letting go to the
     * second press. 0: no double-taps, so a tap is a tap at once. While one waits, [tapTimeout] decides it.
     */
    var doubleTapMs: Long = 0

    private enum class Mode { Idle, Pressed, Dragging, Listening, Locked }

    private var mode = Mode.Idle
    private var x0 = 0f
    private var y0 = 0f
    private var downAt = 0L
    private var cancelArmed = false
    private var lockArmed = false
    /** When a tap let go that may still get a second one. */
    private var tapUpAt: Long? = null
    /** This press came soon enough after a tap to make a double-tap, if it stays a tap. */
    private var second = false

    /** A tap let go and waits for a second one: the host calls [tapTimeout] [doubleTapMs] later. */
    val tapWaiting: Boolean get() = tapUpAt != null

    fun down(x: Float, y: Float, at: Long): List<Intent> {
        val waiting = tapUpAt
        tapUpAt = null
        second = waiting != null && at - waiting <= doubleTapMs
        mode = Mode.Pressed
        x0 = x; y0 = y; downAt = at
        cancelArmed = false; lockArmed = false
        // A tap whose timer came late is still a tap, and it happened first.
        return if (waiting != null && !second) listOf(Intent.Tap) else emptyList()
    }

    /** The wait for a second tap is over ([doubleTapMs] after it let go): it was one tap. */
    fun tapTimeout(at: Long): List<Intent> {
        val upAt = tapUpAt ?: return emptyList()
        if (at - upAt < doubleTapMs) return emptyList()
        tapUpAt = null
        return listOf(Intent.Tap)
    }

    fun move(x: Float, y: Float, at: Long): List<Intent> {
        val dx = x - x0
        val dy = y - y0
        return when (mode) {
            Mode.Pressed -> if (hypot(dx, dy) > slop) {
                mode = Mode.Dragging
                second = false
                listOf(Intent.DragStart, Intent.DragTo(dx, dy))
            } else emptyList()
            Mode.Dragging -> listOf(Intent.DragTo(dx, dy))
            Mode.Listening -> slide(dx, dy)
            Mode.Idle, Mode.Locked -> emptyList()
        }
    }

    private fun slide(dx: Float, dy: Float): List<Intent> {
        val cancel = dx * towardMiddle > cancelDistance
        val locking = lockDistance > 0f && !cancel
        if (locking && dy <= -lockDistance) {
            mode = Mode.Locked
            return listOf(Intent.ListenLock)
        }
        val lockHint = locking && dy <= -lockDistance / 2
        if (cancel == cancelArmed && lockHint == lockArmed) return emptyList()
        cancelArmed = cancel; lockArmed = lockHint
        return listOf(Intent.ListenSlide(cancelArmed, lockArmed))
    }

    fun hold(at: Long): List<Intent> {
        if (mode != Mode.Pressed || at - downAt < holdMs) return emptyList()
        mode = Mode.Listening
        second = false
        return listOf(Intent.ListenStart)
    }

    fun up(at: Long): List<Intent> {
        val was = mode
        mode = Mode.Idle
        return when (was) {
            Mode.Pressed -> when {
                second -> { second = false; listOf(Intent.DoubleTap) }
                doubleTapMs > 0 -> { tapUpAt = at; emptyList() }
                else -> listOf(Intent.Tap)
            }
            Mode.Dragging -> listOf(Intent.DragEnd)
            Mode.Listening -> listOf(if (cancelArmed) Intent.ListenCancel else Intent.ListenSend)
            Mode.Idle, Mode.Locked -> emptyList()
        }
    }

    fun cancel(): List<Intent> {
        val was = mode
        mode = Mode.Idle
        second = false
        return when (was) {
            Mode.Dragging -> listOf(Intent.DragEnd)
            Mode.Listening -> listOf(Intent.ListenCancel)
            Mode.Idle, Mode.Pressed, Mode.Locked -> emptyList()
        }
    }
}
