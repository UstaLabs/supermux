package dev.supermux.webview.engine.ios

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGestureRecognizerDelegateProtocol
import platform.UIKit.UIPanGestureRecognizer
import platform.UIKit.UIScrollView
import platform.UIKit.UIView
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hand-off as its pans drive it: the card's own pan the vertical one ([PageSideSwipe.verticalMoved]), the scroll
 * view's the sideways one ([PageSideSwipe.sideMoved]), with the page's answers and the views around it faked: a web view
 * and its scroll view 300 by 400 points, at density 2.
 */
@OptIn(ExperimentalForeignApi::class)
class PageSideSwipeTest {
    private class Recorder : PageSideSwipe.Parents {
        val scrolls = mutableListOf<Offset>()
        val flings = mutableListOf<Velocity>()
        override fun scroll(delta: Offset): Offset = delta.also { scrolls += it }
        override fun fling(velocity: Velocity) {
            flings += velocity
        }
    }

    private val webView = UIView(CGRectMake(0.0, 0.0, 300.0, 400.0))
    private val scrollView = UIScrollView(CGRectMake(0.0, 0.0, 300.0, 400.0)).also { webView.addSubview(it) }
    private val asked = mutableListOf<Pair<String, (String?) -> Unit>>()
    private val parents = Recorder()
    private val swipe = PageSideSwipe(webView, scrollView) { script, done -> asked += script to done }.also {
        it.parents = parents
        it.density = 2f
    }

    @AfterTest fun remove() = swipe.remove()

    /** A page [height] points tall at [offsetY], in a pane or not. */
    private fun page(height: Double = 400.0, offsetY: Double = 0.0, pane: Boolean = true) {
        scrollView.setContentSize(CGSizeMake(300.0, height))
        scrollView.setContentOffset(CGPointMake(0.0, offsetY), animated = false)
        swipe.pane = pane
    }

    private fun answer(result: String?) = asked.last().second(result)

    private fun offsetY() = scrollView.contentOffset.useContents { y }

    @Test fun aPullOverPlainContentInAPaneGoesToTheList() {
        page()
        swipe.verticalBegan(100.0, 300.0)
        swipe.verticalMoved(1.0, 20.0, pointers = 1)
        assertEquals(1, asked.size)
        assertTrue(asked[0].first.endsWith("(100.0,300.0,'y',1)"), asked[0].first)
        // Nothing is handed on until the page answers.
        assertEquals(emptyList(), parents.scrolls)
        answer("false")
        swipe.verticalMoved(1.0, 30.0, pointers = 1)
        assertEquals(listOf(Offset(0f, 20f)), parents.scrolls)
        swipe.verticalMoved(1.0, 45.0, pointers = 1)
        assertEquals(Offset(0f, 30f), parents.scrolls.last())
        assertEquals(0.0, offsetY())
        swipe.verticalEnded(500.0)
        assertEquals(listOf(Velocity(0f, 1000f)), parents.flings)
    }

    @Test fun aCancelledHandOffStillFlingsSoTheListSettles() {
        page()
        swipe.verticalBegan(100.0, 300.0)
        swipe.verticalMoved(0.0, 20.0, pointers = 1)
        answer("false")
        swipe.verticalMoved(0.0, 30.0, pointers = 1)
        swipe.verticalEnded(0.0)
        assertEquals(listOf(Velocity.Zero), parents.flings)
    }

    @Test fun aDragOverWhatClaimsItStaysThePages() {
        page()
        swipe.verticalBegan(100.0, 100.0)
        swipe.verticalMoved(0.0, 20.0, pointers = 1)
        answer("true")
        swipe.verticalMoved(0.0, 40.0, pointers = 1)
        swipe.verticalMoved(0.0, -40.0, pointers = 1)
        assertEquals(emptyList(), parents.scrolls)
        swipe.verticalEnded(500.0)
        assertEquals(emptyList(), parents.flings)
    }

    @Test fun anEmbedNeverHandsAVerticalDragOn() {
        // In the list (Cooperative), the list takes a vertical drag itself.
        page(pane = false)
        swipe.verticalBegan(100.0, 300.0)
        swipe.verticalMoved(0.0, 20.0, pointers = 1)
        swipe.verticalMoved(0.0, 40.0, pointers = 1)
        assertEquals(emptyList(), asked)
        assertEquals(emptyList(), parents.scrolls)
    }

    @Test fun aPageThatCanStillScrollThatWayKeepsTheDrag() {
        page(height = 1000.0, offsetY = 300.0)
        swipe.verticalBegan(100.0, 300.0)
        swipe.verticalMoved(0.0, 20.0, pointers = 1)
        assertEquals(emptyList(), asked)
        assertEquals(emptyList(), parents.scrolls)
    }

    @Test fun aPageAtItsBottomHandsOnAPushUpAndStaysPinnedThere() {
        page(height = 1000.0, offsetY = 600.0)
        swipe.verticalBegan(100.0, 300.0)
        swipe.verticalMoved(0.0, -20.0, pointers = 1)
        answer("false")
        swipe.verticalMoved(0.0, -30.0, pointers = 1)
        assertEquals(listOf(Offset(0f, -20f)), parents.scrolls)
        // The finger turns back: still the list's, and the page stays where it was.
        scrollView.setContentOffset(CGPointMake(0.0, 590.0), animated = false)
        swipe.verticalMoved(0.0, 0.0, pointers = 1)
        assertEquals(600.0, offsetY())
        assertEquals(Offset(0f, 60f), parents.scrolls.last())
    }

    @Test fun aPinchNeverHandsOff() {
        page()
        swipe.verticalBegan(100.0, 300.0)
        swipe.verticalMoved(0.0, 0.0, pointers = 2)
        swipe.verticalMoved(0.0, 40.0, pointers = 1)
        assertEquals(emptyList(), asked)
        assertEquals(emptyList(), parents.scrolls)
    }

    @Test fun aLateAnswerForAnEarlierGestureIsIgnored() {
        page()
        swipe.verticalBegan(100.0, 300.0)
        swipe.verticalMoved(0.0, 20.0, pointers = 1)
        val late = asked.last().second
        swipe.verticalEnded(0.0)
        swipe.verticalBegan(100.0, 300.0)
        swipe.verticalMoved(0.0, 20.0, pointers = 1)
        late("false")
        swipe.verticalMoved(0.0, 40.0, pointers = 1)
        assertEquals(emptyList(), parents.scrolls)
    }

    @Test fun theSidewaysSwipeIsAsItWasInAPaneOrNot() {
        for (pane in listOf(false, true)) {
            asked.clear()
            parents.scrolls.clear()
            page(pane = pane)
            swipe.sideBegan(100.0, 300.0)
            swipe.sideMoved(-20.0, 1.0, pointers = 1)
            assertEquals(1, asked.size)
            assertTrue(asked[0].first.endsWith("(100.0,300.0,-1)"), asked[0].first)
            answer("false")
            swipe.sideMoved(-30.0, 1.0, pointers = 1)
            assertEquals(listOf(Offset(-20f, 0f)), parents.scrolls)
            swipe.sideEnded(-400.0)
            assertEquals(Velocity(-800f, 0f), parents.flings.last())
        }
    }

    // The card's own pan: the scroll view's never begins over a card that fits and doesn't bounce.

    @Test fun theCardsPanSitsOnTheWebViewAndRunsOnlyInAPane() {
        assertTrue(webView.gestureRecognizers.orEmpty().any { it === swipe.cardPan })
        assertFalse(swipe.cardPan.enabled)
        swipe.pane = true
        assertTrue(swipe.cardPan.enabled)
        swipe.pane = false
        assertFalse(swipe.cardPan.enabled)
        swipe.remove()
        assertFalse(webView.gestureRecognizers.orEmpty().any { it === swipe.cardPan })
    }

    @Test fun theCardsPanTakesOneFingerAndLeavesThePageEveryTouch() {
        val pan = swipe.cardPan
        assertEquals(1uL, pan.maximumNumberOfTouches)
        assertFalse(pan.cancelsTouchesInView)
        assertFalse(pan.delaysTouchesBegan)
        assertFalse(pan.delaysTouchesEnded)
    }

    @Test fun theCardsPanRecognizesWithEveryOtherRecognizer() {
        val delegate = swipe.cardPan.delegate as UIGestureRecognizerDelegateProtocol
        for (other in listOf(scrollView.panGestureRecognizer, UIPanGestureRecognizer())) {
            assertTrue(delegate.gestureRecognizer(swipe.cardPan, shouldRecognizeSimultaneouslyWithGestureRecognizer = other))
        }
    }

    @Test fun aVerticalHandOffKeepsTheScrollViewsPanFromSwipingSideways() {
        page()
        swipe.verticalBegan(100.0, 300.0)
        swipe.sideBegan(100.0, 300.0)
        swipe.verticalMoved(0.0, 20.0, pointers = 1)
        answer("false")
        swipe.verticalMoved(0.0, 30.0, pointers = 1)
        assertEquals(1, parents.scrolls.size)
        // The finger veers sideways: the scroll view's pan neither asks nor hands anything on.
        asked.clear()
        swipe.sideMoved(-200.0, 30.0, pointers = 1)
        assertEquals(emptyList(), asked)
        assertEquals(listOf(Offset(0f, 20f)), parents.scrolls)
    }

    @Test fun theCardsPanIsTheOneThatAsks() {
        page()
        // Only the card's pan runs (the scroll view's never began): it asks, and hands on.
        swipe.verticalBegan(40.0, 50.0)
        swipe.verticalMoved(0.0, -20.0, pointers = 1)
        assertEquals(1, asked.size)
        assertTrue(asked[0].first.endsWith("(40.0,50.0,'y',-1)"), asked[0].first)
    }
}
