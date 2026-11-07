package dev.supermux.desktop.overlay

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where the head rests, remembered across restarts (#62): its screen and its spot on it. */
class OverlaySpotTest {
    private val head = 72
    private val laptop = Rectangle(0, 25, 1440, 875)
    private val above = Rectangle(-249, -1440, 2560, 1410)

    @Test fun aSpotSurvivesBeingWrittenDown() {
        val spot = OverlaySpot(-237, -456, above)
        assertEquals(spot, decodeOverlaySpot(spot.encode()))
    }

    @Test fun nothingOrNonsenseWrittenDownIsNoSpot() {
        assertNull(decodeOverlaySpot(null))
        assertNull(decodeOverlaySpot("bottom left please"))
    }

    @Test fun itComesBackWhereItWas() = assertEquals(12 to 745, restoreSpot(OverlaySpot(12, 745, laptop), listOf(laptop), head))

    @Test fun itComesBackOnTheOtherScreenWhileThatOneIsThere() =
        assertEquals(-237 to -456, restoreSpot(OverlaySpot(-237, -456, above), listOf(laptop, above), head))

    @Test fun aScreenThatIsGoneMeansTheUsualSpot() = assertNull(restoreSpot(OverlaySpot(-237, -456, above), listOf(laptop), head))

    @Test fun itIsKeptWholeOnItsScreen() =
        assertEquals((1440 - head) to (25 + 875 - head), restoreSpot(OverlaySpot(1430, 890, laptop), listOf(laptop), head))

    @Test fun aScreenThatShrankStillHoldsItWhenTheSpotIsOnIt() {
        val smaller = Rectangle(0, 25, 1280, 775)
        assertEquals((1280 - head) to (25 + 775 - head), restoreSpot(OverlaySpot(1270, 740, laptop), listOf(smaller), head))
    }
}
