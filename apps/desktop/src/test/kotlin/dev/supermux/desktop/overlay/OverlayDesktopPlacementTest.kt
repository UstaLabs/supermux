package dev.supermux.desktop.overlay

import dev.supermux.ui.overlay.OverlayScreen
import dev.supermux.ui.overlay.Point
import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayDesktopPlacementTest {
    // A 1440 × 900 Mac screen with a 25 px menu bar and an 80 px Dock.
    private val screen = OverlayScreen(width = 1440f, height = 900f, topInset = 25f, bottomInset = 80f, keyboardHeight = 0f)

    @Test
    fun onTheLeftEdgeTheCardOpensToItsRightLevelWithItsBottom() =
        assertEquals(Point(84f, 184f), chatCardSpot(headX = 12f, headY = 680f, headSize = 64f, cardW = 380f, cardH = 560f, screen = screen, gap = 8f))

    @Test
    fun onTheRightEdgeTheCardOpensToItsLeft() =
        assertEquals(Point(976f, 184f), chatCardSpot(headX = 1364f, headY = 680f, headSize = 64f, cardW = 380f, cardH = 560f, screen = screen, gap = 8f))

    @Test
    fun aHeadHighUpKeepsTheCardBelowTheMenuBar() =
        assertEquals(33f, chatCardSpot(headX = 12f, headY = 40f, headSize = 64f, cardW = 380f, cardH = 560f, screen = screen, gap = 8f).y)

    @Test
    fun aCardTallerThanTheRoomStillStartsBelowTheMenuBar() {
        val small = OverlayScreen(width = 800f, height = 500f, topInset = 25f, bottomInset = 40f, keyboardHeight = 0f)
        assertEquals(33f, chatCardSpot(headX = 12f, headY = 300f, headSize = 64f, cardW = 380f, cardH = 560f, screen = small, gap = 8f).y)
    }
}
