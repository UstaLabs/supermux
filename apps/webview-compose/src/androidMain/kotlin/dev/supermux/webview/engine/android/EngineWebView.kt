package dev.supermux.webview.engine.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
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
 * - The page gets the touch as if the view were still where it was when the finger went down. As the parents scroll
 *   the view under the finger, the page would take that for the finger moving, and hand it back to them: on the phone,
 *   the list shook under a steady drag, further each move.
 * - It takes its IME inset from [imeBottom], which the Surface sets from Compose's insets: WebView 139+ then shrinks its
 *   visual viewport by the part of the page the keyboard covers, whatever the views above it did with the insets.
 */
@SuppressLint("ViewConstructor")
internal class EngineWebView(context: Context, private val onFocus: (Boolean) -> Unit) : WebView(context), NestedScrollingChild3 {
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touching = true
            dragReachedParent = false
            overscrolled = 0f
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
            getLocationInWindow(downAt)
            // The gesture stays the page's: what it can't take reaches the parents through nested scrolling.
            if (startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        if (!touching) return super.onTouchEvent(event)
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

    private fun endTouch() {
        touching = false
        velocity?.recycle()
        velocity = null
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
        if (touching && deltaY != 0 && hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)) {
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
