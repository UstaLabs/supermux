package dev.supermux.ui.panes

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a tab dragged along its own strip sits, by the real widths of its neighbours: it passes one
 * once its centre crosses that neighbour's middle.
 */
class TabReorderTest {
    @Test
    fun aNeighbourIsPassedOnceTheDraggedTabsCentreCrossesItsMiddle() {
        // a (100) dragged from 0 past b, c, d (100 each): b's middle is at 150 while a sits at 0.
        assertEquals(0, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 0, centre = 149f))
        assertEquals(1, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 0, centre = 151f))
    }

    @Test
    fun aWideNeighbourIsPassedAtItsOwnMiddleNotAtAFixedSlot() {
        // b is 300 wide: its middle, with a (100) before it, is at 250.
        assertEquals(0, liveInsertIndex(listOf(300f, 100f), 100f, current = 0, centre = 240f))
        assertEquals(1, liveInsertIndex(listOf(300f, 100f), 100f, current = 0, centre = 260f))
    }

    @Test
    fun aNarrowNeighbourIsPassedSooner() {
        // b is 40 wide: its middle, with a (200) before it, is at 220.
        assertEquals(1, liveInsertIndex(listOf(40f, 100f), 200f, current = 0, centre = 221f))
    }

    @Test
    fun goingBackNeedsTheCentreToCrossTheNeighboursMiddleWhereItNowIs() {
        // a passed b (300): b now sits at 0..300, middle 150. Hovering between stays put.
        assertEquals(1, liveInsertIndex(listOf(300f, 100f), 100f, current = 1, centre = 200f))
        assertEquals(0, liveInsertIndex(listOf(300f, 100f), 100f, current = 1, centre = 149f))
    }

    @Test
    fun aFastDragPassesSeveralNeighboursAtOnce() {
        assertEquals(3, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 0, centre = 380f))
        assertEquals(0, liveInsertIndex(listOf(100f, 100f, 100f), 100f, current = 3, centre = 20f))
    }

    @Test
    fun theIndexStaysWithinTheStrip() {
        assertEquals(2, liveInsertIndex(listOf(100f, 100f), 100f, current = 9, centre = 10_000f))
        assertEquals(0, liveInsertIndex(listOf(100f, 100f), 100f, current = -3, centre = -500f))
        assertEquals(0, liveInsertIndex(emptyList(), 100f, current = 0, centre = 50f))
    }
}
