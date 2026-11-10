package dev.supermux.webview.engine.android

import kotlin.math.abs

/**
 * One gesture's sideways hand-off from the page to the scrolling parents (the phone's tab swipe). What the page can't
 * take of a mostly sideways, one-finger drag is offered to them; once they take some, the rest of the gesture is
 * [taken]: theirs, both ways, until the finger lifts. A pinch never is, even after its second finger lifts.
 */
internal class SideSwipe {
    private var totalX = 0f
    private var totalY = 0f
    private var pinch = false
    private var fraction = 0f

    /** Whether the parents took this gesture: the page gets no more of it. */
    var taken = false
        private set

    fun down() {
        totalX = 0f
        totalY = 0f
        pinch = false
        fraction = 0f
        taken = false
    }

    /** The finger moved by [dx], [dy] px, with [pointers] fingers down. */
    fun moved(dx: Float, dy: Float, pointers: Int) {
        if (pointers > 1) pinch = true
        totalX += dx
        totalY += dy
    }

    /** Whether what the page left of a sideways drag may go to the parents. */
    fun mayOffer(): Boolean = !taken && !pinch && abs(totalX) > abs(totalY)

    fun take() {
        taken = true
    }

    /** [px] as whole px for the parents, the fraction carried to the next. */
    fun whole(px: Float): Int {
        fraction += px
        val n = fraction.toInt()
        fraction -= n
        return n
    }
}
