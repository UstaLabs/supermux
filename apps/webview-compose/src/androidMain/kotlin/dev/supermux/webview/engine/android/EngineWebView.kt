package dev.supermux.webview.engine.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.webkit.WebView
import androidx.core.graphics.Insets
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.supermux.webview.engine.SideSwipe
import dev.supermux.webview.engine.VerticalHandOff
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The engine's WebView.
 * - It reports keyboard focus, which Compose never sees over a native view.
 * - It is a [NestedScrollingChild3], which WebView isn't (spec §7), and it keeps the gesture: it asks its parents not
 *   to intercept it, or Compose's list would take a drag past its touch slop from the page. WebView scrolls the page on
 *   its own compositor and reports the part of a drag the page couldn't take through [overScrollBy]: that part goes to
 *   the scrolling parents (Compose's, through AndroidView) while the finger is down. So does the gesture's fling, if
 *   its drag reached them: a flick the page took itself (an inner scroller, a carousel, a canvas) stays the page's.
 * - Sideways it is the same, up to the page's edge ([SideSwipe]): a page that can't scroll sideways, or one scrolled to
 *   its edge, hands the rest of a one-finger sideways drag to the parents (the phone's tab swipe). Once they take some,
 *   the page gets a cancel and the gesture is theirs, both ways, until the finger lifts, so the page doesn't scroll back
 *   under a half-moved tab. A pinch never hands off; a zoomed-in page scrolls to its edge first.
 * - The page gets the touch as if the view were still where it was when the finger went down. As the parents scroll
 *   the view under the finger, the page would take that for the finger moving, and hand it back to them: on the phone,
 *   the list shook under a steady drag, further each move.
 * - It takes its IME inset from [imeBottom], which the Surface sets from Compose's insets: WebView 139+ then shrinks its
 *   visual viewport by the part of the page the keyboard covers, whatever the views above it did with the insets.
 */
@SuppressLint("ViewConstructor")
internal class EngineWebView(
    context: Context,
    private val onFocus: (Boolean) -> Unit,
    /** The user pressed the view or typed a key into it: real input, from the system, which no page script can make. */
    private val onInput: () -> Unit = {},
) : WebView(context), NestedScrollingChild3 {
    /** Whether the engine destroyed it. Nothing touches it after that. */
    var gone: Boolean = false

    // Nullable: View's constructor may call the nested-scrolling setters before this class's fields are set.
    private var nested: NestedScrollingChildHelper? = null
    private val consumed = IntArray(2)
    private var velocity: VelocityTracker? = null
    private var touching = false

    /** Whether the parents took part of this gesture's drag: only then is its fling theirs too. */
    private var dragReachedParent = false
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    /** Where the view was in its window when the finger went down, and where it is now. */
    private val downAt = IntArray(2)
    private val nowAt = IntArray(2)

    /** What the page couldn't take of this gesture's drag, in px, and the parents weren't handed yet: a fraction. */
    private var overscrolled = 0f

    /** This gesture's sideways hand-off, and where the finger was last, on the screen. */
    private val side = SideSwipe()
    private var lastRawX = 0f
    private var lastRawY = 0f
    /** The finger's velocity on the screen, which the parents' fling follows. */
    private var screenVelocity: VelocityTracker? = null
    private var pageCancelled = false

    /** This gesture's vertical hand-off, and how far the finger went up or down, in px, before the parents took it. */
    private val vertical = VerticalHandOff()
    private var pendingY = 0f

    /** The page's answers for this gesture, asked at the down: whether it claims a drag up ([0]) and down ([1]). */
    private val claimsWay = arrayOfNulls<Boolean>(2)

    /** A gesture that ended before the page answered: what the finger went and its fling, for when it does. */
    private class Late(val gesture: Int, val way: Int, val dy: Float, val fling: Float)
    private var late: Late? = null
    /** Where the finger went down, in this view's px: the page is asked what is there. */
    private var downX = 0f
    private var downY = 0f

    init {
        nested = NestedScrollingChildHelper(this).also { it.isNestedScrollingEnabled = true }
    }

    /** The keyboard's height over the bottom of the window, in px, as Compose sees it. WebView gets it as the IME inset. */
    var imeBottom: Int = 0
        set(value) {
            if (field == value) return
            field = value
            val root = ViewCompat.getRootWindowInsets(this)?.toWindowInsets() ?: return
            dispatchApplyWindowInsets(root)
        }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val withIme = WindowInsetsCompat.Builder(WindowInsetsCompat.toWindowInsetsCompat(insets, this))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
            .setVisible(WindowInsetsCompat.Type.ime(), imeBottom > 0)
            .build()
            .toWindowInsets() ?: insets
        return super.onApplyWindowInsets(withIme)
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        onFocus(focused)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!gone && event.actionMasked == MotionEvent.ACTION_DOWN) onInput()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!gone && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onInput()
        return super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touching = true
            dragReachedParent = false
            overscrolled = 0f
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
            getLocationInWindow(downAt)
            side.down()
            pageCancelled = false
            downX = event.x
            downY = event.y
            lastRawX = event.rawX
            lastRawY = event.rawY
            screenVelocity?.recycle()
            screenVelocity = VelocityTracker.obtain()
            vertical.down()
            pendingY = 0f
            late = null
            claimsWay.fill(null)
            // The gesture stays the page's: what it can't take reaches the parents through nested scrolling.
            val axes = ViewCompat.SCROLL_AXIS_VERTICAL or ViewCompat.SCROLL_AXIS_HORIZONTAL
            if (startNestedScroll(axes, ViewCompat.TYPE_TOUCH)) {
                parent?.requestDisallowInterceptTouchEvent(true)
                // Asked now, both ways, so the answer is usually in before the finger passes the touch slop.
                askPageVertically(-1)
                askPageVertically(1)
            }
        }
        if (!touching) return super.onTouchEvent(event)
        val dx = event.rawX - lastRawX
        lastRawX = event.rawX
        val dy = event.rawY - lastRawY
        lastRawY = event.rawY
        screenVelocity?.let { tracker ->
            val onScreen = MotionEvent.obtain(event)
            onScreen.setLocation(event.rawX, event.rawY)
            tracker.addMovement(onScreen)
            onScreen.recycle()
        }
        if (handingOffVertically(event, dy)) return true
        if (sideSwiping(event, dx, dy)) return true
        // The touch where it would be had the view stayed where it was at the down.
        getLocationInWindow(nowAt)
        val steady = MotionEvent.obtain(event)
        steady.offsetLocation((nowAt[0] - downAt[0]).toFloat(), (nowAt[1] - downAt[1]).toFloat())
        val handled = try {
            velocity?.addMovement(steady)
            super.onTouchEvent(steady)
        } finally {
            steady.recycle()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                velocity?.let { tracker ->
                    tracker.computeCurrentVelocity(1000, maxFling.toFloat())
                    val fling = -tracker.yVelocity
                    val direction = if (fling > 0) 1 else -1
                    val parents = dragReachedParent && abs(fling) >= minFling && !canScrollVertically(direction)
                    if (parents && !dispatchNestedPreFling(0f, fling)) {
                        dispatchNestedFling(0f, fling, false)
                    }
                }
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> endTouch()
        }
        return handled
    }

    /**
     * Feeds [side] the finger's move, on the screen (the parents move this view under it). Once the parents took the
     * gesture, it is theirs: the page gets one cancel, the parents every move and, at the end, the fling, always, so
     * they settle where it leaves them. True when the event was theirs.
     */
    private fun sideSwiping(event: MotionEvent, dx: Float, dy: Float): Boolean {
        if (vertical.taken) return false
        if (event.actionMasked == MotionEvent.ACTION_MOVE) side.moved(dx, dy, event.pointerCount)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) side.moved(0f, 0f, event.pointerCount)
        if (!side.taken) return false
        cancelPage(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // Nested scrolling's dx is the content's: a finger moving left scrolls it right.
                val n = side.whole(-dx)
                if (n != 0) dispatchNestedScroll(0, 0, n, 0, null, ViewCompat.TYPE_TOUCH, consumed)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                var fling = 0f
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    screenVelocity?.let { tracker ->
                        tracker.computeCurrentVelocity(1000, maxFling.toFloat())
                        fling = -tracker.xVelocity
                    }
                }
                dispatchNestedPreFling(fling, 0f)
                endTouch()
            }
        }
        return true
    }

    /**
     * Feeds [vertical] the finger's move ([dy], on the screen). A mostly vertical drag the page doesn't claim (nothing
     * under the finger handles touches or scrolls that way, and the document can't scroll that way either) is the
     * parents' once the page says so: they get the finger's moves on the screen, from the first, and its fling. Not
     * WebView's overscroll: it comes a move late, so the last move before the finger lifts was lost, and over a view
     * taller than the screen it reports a scroll range for a page laid out exactly as tall as its view, scaled one way
     * and not the other, so a drag up moved the list 38 % of the finger, and a flick never flung it (#162). True when the
     * event was theirs.
     */
    private fun handingOffVertically(event: MotionEvent, dy: Float): Boolean {
        if (side.taken) return false
        if (!vertical.taken) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    vertical.moved(event.rawX - lastRawX, dy, event.pointerCount)
                    pendingY += dy
                }
                MotionEvent.ACTION_POINTER_DOWN -> vertical.moved(0f, 0f, event.pointerCount)
            }
            if (!hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)) return false
            vertical.ask()?.let { way -> claimsWay[slot(way)]?.let { vertical.answered(way, it) } }
            if (!vertical.mayOffer()) {
                if (event.actionMasked == MotionEvent.ACTION_UP && vertical.awaiting) {
                    // The finger lifted before the page answered: the answer decides then.
                    screenVelocity?.let { tracker ->
                        tracker.computeCurrentVelocity(1000, maxFling.toFloat())
                        late = Late(vertical.gesture, vertical.way, pendingY, -tracker.yVelocity)
                    }
                }
                return false
            }
            takeVertically(event)
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val n = vertical.whole(-dy)
            if (n != 0) dispatchNestedScroll(0, 0, 0, n, null, ViewCompat.TYPE_TOUCH, consumed)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    screenVelocity?.let { tracker ->
                        tracker.computeCurrentVelocity(1000, maxFling.toFloat())
                        val fling = -tracker.yVelocity
                        if (abs(fling) >= minFling && !dispatchNestedPreFling(0f, fling)) dispatchNestedFling(0f, fling, false)
                    }
                }
                endTouch()
            }
        }
        return true
    }

    /** The parents take this gesture's vertical drag: the page gets a cancel, they get what the finger went so far. */
    private fun takeVertically(event: MotionEvent?) {
        vertical.take()
        cancelPage(event)
        val n = vertical.whole(-pendingY)
        pendingY = 0f
        if (n != 0) dispatchNestedScroll(0, 0, 0, n, null, ViewCompat.TYPE_TOUCH, consumed)
    }

    /** Ends the page's part of this gesture, once: [event] as a cancel, or a cancel made now when there is none. */
    private fun cancelPage(event: MotionEvent?) {
        if (pageCancelled) return
        pageCancelled = true
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = if (event != null) MotionEvent.obtain(event) else MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.onTouchEvent(cancel)
        cancel.recycle()
    }

    private fun slot(way: Int) = if (way < 0) 0 else 1

    /**
     * Asks the page whether it claims a vertical drag the [way] the finger goes ([VerticalHandOff.claimsOrScrollsQuestion])
     * at the point it went down. Until it answers, nothing is handed on; once it says no, the parents take the gesture,
     * even if the finger has stopped, or, if it has lifted, get what it went and its fling then. Only a plain false counts
     * as no: a page that runs no script (a browser tab with scripts off) keeps it.
     */
    private fun askPageVertically(way: Int) {
        val gesture = vertical.gesture
        @Suppress("DEPRECATION")
        val cssPx = scale
        evaluateJavascript(VerticalHandOff.claimsOrScrollsQuestion(downX / cssPx, downY / cssPx, way)) { result ->
            if (gone || vertical.gesture != gesture) return@evaluateJavascript
            val claims = result?.trim() != "false"
            claimsWay[slot(way)] = claims
            if (vertical.way != way || !vertical.awaiting) return@evaluateJavascript
            vertical.answered(way, claims)
            if (touching) {
                if (!vertical.taken && !side.taken && vertical.mayOffer() && hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)) {
                    takeVertically(null)
                }
            } else {
                late?.takeIf { it.gesture == gesture && it.way == way && !claims }?.let(::finishLate)
                late = null
            }
        }
    }

    /** A gesture the page didn't claim, answered after the finger lifted: the parents get its drag and fling now. */
    private fun finishLate(gesture: Late) {
        if (!startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)) return
        val n = vertical.whole(-gesture.dy)
        if (n != 0) dispatchNestedScroll(0, 0, 0, n, null, ViewCompat.TYPE_TOUCH, consumed)
        stopNestedScroll(ViewCompat.TYPE_TOUCH)
        if (abs(gesture.fling) >= minFling && !dispatchNestedPreFling(0f, gesture.fling)) dispatchNestedFling(0f, gesture.fling, false)
    }

    private fun endTouch() {
        touching = false
        velocity?.recycle()
        velocity = null
        screenVelocity?.recycle()
        screenVelocity = null
        stopNestedScroll(ViewCompat.TYPE_TOUCH)
    }

    /** What the page couldn't take of a drag ([deltaY]) goes to the scrolling parents first. */
    override fun overScrollBy(
        deltaX: Int,
        deltaY: Int,
        scrollX: Int,
        scrollY: Int,
        scrollRangeX: Int,
        scrollRangeY: Int,
        maxOverScrollX: Int,
        maxOverScrollY: Int,
        isTouchEvent: Boolean,
    ): Boolean {
        if (touching && deltaX != 0 && hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)) side.ask()?.let(::askPage)
        if (touching && deltaX != 0 && side.mayOffer() && hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)) {
            // In the finger's px, as below.
            @Suppress("DEPRECATION")
            val dx = side.whole(deltaX / scale)
            consumed[0] = 0
            consumed[1] = 0
            if (dx != 0) dispatchNestedScroll(0, 0, dx, 0, null, ViewCompat.TYPE_TOUCH, consumed)
            if (consumed[0] != 0) {
                side.take()
                return false
            }
        }
        // Only a drag the page claimed (its own scrolling, up to its edge): any other is handingOffVertically's.
        if (touching && deltaY != 0 && vertical.claimed && hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)) {
            // WebView reports it multiplied by its scale, the density times the page's zoom: over a page that can't
            // scroll, each 24.6 px move came as 64 or 65 px on the phone (WebView 156, 2.625 px a dp), and the list ran
            // ahead of the finger. The parents get it in the finger's px, the fraction carried to the next.
            @Suppress("DEPRECATION")
            overscrolled += deltaY / scale
            val dy = overscrolled.roundToInt()
            overscrolled -= dy
            consumed[0] = 0
            consumed[1] = 0
            if (dy != 0) dispatchNestedScroll(0, 0, 0, dy, null, ViewCompat.TYPE_TOUCH, consumed)
            if (consumed[1] != 0) {
                dragReachedParent = true
                return false
            }
        }
        return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY, maxOverScrollX, maxOverScrollY, isTouchEvent)
    }

    /**
     * Asks the page whether the box under the finger (a carousel, a `<pre>`, a wide table) can still scroll the [way] the
     * finger pushes: until it answers, nothing is handed on; if it can, the gesture stays the page's.
     */
    private fun askPage(way: Int) {
        val gesture = side.gesture
        @Suppress("DEPRECATION")
        val cssPx = scale
        evaluateJavascript(SideSwipe.innerScrollQuestion(downX / cssPx, downY / cssPx, way)) { result ->
            if (!gone && side.gesture == gesture) side.answered(way, SideSwipe.readAnswer(result))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        nested?.onDetachedFromWindow()
    }

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        nested?.isNestedScrollingEnabled = enabled
    }

    override fun isNestedScrollingEnabled(): Boolean = nested?.isNestedScrollingEnabled ?: false

    override fun startNestedScroll(axes: Int): Boolean = nested?.startNestedScroll(axes) ?: false

    override fun startNestedScroll(axes: Int, type: Int): Boolean = nested?.startNestedScroll(axes, type) ?: false

    override fun stopNestedScroll() {
        nested?.stopNestedScroll()
    }

    override fun stopNestedScroll(type: Int) {
        nested?.stopNestedScroll(type)
    }

    override fun hasNestedScrollingParent(): Boolean = nested?.hasNestedScrollingParent() ?: false

    override fun hasNestedScrollingParent(type: Int): Boolean = nested?.hasNestedScrollingParent(type) ?: false

    override fun dispatchNestedScroll(dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, offsetInWindow: IntArray?): Boolean =
        nested?.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow) ?: false

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
    ): Boolean = nested?.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type) ?: false

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
        consumed: IntArray,
    ) {
        nested?.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type, consumed)
    }

    override fun dispatchNestedPreScroll(dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?): Boolean =
        nested?.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow) ?: false

    override fun dispatchNestedPreScroll(dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?, type: Int): Boolean =
        nested?.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type) ?: false

    override fun dispatchNestedFling(velocityX: Float, velocityY: Float, consumed: Boolean): Boolean =
        nested?.dispatchNestedFling(velocityX, velocityY, consumed) ?: false

    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float): Boolean =
        nested?.dispatchNestedPreFling(velocityX, velocityY) ?: false
}
