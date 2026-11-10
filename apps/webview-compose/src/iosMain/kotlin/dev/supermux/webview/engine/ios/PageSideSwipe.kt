package dev.supermux.webview.engine.ios

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import dev.supermux.webview.engine.SideSwipe
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGPointMake
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIGestureRecognizerStateBegan
import platform.UIKit.UIGestureRecognizerStateCancelled
import platform.UIKit.UIGestureRecognizerStateChanged
import platform.UIKit.UIGestureRecognizerStateEnded
import platform.UIKit.UIPanGestureRecognizer
import platform.UIKit.UIScrollView
import platform.darwin.NSObject

/**
 * The page's sideways hand-off to the views around it (the phone's tab swipe), as on Android ([SideSwipe]): a page that
 * can't scroll sideways, or one scrolled to its edge, hands the rest of a one-finger sideways drag to [dispatcher]. Once
 * it takes some, the gesture is its own until the finger lifts: the page stays pinned at its edge, both ways, and the
 * finger's moves and the fling go to it. It rides the scroll view's own pan, so pinch and vertical scrolling are WebKit's
 * as ever. The finger is read in the window, since the views around may move this one under it.
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal class PageSideSwipe(
    private val scrollView: UIScrollView,
    /** Runs a script in the page; the result as text ("true"), or null. */
    private val evaluate: (script: String, done: (String?) -> Unit) -> Unit,
) : NSObject() {
    /** The surface's, while it is composed; and Compose's px per point. */
    var dispatcher: NestedScrollDispatcher? = null
    var density: Float = 1f

    private val side = SideSwipe()
    private var lastX = 0.0
    private var lastY = 0.0
    private var pinnedX = 0.0
    /** Where the finger went down, in the page's CSS px from the visible corner. */
    private var downX = 0.0
    private var downY = 0.0

    init {
        scrollView.panGestureRecognizer.addTarget(this, NSSelectorFromString("pan:"))
    }

    fun remove() {
        scrollView.panGestureRecognizer.removeTarget(this, NSSelectorFromString("pan:"))
        dispatcher = null
    }

    @ObjCAction
    fun pan(recognizer: UIPanGestureRecognizer) {
        val state = recognizer.state
        if (state == UIGestureRecognizerStateBegan) {
            side.down()
            lastX = 0.0
            lastY = 0.0
            val zoom = scrollView.zoomScale.takeIf { it > 0.0 } ?: 1.0
            val at = recognizer.locationInView(scrollView.superview ?: scrollView).useContents { x to y }
            val start = recognizer.translationInView(scrollView.superview ?: scrollView).useContents { x to y }
            downX = (at.first - start.first) / zoom
            downY = (at.second - start.second) / zoom
        }
        val (tx, ty) = recognizer.translationInView(null).useContents { x to y }
        val dx = (tx - lastX).toFloat()
        val dy = (ty - lastY).toFloat()
        lastX = tx
        lastY = ty
        when (state) {
            UIGestureRecognizerStateBegan, UIGestureRecognizerStateChanged -> {
                side.moved(dx, dy, recognizer.numberOfTouches.toInt())
                if (side.taken) {
                    pin()
                    send(dx)
                } else if (dx != 0f && atEdge(dx) && side.ask()?.let(::askPage) != null) {
                    // Asked the page; nothing is handed on until it answers.
                } else if (dx != 0f && side.mayOffer() && atEdge(dx) && send(dx) != 0f) {
                    side.take()
                    pinnedX = edge(dx)
                    pin()
                }
            }
            UIGestureRecognizerStateEnded, UIGestureRecognizerStateCancelled -> {
                if (side.taken) {
                    val vx = if (state == UIGestureRecognizerStateEnded) recognizer.velocityInView(null).useContents { x } else 0.0
                    fling(vx.toFloat() * density)
                }
                side.down()
            }
            else -> Unit
        }
    }

    /**
     * Asks the page whether the box under the finger (a carousel, a `<pre>`, a wide table) can still scroll the [way] the
     * finger pushes: if it can, the gesture stays the page's.
     */
    private fun askPage(way: Int) {
        val gesture = side.gesture
        evaluate(SideSwipe.innerScrollQuestion(downX.toFloat(), downY.toFloat(), way)) { result ->
            if (side.gesture == gesture) side.answered(way, SideSwipe.readAnswer(result))
        }
    }

    private fun atEdge(dx: Float): Boolean = scrollView.run {
        val inset = adjustedContentInset.useContents { left to right }
        SideSwipe.atEdge(
            offsetX = contentOffset.useContents { x } + inset.first,
            contentWidth = contentSize.useContents { width } + inset.first + inset.second,
            viewWidth = bounds.useContents { size.width },
            fingerDx = dx,
        )
    }

    /** The offset the page rests at, at the edge [dx] pushes against. */
    private fun edge(dx: Float): Double = scrollView.run {
        val left = -adjustedContentInset.useContents { left }
        if (dx > 0f) left
        else maxOf(left, contentSize.useContents { width } + adjustedContentInset.useContents { right } - bounds.useContents { size.width })
    }

    private fun pin() {
        val y = scrollView.contentOffset.useContents { y }
        scrollView.setContentOffset(CGPointMake(pinnedX, y), animated = false)
    }

    /** Hands [dx] points of the finger to the views around as a child's left-over scroll; what they took, in px. */
    private fun send(dx: Float): Float {
        val d = dispatcher ?: return 0f
        val delta = Offset(dx * density, 0f)
        val before = d.dispatchPreScroll(delta, NestedScrollSource.UserInput)
        val after = d.dispatchPostScroll(Offset.Zero, delta - before, NestedScrollSource.UserInput)
        return (before + after).x
    }

    /** Always, at the end of a taken gesture, so the views around settle where it leaves them. */
    private fun fling(vx: Float) {
        val d = dispatcher ?: return
        // Only an attached dispatcher has a scope to fling in.
        val scope = try {
            d.coroutineScope
        } catch (e: IllegalStateException) {
            return
        }
        scope.launch {
            val velocity = Velocity(vx, 0f)
            val before = d.dispatchPreFling(velocity)
            d.dispatchPostFling(Velocity.Zero, velocity - before)
        }
    }
}

/** The surface only hands scroll on; it takes none from its children. */
internal object NoNestedScroll : NestedScrollConnection
