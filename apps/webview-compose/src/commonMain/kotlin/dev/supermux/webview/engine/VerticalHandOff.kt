package dev.supermux.webview.engine

import kotlin.math.abs

/** An axis a drag can pan along, for [VerticalHandOff.claimsQuestion]. */
internal enum class PanAxis { Horizontal, Vertical }

/**
 * One gesture's vertical hand-off from a pane's page to the scrolling list around it, as [SideSwipe] is sideways. A pane
 * (on iOS, a web view that gets every touch on it) still lets the chat scroll by a drag over its plain content: what the
 * page doesn't claim of a mostly vertical, one-finger drag is offered to the list; once it takes some, the rest of the
 * gesture is [taken]: the list's, both ways, until the finger lifts. A pinch never is, even after its second finger lifts.
 *
 * The page is asked first ([ask], [claimsQuestion]): an element under the finger that claims the drag (a canvas with
 * `touch-action: none`, an inner scroller that can still go that way) keeps it, for the whole gesture. Nothing is offered
 * until the page answers; an unclaimed drag that turns the other way asks again.
 */
internal class VerticalHandOff {
    private var totalX = 0f
    private var totalY = 0f
    private var pinch = false
    private var fraction = 0f

    /** The way the page was asked about (-1 the finger moving up, 1 down, 0 not asked), and what it said. */
    private var askedWay = 0
    private var claims: Boolean? = null
    /** The page claimed the drag: the gesture is its own until the finger lifts. */
    private var claimed = false

    /** Counts gestures, so an answer for an earlier one can be told apart. */
    var gesture = 0
        private set

    /** Whether the list took this gesture: the page gets no more of it. */
    var taken = false
        private set

    fun down() {
        totalX = 0f
        totalY = 0f
        pinch = false
        fraction = 0f
        taken = false
        askedWay = 0
        claims = null
        claimed = false
        gesture++
    }

    /** The finger moved by [dx], [dy] px, with [pointers] fingers down. */
    fun moved(dx: Float, dy: Float, pointers: Int) {
        if (pointers > 1) pinch = true
        totalX += dx
        totalY += dy
    }

    /** Whether what the page left of a vertical drag may go to the list. */
    fun mayOffer(): Boolean = vertical() && askedWay == way() && claims == false

    /** The way to ask the page about now, or null: not a one-finger vertical drag, claimed, or asked about already. */
    fun ask(): Int? {
        if (!vertical() || way() == askedWay) return null
        askedWay = way()
        claims = null
        return askedWay
    }

    /** The page's answer for [way]: whether something under the finger claims the drag. */
    fun answered(way: Int, claims: Boolean) {
        if (way != askedWay) return
        this.claims = claims
        if (claims) claimed = true
    }

    private fun vertical() = !taken && !claimed && !pinch && abs(totalY) > abs(totalX)

    private fun way() = if (totalY < 0f) -1 else if (totalY > 0f) 1 else 0

    fun take() {
        taken = true
    }

    /** [px] as whole px for the list, the fraction carried to the next. */
    fun whole(px: Float): Int {
        fraction += px
        val n = fraction.toInt()
        fraction -= n
        return n
    }

    companion object {
        /**
         * Whether a page scrolled to [offsetY] of its [contentHeight], in a view [viewHeight] tall, is at its edge for a
         * finger moving [fingerDy]: moving down, past its top; moving up, past its bottom. A page that fits is at both.
         */
        fun atEdge(offsetY: Double, contentHeight: Double, viewHeight: Double, fingerDy: Float): Boolean =
            SideSwipe.atEdge(offsetY, contentHeight, viewHeight, fingerDy)

        /**
         * A script asking whether the element at [x], [y] (CSS px from the visual viewport's corner), or one of its
         * ancestors below the page's own scroller, claims a drag along [axis] the way a finger moving [direction] (-1 up
         * or left, 1 down or right) goes. It does if its computed `touch-action` keeps the browser from panning that axis
         * at all (none; vertically also pan-x, pinch-zoom, pan-left or pan-right without pan-y), or if it can itself
         * still scroll that way (overflow auto or scroll, with room). It evaluates to true or false.
         */
        fun claimsQuestion(x: Float, y: Float, axis: PanAxis, direction: Int): String =
            "(function(x,y,a,d){var v=window.visualViewport,e=document.elementFromPoint(x+(v?v.offsetLeft:0),y+(v?v.offsetTop:0))," +
                "r=document.scrollingElement,p=a==='y'?/\\bpan-(y|up|down)\\b/:/\\bpan-(x|left|right)\\b/;" +
                "for(;e&&e!==r&&e!==document.documentElement&&e!==document.body;e=e.parentElement){" +
                "var s=getComputedStyle(e),t=s.touchAction;" +
                "if(t&&t!=='auto'&&t!=='manipulation'&&!p.test(t))return true;" +
                "var o=a==='y'?s.overflowY:s.overflowX,m=a==='y'?e.scrollHeight-e.clientHeight:e.scrollWidth-e.clientWidth," +
                "l=Math.abs(a==='y'?e.scrollTop:e.scrollLeft);" +
                "if((o==='auto'||o==='scroll')&&m>1&&(d<0?l<m-1:l>1))return true}" +
                "return false})($x,$y,'${if (axis == PanAxis.Vertical) "y" else "x"}',$direction)"

        /** The script's result as the engine gets it: anything but true (no script, an error) means nothing claims. */
        fun readAnswer(result: String?): Boolean = SideSwipe.readAnswer(result)
    }
}
