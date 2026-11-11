package dev.supermux.webview.engine.ios

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import dev.supermux.webview.engine.PanAxis
import dev.supermux.webview.engine.SideSwipe
import dev.supermux.webview.engine.VerticalHandOff
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
 * The page's hand-off to the views around it, as on Android ([SideSwipe], [VerticalHandOff]), riding the scroll view's
 * own pan, so pinch and the page's own scrolling are WebKit's as ever. The finger is read in the window, since the views
 * around may move this one under it.
 * - Sideways (the phone's tab swipe): a page that can't scroll sideways, or one scrolled to its edge, hands the rest of a
 *   one-finger sideways drag to [parents].
 * - Vertically, only in a [pane] (a view that gets every touch on it, so the list around it can't take a drag itself):
 *   a page that can't scroll further that way hands a one-finger vertical drag to [parents] unless what is under the
 *   finger claims it (a canvas with `touch-action: none`, an inner scroller with room). Over a `touch-action: none`
 *   element WebKit may not start the pan at all: that drag is the page's anyway.
 *
 * Once the parents take some, the gesture is theirs until the finger lifts: the page stays pinned at its edge, both
 * ways, and the finger's moves and the fling go to them. Until the page answers its question, nothing is handed on.
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal class PageSideSwipe(
    private val scrollView: UIScrollView,
    /** Runs a script in the page; the result as text ("true"), or null. */
    private val evaluate: (script: String, done: (String?) -> Unit) -> Unit,
) : NSObject() {
    /** The views around, while the surface is composed; and Compose's px per point. */
    var parents: Parents? = null
    var density: Float = 1f
    /** Whether the surface is a pane ([dev.supermux.webview.WebSurfaceHints.inScrollingList] false). */
    var pane: Boolean = false

    private val side = SideSwipe()
    private val vertical = VerticalHandOff()
    private var lastX = 0.0
    private var lastY = 0.0
    private var pinnedX = 0.0
    private var pinnedY = 0.0
    /** Where the finger went down, in the page's CSS px from the visible corner. */
    private var downX = 0.0
    private var downY = 0.0

    init {
        scrollView.panGestureRecognizer.addTarget(this, NSSelectorFromString("pan:"))
    }

    fun remove() {
        scrollView.panGestureRecognizer.removeTarget(this, NSSelectorFromString("pan:"))
        parents = null
    }

    @ObjCAction
    fun pan(recognizer: UIPanGestureRecognizer) {
        val state = recognizer.state
        if (state == UIGestureRecognizerStateBegan) {
            val zoom = scrollView.zoomScale.takeIf { it > 0.0 } ?: 1.0
            val at = recognizer.locationInView(scrollView.superview ?: scrollView).useContents { x to y }
            val start = recognizer.translationInView(scrollView.superview ?: scrollView).useContents { x to y }
            began((at.first - start.first) / zoom, (at.second - start.second) / zoom)
        }
        when (state) {
            UIGestureRecognizerStateBegan, UIGestureRecognizerStateChanged -> {
                val (tx, ty) = recognizer.translationInView(null).useContents { x to y }
                moved(tx, ty, recognizer.numberOfTouches.toInt())
            }
            UIGestureRecognizerStateEnded, UIGestureRecognizerStateCancelled -> {
                val (vx, vy) =
                    if (state == UIGestureRecognizerStateEnded) recognizer.velocityInView(null).useContents { x to y }
                    else 0.0 to 0.0
                ended(vx, vy)
            }
            else -> Unit
        }
    }

    /** A pan began with the finger down at [x], [y]: the page's CSS px from the visible corner. */
    internal fun began(x: Double, y: Double) {
        side.down()
        vertical.down()
        lastX = 0.0
        lastY = 0.0
        downX = x
        downY = y
    }

    /** The pan's translation in the window is [tx], [ty] points, with [pointers] fingers down. */
    internal fun moved(tx: Double, ty: Double, pointers: Int) {
        val dx = (tx - lastX).toFloat()
        val dy = (ty - lastY).toFloat()
        lastX = tx
        lastY = ty
        side.moved(dx, dy, pointers)
        vertical.moved(dx, dy, pointers)
        when {
            side.taken -> {
                pin()
                send(Offset(dx, 0f))
            }
            vertical.taken -> {
                pin()
                send(Offset(0f, dy))
            }
            else -> {
                sideways(dx)
                if (!side.taken && pane) vertically(dy)
            }
        }
    }

    /** The pan ended with the finger moving at [vx], [vy] points a second (zero when cancelled). */
    internal fun ended(vx: Double, vy: Double) {
        if (side.taken) fling(Velocity(vx.toFloat() * density, 0f))
        else if (vertical.taken) fling(Velocity(0f, vy.toFloat() * density))
        side.down()
        vertical.down()
    }

    private fun sideways(dx: Float) {
        if (dx == 0f || !atEdgeX(dx)) return
        if (side.ask()?.let(::askSideways) != null) return // Nothing is handed on until the page answers.
        if (side.mayOffer() && send(Offset(dx, 0f)) != 0f) {
            side.take()
            pinnedX = edgeX(dx)
            pinnedY = scrollView.contentOffset.useContents { y }
            pin()
        }
    }

    private fun vertically(dy: Float) {
        if (dy == 0f || !atEdgeY(dy)) return
        if (vertical.ask()?.let(::askClaims) != null) return // Nothing is handed on until the page answers.
        if (vertical.mayOffer() && send(Offset(0f, dy)) != 0f) {
            vertical.take()
            pinnedX = scrollView.contentOffset.useContents { x }
            pinnedY = edgeY(dy)
            pin()
        }
    }

    /**
     * Asks the page whether the box under the finger (a carousel, a `<pre>`, a wide table) can still scroll the [way] the
     * finger pushes: if it can, the gesture stays the page's.
     */
    private fun askSideways(way: Int) {
        val gesture = side.gesture
        evaluate(SideSwipe.innerScrollQuestion(downX.toFloat(), downY.toFloat(), way)) { result ->
            if (side.gesture == gesture) side.answered(way, SideSwipe.readAnswer(result))
        }
    }

    /** Asks the page whether what is under the finger claims a vertical drag the [way] it goes. */
    private fun askClaims(way: Int) {
        val gesture = vertical.gesture
        evaluate(VerticalHandOff.claimsQuestion(downX.toFloat(), downY.toFloat(), PanAxis.Vertical, way)) { result ->
            if (vertical.gesture == gesture) vertical.answered(way, VerticalHandOff.readAnswer(result))
        }
    }

    private fun atEdgeX(dx: Float): Boolean = scrollView.run {
        val inset = adjustedContentInset.useContents { left to right }
        SideSwipe.atEdge(
            offsetX = contentOffset.useContents { x } + inset.first,
            contentWidth = contentSize.useContents { width } + inset.first + inset.second,
            viewWidth = bounds.useContents { size.width },
            fingerDx = dx,
        )
    }

    private fun atEdgeY(dy: Float): Boolean = scrollView.run {
        val inset = adjustedContentInset.useContents { top to bottom }
        VerticalHandOff.atEdge(
            offsetY = contentOffset.useContents { y } + inset.first,
            contentHeight = contentSize.useContents { height } + inset.first + inset.second,
            viewHeight = bounds.useContents { size.height },
            fingerDy = dy,
        )
    }

    /** The offset the page rests at, at the edge [dx] pushes against. */
    private fun edgeX(dx: Float): Double = scrollView.run {
        val left = -adjustedContentInset.useContents { left }
        if (dx > 0f) left
        else maxOf(left, contentSize.useContents { width } + adjustedContentInset.useContents { right } - bounds.useContents { size.width })
    }

    /** The offset the page rests at, at the edge [dy] pushes against. */
    private fun edgeY(dy: Float): Double = scrollView.run {
        val top = -adjustedContentInset.useContents { top }
        if (dy > 0f) top
        else maxOf(top, contentSize.useContents { height } + adjustedContentInset.useContents { bottom } - bounds.useContents { size.height })
    }

    /** Holds the page where the hand-off found it: the sideways one pins x, the vertical one y. */
    private fun pin() {
        val (x, y) = scrollView.contentOffset.useContents { x to y }
        scrollView.setContentOffset(CGPointMake(if (side.taken) pinnedX else x, if (vertical.taken) pinnedY else y), animated = false)
    }

    /** Hands [points] of the finger to the views around as a child's left-over scroll; what they took, in px. */
    private fun send(points: Offset): Float {
        val p = parents ?: return 0f
        val taken = p.scroll(points * density)
        return if (points.x != 0f) taken.x else taken.y
    }

    /** Always, at the end of a taken gesture, so the views around settle where it leaves them. */
    private fun fling(velocity: Velocity) {
        parents?.fling(velocity)
    }

    /** Where the page's hand-off goes: the views around it. */
    interface Parents {
        /** Offers [delta] px, the finger's way, as a child's left-over scroll; what they took. */
        fun scroll(delta: Offset): Offset

        /** Flings them at [velocity] px a second: pre-fling always, then post-fling with what is left. */
        fun fling(velocity: Velocity)
    }
}

/** The surface's parents, through Compose's nested scroll. */
internal class DispatcherParents(private val dispatcher: NestedScrollDispatcher) : PageSideSwipe.Parents {
    override fun scroll(delta: Offset): Offset {
        val before = dispatcher.dispatchPreScroll(delta, NestedScrollSource.UserInput)
        val after = dispatcher.dispatchPostScroll(Offset.Zero, delta - before, NestedScrollSource.UserInput)
        return before + after
    }

    override fun fling(velocity: Velocity) {
        // Only an attached dispatcher has a scope to fling in.
        val scope = try {
            dispatcher.coroutineScope
        } catch (e: IllegalStateException) {
            return
        }
        scope.launch {
            val before = dispatcher.dispatchPreFling(velocity)
            dispatcher.dispatchPostFling(Velocity.Zero, velocity - before)
        }
    }
}

/** The surface only hands scroll on; it takes none from its children. */
internal object NoNestedScroll : NestedScrollConnection
