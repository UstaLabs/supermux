// What a press on the assistant overlay means: a tap (open its chat), a hold (talk to it), or a drag
// (move it). Holding works like a voice message in Telegram: let go to send, slide up to lock and keep
// talking hands-free, slide toward the middle of the screen to cancel. A drag that comes to rest and
// stays still for a moment arms "look at this": letting go then shows the Agent what's under it,
// rather than moving it; moving on disarms it.
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
    /** Holding still this long (ms) while dragging arms looking; 0 = it never looks. */
    private val lookMs: Long = 0,
    /** Once armed, movement (px) beyond this disarms; a little wobble while holding still doesn't. */
    private val lookSlop: Float = slop * 2,
) {
    sealed interface Intent {
        data object Tap : Intent
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
        /** Held still while dragging: letting go now looks at what's under it, rather than moving it. */
        data object LookArm : Intent
        /** Moved on: letting go just moves it again. */
        data object LookDisarm : Intent
        /** Let go while armed: look at what's under it. */
        data object LookDrop : Intent
    }

    /** +1 when the middle of the screen is to the right of the companion (it sits on the left edge). */
    var towardMiddle: Int = 1

    private enum class Mode { Idle, Pressed, Dragging, Listening, Locked }

    private var mode = Mode.Idle
    private var x0 = 0f
    private var y0 = 0f
    private var downAt = 0L
    private var cancelArmed = false
    private var lockArmed = false
    // Where (and since when) a drag has been holding still, and whether that armed looking.
    private var stillX = 0f
    private var stillY = 0f
    private var stillAt = 0L
    private var looking = false

    /**
     * When holding still arms looking (the platform's timer then asks [dwell]); null while not
     * dragging, already armed, or when it never looks. Moves within the slop don't push it back.
     */
    val lookDueAt: Long? get() = if (mode == Mode.Dragging && !looking && lookMs > 0) stillAt + lookMs else null

    fun down(x: Float, y: Float, at: Long): List<Intent> {
        mode = Mode.Pressed
        x0 = x; y0 = y; downAt = at
        cancelArmed = false; lockArmed = false; looking = false
        return emptyList()
    }

    fun move(x: Float, y: Float, at: Long): List<Intent> {
        val dx = x - x0
        val dy = y - y0
        return when (mode) {
            Mode.Pressed -> if (hypot(dx, dy) > slop) {
                mode = Mode.Dragging
                stillX = x; stillY = y; stillAt = at
                listOf(Intent.DragStart, Intent.DragTo(dx, dy))
            } else emptyList()
            Mode.Dragging -> drag(x, y, at, dx, dy)
            Mode.Listening -> slide(dx, dy)
            Mode.Idle, Mode.Locked -> emptyList()
        }
    }

    private fun drag(x: Float, y: Float, at: Long, dx: Float, dy: Float): List<Intent> {
        val moved = hypot(x - stillX, y - stillY)
        if (looking) {
            if (moved <= lookSlop) return listOf(Intent.DragTo(dx, dy))
            looking = false
            stillX = x; stillY = y; stillAt = at
            return listOf(Intent.DragTo(dx, dy), Intent.LookDisarm)
        }
        if (moved > slop) { stillX = x; stillY = y; stillAt = at }
        return listOf(Intent.DragTo(dx, dy))
    }

    /** The platform's timer, at [lookDueAt]: arms looking if the drag is still holding still. */
    fun dwell(at: Long): List<Intent> {
        val due = lookDueAt ?: return emptyList()
        if (at < due) return emptyList()
        looking = true
        return listOf(Intent.LookArm)
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
        return listOf(Intent.ListenStart)
    }

    fun up(at: Long): List<Intent> {
        val was = mode
        val armed = looking
        mode = Mode.Idle
        looking = false
        return when (was) {
            Mode.Pressed -> listOf(Intent.Tap)
            Mode.Dragging -> listOf(if (armed) Intent.LookDrop else Intent.DragEnd)
            Mode.Listening -> listOf(if (cancelArmed) Intent.ListenCancel else Intent.ListenSend)
            Mode.Idle, Mode.Locked -> emptyList()
        }
    }

    fun cancel(): List<Intent> {
        val was = mode
        val armed = looking
        mode = Mode.Idle
        looking = false
        return when (was) {
            Mode.Dragging -> if (armed) listOf(Intent.LookDisarm, Intent.DragEnd) else listOf(Intent.DragEnd)
            Mode.Listening -> listOf(Intent.ListenCancel)
            Mode.Idle, Mode.Pressed, Mode.Locked -> emptyList()
        }
    }
}
