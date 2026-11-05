package dev.supermux.ui.panes

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a tab dragged along its own strip sits, by the real widths of its neighbours: one gives
 * way as soon as the held tab's leading edge crosses its middle — the held tab covers half of it —
 * with a few pixels of dead band so a pointer resting on that line cannot flip the order.
 */
class TabReorderTest {
    private val band = 4f

    @Test
    fun movingRightTheNextTabGivesWayOnceTheHeldTabsRightEdgeCrossesItsMiddle() {
        // a (100) held at slot 0, then b, c, d (100 each): b's middle is at 150, a's right edge at left + 100.
        assertEquals(0, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 0, left = 53f, hysteresis = band))
        assertEquals(1, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 0, left = 55f, hysteresis = band))
    }

    @Test
    fun movingLeftThePreviousTabGivesWayOnceTheHeldTabsLeftEdgeCrossesItsMiddle() {
        // a held at slot 2 (left 200): the tab before it spans 100..200, its middle at 150.
        assertEquals(2, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 2, left = 147f, hysteresis = band))
        assertEquals(1, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 2, left = 145f, hysteresis = band))
    }

    @Test
    fun aWideNeighbourGivesWayAtItsOwnMiddle() {
        // b is 300 wide: a's right edge must pass 100 + 150.
        assertEquals(0, liveInsertIndex(listOf(300f, 100f), 100f, current = 0, left = 150f, hysteresis = band))
        assertEquals(1, liveInsertIndex(listOf(300f, 100f), 100f, current = 0, left = 155f, hysteresis = band))
        // And back: a's left edge must pass b's middle, 150, from a's slot at 300.
        assertEquals(1, liveInsertIndex(listOf(300f, 100f), 100f, current = 1, left = 147f, hysteresis = band))
        assertEquals(0, liveInsertIndex(listOf(300f, 100f), 100f, current = 1, left = 145f, hysteresis = band))
    }

    @Test
    fun aNarrowNeighbourGivesWaySooner() {
        // a is 200 wide, b only 40: a's right edge passes b's middle (220) after 20.
        assertEquals(0, liveInsertIndex(listOf(40f, 100f), 200f, current = 0, left = 23f, hysteresis = band))
        assertEquals(1, liveInsertIndex(listOf(40f, 100f), 200f, current = 0, left = 25f, hysteresis = band))
    }

    @Test
    fun aPointerRestingOnTheLineNeverFlipsTheOrder() {
        // Passed b at 55; wobbling between 47 and 53 keeps it passed, and only well back (45) returns.
        for (left in listOf(53f, 50f, 47f, 52f)) {
            assertEquals(1, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 1, left = left, hysteresis = band))
        }
        assertEquals(0, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 1, left = 45f, hysteresis = band))
        // Not yet passed: the same wobble keeps it where it is.
        for (left in listOf(47f, 50f, 53f)) {
            assertEquals(0, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 0, left = left, hysteresis = band))
        }
    }

    @Test
    fun theSameDistanceMovesTheTabEitherWay() {
        // 55 right of slot 1 passes c; 55 left of it passes a.
        assertEquals(2, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 1, left = 155f, hysteresis = band))
        assertEquals(0, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 1, left = 45f, hysteresis = band))
    }

    @Test
    fun aFastDragPassesSeveralNeighboursAtOnce() {
        assertEquals(3, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 0, left = 290f, hysteresis = band))
        assertEquals(0, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 3, left = 10f, hysteresis = band))
    }

    @Test
    fun theIndexStaysWithinTheStrip() {
        assertEquals(2, liveInsertIndex(listOf(100f, 100f), 100f, current = 9, left = 10_000f, hysteresis = band))
        assertEquals(0, liveInsertIndex(listOf(100f, 100f), 100f, current = -3, left = -500f, hysteresis = band))
        assertEquals(0, liveInsertIndex(emptyList(), 100f, current = 0, left = 50f, hysteresis = band))
    }
}
