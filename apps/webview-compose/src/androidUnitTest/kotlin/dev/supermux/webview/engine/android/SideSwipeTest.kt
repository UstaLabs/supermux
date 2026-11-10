package dev.supermux.webview.engine.android

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
}
