package dev.supermux.webview.engine

import kotlin.math.abs

/**
 * One gesture's sideways hand-off from the page to the scrolling parents (the phone's tab swipe). What the page can't
 * take of a mostly sideways, one-finger drag is offered to them; once they take some, the rest of the gesture is
 * [taken]: theirs, both ways, until the finger lifts. A pinch never is, even after its second finger lifts.
 *
 * The page is asked first ([ask], [innerScrollQuestion]): a box under the finger that can still scroll that way (a
 * carousel, a long `<pre>`, a wide table) keeps the gesture, as the page itself does until its edge. Nothing is offered
 * until it answers; turning the other way asks again.
 */
internal class SideSwipe {
    private var totalX = 0f
    private var totalY = 0f
    private var pinch = false
    private var fraction = 0f

    /** The way the page was asked about (-1 the finger moving left, 1 right, 0 not asked), and what it said. */
    private var askedWay = 0
    private var innerScrolls: Boolean? = null

    /** Counts gestures, so an answer for an earlier one can be told apart. */
    var gesture = 0
        private set

    /** Whether the parents took this gesture: the page gets no more of it. */
    var taken = false
        private set

    fun down() {
        totalX = 0f
        totalY = 0f
        pinch = false
        fraction = 0f
        taken = false
        askedWay = 0
        innerScrolls = null
        gesture++
    }

    /** The finger moved by [dx], [dy] px, with [pointers] fingers down. */
    fun moved(dx: Float, dy: Float, pointers: Int) {
        if (pointers > 1) pinch = true
        totalX += dx
        totalY += dy
    }

    /** Whether what the page left of a sideways drag may go to the parents. */
    fun mayOffer(): Boolean = sideways() && askedWay == way() && innerScrolls == false

    /** The way to ask the page about now, or null: not a one-finger sideways drag, or asked about this way already. */
    fun ask(): Int? {
        if (!sideways() || way() == askedWay) return null
        askedWay = way()
        innerScrolls = null
        return askedWay
    }

    /** The page's answer for [way]: whether something under the finger can still scroll that way. */
    fun answered(way: Int, innerScrolls: Boolean) {
        if (way == askedWay) this.innerScrolls = innerScrolls
    }

    private fun sideways() = !taken && !pinch && abs(totalX) > abs(totalY)

    private fun way() = if (totalX < 0f) -1 else if (totalX > 0f) 1 else 0

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

    companion object {
        /**
         * Whether a page scrolled to [offsetX] of its [contentWidth], in a view [viewWidth] wide, is at its edge for a
         * finger moving [fingerDx]: moving right, it would scroll the page left, past its left edge; moving left, past
         * its right one. A page no wider than its view is at both. Within half a point counts, for rounding.
         */
        fun atEdge(offsetX: Double, contentWidth: Double, viewWidth: Double, fingerDx: Float): Boolean = when {
            fingerDx > 0f -> offsetX <= 0.5
            fingerDx < 0f -> offsetX >= contentWidth - viewWidth - 0.5
            else -> false
        }

        /**
         * A script asking whether the element at [x], [y] (CSS px from the visual viewport's corner), or one of its
         * ancestors below the page's own scroller, can still scroll the way a finger moving [direction] pushes it: -1,
         * the finger moving left, scrolls content right. It evaluates to true or false.
         */
        fun innerScrollQuestion(x: Float, y: Float, direction: Int): String =
            "(function(x,y,d){var v=window.visualViewport,e=document.elementFromPoint(x+(v?v.offsetLeft:0),y+(v?v.offsetTop:0))," +
                "r=document.scrollingElement;" +
                "for(;e&&e!==r&&e!==document.documentElement&&e!==document.body;e=e.parentElement){" +
                "var o=getComputedStyle(e).overflowX,m=e.scrollWidth-e.clientWidth,l=Math.abs(e.scrollLeft);" +
                "if((o==='auto'||o==='scroll')&&m>1&&(d<0?l<m-1:l>1))return true}" +
                "return false})($x,$y,$direction)"

        /** The script's result as the engine gets it: anything but true (no script, an error) means nothing scrolls. */
        fun readAnswer(result: String?): Boolean = result?.trim() == "true"
    }
}
