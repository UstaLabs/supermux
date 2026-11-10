package dev.supermux.webview.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SideSwipeTest {
    @Test
    fun aSidewaysDragOffersWhatThePageLeft() {
        val s = SideSwipe()
        s.down()
        s.moved(-30f, 4f, pointers = 1)
        assertTrue(s.mayOffer())
    }

    @Test
    fun aMostlyVerticalDragKeepsItsSidewaysJitter() {
        val s = SideSwipe()
        s.down()
        s.moved(-6f, 40f, pointers = 1)
        assertFalse(s.mayOffer())
    }

    @Test
    fun aPinchNeverBecomesASwipe() {
        val s = SideSwipe()
        s.down()
        s.moved(0f, 0f, pointers = 2)
        // The second finger lifted: the rest of the gesture is still the pinch's.
        s.moved(-50f, 0f, pointers = 1)
        assertFalse(s.mayOffer())
    }

    @Test
    fun takenStaysTakenUntilTheNextDown() {
        val s = SideSwipe()
        s.down()
        s.moved(-30f, 0f, pointers = 1)
        s.take()
        assertTrue(s.taken)
        assertFalse(s.mayOffer())
        s.down()
        assertFalse(s.taken)
    }

    @Test
    fun theFingerPxCarryTheirFraction() {
        val s = SideSwipe()
        s.down()
        // 0.4 + 0.4 + 0.4: nothing whole, then one px.
        assertTrue(listOf(s.whole(0.4f), s.whole(0.4f), s.whole(0.4f)) == listOf(0, 0, 1))
    }

    // A page 1000 wide in a view 400 wide, no insets: it scrolls from 0 to 600.
    @Test
    fun aPageAtItsLeftEdgeHandsOnADragToTheRight() {
        assertTrue(SideSwipe.atEdge(offsetX = 0.0, contentWidth = 1000.0, viewWidth = 400.0, fingerDx = 5f))
        assertFalse(SideSwipe.atEdge(offsetX = 0.0, contentWidth = 1000.0, viewWidth = 400.0, fingerDx = -5f))
    }

    @Test
    fun aPageAtItsRightEdgeHandsOnADragToTheLeft() {
        assertTrue(SideSwipe.atEdge(offsetX = 600.0, contentWidth = 1000.0, viewWidth = 400.0, fingerDx = -5f))
        assertFalse(SideSwipe.atEdge(offsetX = 600.0, contentWidth = 1000.0, viewWidth = 400.0, fingerDx = 5f))
    }

    @Test
    fun aPageMidwayKeepsTheDrag() {
        assertFalse(SideSwipe.atEdge(offsetX = 300.0, contentWidth = 1000.0, viewWidth = 400.0, fingerDx = 5f))
        assertFalse(SideSwipe.atEdge(offsetX = 300.0, contentWidth = 1000.0, viewWidth = 400.0, fingerDx = -5f))
    }

    @Test
    fun aPageThatCantScrollSidewaysIsAtBothEdges() {
        assertTrue(SideSwipe.atEdge(offsetX = 0.0, contentWidth = 400.0, viewWidth = 400.0, fingerDx = 5f))
        assertTrue(SideSwipe.atEdge(offsetX = 0.0, contentWidth = 390.0, viewWidth = 400.0, fingerDx = -5f))
    }
}
