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
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.coroutines.launch
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import platform.CoreGraphics.CGPointMake
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIGestureRecognizer
import platform.UIKit.UIGestureRecognizerDelegateProtocol
import platform.UIKit.UIGestureRecognizerStateBegan
import platform.UIKit.UIGestureRecognizerStateCancelled
import platform.UIKit.UIGestureRecognizerStateChanged
import platform.UIKit.UIGestureRecognizerStateEnded
import platform.UIKit.UIPanGestureRecognizer
import platform.UIKit.UIScrollView
import platform.UIKit.UITouch
import platform.UIKit.UIView
import platform.darwin.NSObject

/**
 * The page's hand-off to the views around it, as on Android ([SideSwipe], [VerticalHandOff]). The finger is read in the
 * window, since the views around may move this one under it.
 * - Sideways (the phone's tab swipe), on the scroll view's own pan, so pinch and the page's scrolling are WebKit's as
 *   ever: a page that can't scroll sideways, or one scrolled to its edge, hands the rest of a one-finger sideways drag
 *   to [parents].
 * - Vertically, only in a [pane] (a view that gets every touch on it, so the list around it can't take a drag itself),
 *   on a pan of its own on [view] ([cardPan]): the scroll view's pan never begins over a page that fits and doesn't
 *   bounce, which is a card's. It takes one finger, recognizes with every other recognizer and holds back no touch, so
 *   the page gets every event as before. A page that can't scroll further that way hands a one-finger vertical drag to
 *   [parents] unless what is under the finger claims it (a canvas with `touch-action: none`, an inner scroller with
 *   room): the pan fires over such a canvas too, and the page's claim keeps the drag its own.
 *
 * Once the parents take some, the gesture is theirs until the finger lifts: the page stays pinned at its edge, both
 * ways, and the finger's moves and the fling go to them. Until the page answers its question, nothing is handed on.
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal class PageSideSwipe(
    /** The web view: the card's own pan sits on it. */
    private val view: UIView,
    private val scrollView: UIScrollView,
    /** Runs a script in the page; the result as text ("true"), or null. */
    private val evaluate: (script: String, done: (String?) -> Unit) -> Unit,
) : NSObject() {
    /** The views around, while the surface is composed; and Compose's px per point. */
    var parents: Parents? = null
    var density: Float = 1f

    /** Whether the surface is a pane ([dev.supermux.webview.WebSurfaceHints.inScrollingList] false): [cardPan] runs. */
    var pane: Boolean = false
        set(value) {
            field = value
            // Turned off mid-gesture, the pan ends cancelled: what the parents took settles.
            cardPan.enabled = value
        }

    private val side = SideSwipe()
    private val vertical = VerticalHandOff()
    // The scroll view's pan, sideways.
    private var sideLastX = 0.0
    private var sideLastY = 0.0
    private var pinnedX = 0.0
    // The card's pan, vertical.
    private var cardLastX = 0.0
    private var cardLastY = 0.0
    private var pinnedY = 0.0
    /** Where the finger went down, in the page's CSS px from the visible corner, as each pan saw it. */
    private var sideDownX = 0.0
    private var sideDownY = 0.0
    private var cardDownX = 0.0
    private var cardDownY = 0.0

    private val cardPanDelegate = CardPanDelegate(this)

    /** The pane's own one-finger pan, for the vertical hand-off: off unless the surface is a [pane]. */
    internal val cardPan = UIPanGestureRecognizer(target = this, action = NSSelectorFromString("cardPan:")).apply {
        maximumNumberOfTouches = 1u
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        delegate = cardPanDelegate
        enabled = false
    }

    init {
        scrollView.panGestureRecognizer.addTarget(this, NSSelectorFromString("pan:"))
        view.addGestureRecognizer(cardPan)
    }

    fun remove() {
        scrollView.panGestureRecognizer.removeTarget(this, NSSelectorFromString("pan:"))
        cardPan.removeTarget(this, NSSelectorFromString("cardPan:"))
        view.removeGestureRecognizer(cardPan)
        parents = null
    }

    @ObjCAction
    fun pan(recognizer: UIPanGestureRecognizer) {
        val state = recognizer.state
        if (state == UIGestureRecognizerStateBegan) {
            val (x, y) = downAt(recognizer, scrollView.superview ?: scrollView)
            sideBegan(x, y)
        }
        when (state) {
            UIGestureRecognizerStateBegan, UIGestureRecognizerStateChanged -> {
                val (tx, ty) = recognizer.translationInView(null).useContents { x to y }
                sideMoved(tx, ty, recognizer.numberOfTouches.toInt())
            }
            UIGestureRecognizerStateEnded, UIGestureRecognizerStateCancelled -> {
                val vx = if (state == UIGestureRecognizerStateEnded) recognizer.velocityInView(null).useContents { x } else 0.0
                sideEnded(vx)
            }
            else -> Unit
        }
    }

    @ObjCAction
    fun cardPan(recognizer: UIPanGestureRecognizer) {
        val state = recognizer.state
        if (state == UIGestureRecognizerStateBegan) {
            val (x, y) = downAt(recognizer, view)
            verticalBegan(x, y)
        }
        when (state) {
            UIGestureRecognizerStateBegan, UIGestureRecognizerStateChanged -> {
                val (tx, ty) = recognizer.translationInView(null).useContents { x to y }
                // It takes one finger; WebKit's pinch says when there are two.
                val pointers = if (webKitPinching()) 2 else recognizer.numberOfTouches.toInt()
                verticalMoved(tx, ty, pointers)
            }
            UIGestureRecognizerStateEnded, UIGestureRecognizerStateCancelled -> {
                val vy = if (state == UIGestureRecognizerStateEnded) recognizer.velocityInView(null).useContents { y } else 0.0
                verticalEnded(vy)
            }
            else -> Unit
        }
    }

    /** Where [recognizer]'s finger went down in [view], in the page's CSS px. */
    private fun downAt(recognizer: UIPanGestureRecognizer, view: UIView): Pair<Double, Double> {
        val zoom = scrollView.zoomScale.takeIf { it > 0.0 } ?: 1.0
        val at = recognizer.locationInView(view).useContents { x to y }
        val start = recognizer.translationInView(view).useContents { x to y }
        return (at.first - start.first) / zoom to (at.second - start.second) / zoom
    }

    private fun webKitPinching(): Boolean = scrollView.pinchGestureRecognizer?.state.let {
        it == UIGestureRecognizerStateBegan || it == UIGestureRecognizerStateChanged
    }

    // --- Sideways, on the scroll view's pan ---

    /** The scroll view's pan began with the finger down at [x], [y]: the page's CSS px from the visible corner. */
    internal fun sideBegan(x: Double, y: Double) {
        side.down()
        sideLastX = 0.0
        sideLastY = 0.0
        sideDownX = x
        sideDownY = y
    }

    /** The scroll view's pan is at [tx], [ty] points in the window, with [pointers] fingers down. */
    internal fun sideMoved(tx: Double, ty: Double, pointers: Int) {
        val dx = (tx - sideLastX).toFloat()
        val dy = (ty - sideLastY).toFloat()
        sideLastX = tx
        sideLastY = ty
        side.moved(dx, dy, pointers)
        when {
            side.taken -> {
                pin()
                send(Offset(dx, 0f))
            }
            // The card's pan has the gesture: the parents get it from there.
            !vertical.taken -> sideways(dx)
        }
    }

    /** The scroll view's pan ended with the finger moving at [vx] points a second (zero when cancelled). */
    internal fun sideEnded(vx: Double) {
        if (side.taken) fling(Velocity(vx.toFloat() * density, 0f))
        side.down()
    }

    // --- Vertically, on the card's pan ---

    /** The card's pan began with the finger down at [x], [y]: the page's CSS px from the visible corner. */
    internal fun verticalBegan(x: Double, y: Double) {
        vertical.down()
        cardLastX = 0.0
        cardLastY = 0.0
        cardDownX = x
        cardDownY = y
    }

    /** The card's pan is at [tx], [ty] points in the window, with [pointers] fingers down. */
    internal fun verticalMoved(tx: Double, ty: Double, pointers: Int) {
        val dx = (tx - cardLastX).toFloat()
        val dy = (ty - cardLastY).toFloat()
        cardLastX = tx
        cardLastY = ty
        vertical.moved(dx, dy, pointers)
        when {
            vertical.taken -> {
                pin()
                send(Offset(0f, dy))
            }
            pane && !side.taken -> vertically(dy)
        }
    }

    /** Another finger landed on the view while the card's pan runs: a pinch, which never hands off. */
    internal fun fingerLanded() {
        if (cardPan.state == UIGestureRecognizerStateBegan || cardPan.state == UIGestureRecognizerStateChanged) {
            vertical.moved(0f, 0f, pointers = 2)
        }
    }

    /** The card's pan ended with the finger moving at [vy] points a second (zero when cancelled). */
    internal fun verticalEnded(vy: Double) {
        if (vertical.taken) fling(Velocity(0f, vy.toFloat() * density))
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
        evaluate(SideSwipe.innerScrollQuestion(sideDownX.toFloat(), sideDownY.toFloat(), way)) { result ->
            if (side.gesture == gesture) side.answered(way, SideSwipe.readAnswer(result))
        }
    }

    /** Asks the page whether what is under the finger claims a vertical drag the [way] it goes. */
    private fun askClaims(way: Int) {
        val gesture = vertical.gesture
        evaluate(VerticalHandOff.claimsQuestion(cardDownX.toFloat(), cardDownY.toFloat(), PanAxis.Vertical, way)) { result ->
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

/**
 * The card's pan recognizes with every other recognizer (the page's, the scroll view's pan and pinch), and hears of every
 * finger that lands, though it tracks one.
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class, ExperimentalNativeApi::class)
private class CardPanDelegate(swipe: PageSideSwipe) : NSObject(), UIGestureRecognizerDelegateProtocol {
    // The swipe owns this delegate; the recognizer holds it weakly.
    private val swipe = WeakReference(swipe)

    @ObjCSignatureOverride
    override fun gestureRecognizer(
        gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWithGestureRecognizer: UIGestureRecognizer,
    ): Boolean = true

    @ObjCSignatureOverride
    override fun gestureRecognizer(gestureRecognizer: UIGestureRecognizer, shouldReceiveTouch: UITouch): Boolean {
        swipe.get()?.fingerLanded()
        return true
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
