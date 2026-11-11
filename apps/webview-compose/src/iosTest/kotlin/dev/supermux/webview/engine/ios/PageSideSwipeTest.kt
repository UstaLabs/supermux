package dev.supermux.webview.engine.ios

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIScrollView
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hand-off as the scroll view's pan drives it ([PageSideSwipe.began], [PageSideSwipe.moved], [PageSideSwipe.ended]),
 * with the page's answers and the views around it faked: a scroll view 300 by 400 points, at density 2.
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

    private val scrollView = UIScrollView(CGRectMake(0.0, 0.0, 300.0, 400.0))
    private val asked = mutableListOf<Pair<String, (String?) -> Unit>>()
    private val parents = Recorder()
    private val swipe = PageSideSwipe(scrollView) { script, done -> asked += script to done }.also {
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
        swipe.began(100.0, 300.0)
        swipe.moved(1.0, 20.0, pointers = 1)
        assertEquals(1, asked.size)
        assertTrue(asked[0].first.endsWith("(100.0,300.0,'y',1)"), asked[0].first)
        // Nothing is handed on until the page answers.
        assertEquals(emptyList(), parents.scrolls)
        answer("false")
        swipe.moved(1.0, 30.0, pointers = 1)
        assertEquals(listOf(Offset(0f, 20f)), parents.scrolls)
        swipe.moved(1.0, 45.0, pointers = 1)
        assertEquals(Offset(0f, 30f), parents.scrolls.last())
        assertEquals(0.0, offsetY())
        swipe.ended(10.0, 500.0)
        assertEquals(listOf(Velocity(0f, 1000f)), parents.flings)
    }

    @Test fun aCancelledHandOffStillFlingsSoTheListSettles() {
        page()
        swipe.began(100.0, 300.0)
        swipe.moved(0.0, 20.0, pointers = 1)
        answer("false")
        swipe.moved(0.0, 30.0, pointers = 1)
        swipe.ended(0.0, 0.0)
        assertEquals(listOf(Velocity.Zero), parents.flings)
    }

    @Test fun aDragOverWhatClaimsItStaysThePages() {
        page()
        swipe.began(100.0, 100.0)
        swipe.moved(0.0, 20.0, pointers = 1)
        answer("true")
        swipe.moved(0.0, 40.0, pointers = 1)
        swipe.moved(0.0, -40.0, pointers = 1)
        assertEquals(emptyList(), parents.scrolls)
        swipe.ended(0.0, 500.0)
        assertEquals(emptyList(), parents.flings)
    }

    @Test fun anEmbedNeverHandsAVerticalDragOn() {
        // In the list (Cooperative), the list takes a vertical drag itself.
        page(pane = false)
        swipe.began(100.0, 300.0)
        swipe.moved(0.0, 20.0, pointers = 1)
        swipe.moved(0.0, 40.0, pointers = 1)
        assertEquals(emptyList(), asked)
        assertEquals(emptyList(), parents.scrolls)
    }

    @Test fun aPageThatCanStillScrollThatWayKeepsTheDrag() {
        page(height = 1000.0, offsetY = 300.0)
        swipe.began(100.0, 300.0)
        swipe.moved(0.0, 20.0, pointers = 1)
        assertEquals(emptyList(), asked)
        assertEquals(emptyList(), parents.scrolls)
    }

    @Test fun aPageAtItsBottomHandsOnAPushUpAndStaysPinnedThere() {
        page(height = 1000.0, offsetY = 600.0)
        swipe.began(100.0, 300.0)
        swipe.moved(0.0, -20.0, pointers = 1)
        answer("false")
        swipe.moved(0.0, -30.0, pointers = 1)
        assertEquals(listOf(Offset(0f, -20f)), parents.scrolls)
        // The finger turns back: still the list's, and the page stays where it was.
        scrollView.setContentOffset(CGPointMake(0.0, 590.0), animated = false)
        swipe.moved(0.0, 0.0, pointers = 1)
        assertEquals(600.0, offsetY())
        assertEquals(Offset(0f, 60f), parents.scrolls.last())
    }

    @Test fun aPinchNeverHandsOff() {
        page()
        swipe.began(100.0, 300.0)
        swipe.moved(0.0, 0.0, pointers = 2)
        swipe.moved(0.0, 40.0, pointers = 1)
        assertEquals(emptyList(), asked)
        assertEquals(emptyList(), parents.scrolls)
    }

    @Test fun aLateAnswerForAnEarlierGestureIsIgnored() {
        page()
        swipe.began(100.0, 300.0)
        swipe.moved(0.0, 20.0, pointers = 1)
        val late = asked.last().second
        swipe.ended(0.0, 0.0)
        swipe.began(100.0, 300.0)
        swipe.moved(0.0, 20.0, pointers = 1)
        late("false")
        swipe.moved(0.0, 40.0, pointers = 1)
        assertEquals(emptyList(), parents.scrolls)
    }

    @Test fun theSidewaysSwipeIsAsItWasInAPaneOrNot() {
        for (pane in listOf(false, true)) {
            asked.clear()
            parents.scrolls.clear()
            page(pane = pane)
            swipe.began(100.0, 300.0)
            swipe.moved(-20.0, 1.0, pointers = 1)
            assertEquals(1, asked.size)
            assertTrue(asked[0].first.endsWith("(100.0,300.0,-1)"), asked[0].first)
            answer("false")
            swipe.moved(-30.0, 1.0, pointers = 1)
            assertEquals(listOf(Offset(-20f, 0f)), parents.scrolls)
            swipe.ended(-400.0, 0.0)
            assertEquals(Velocity(-800f, 0f), parents.flings.last())
        }
    }
}
