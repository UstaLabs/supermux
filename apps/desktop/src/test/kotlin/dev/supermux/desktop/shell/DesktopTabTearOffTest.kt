package dev.supermux.desktop.shell

import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

/** The frame a window opened for a dragged tab gets: measured off a window already on screen. */
class DesktopTabTearOffTest {
    @Test
    fun underOurOwnChromeTheContentIsTheWholeWindow() {
        assertEquals(Insets(0, 0, 0, 0), frameInsets(Rectangle(100, 50, 800, 600), Point(100, 50), Dimension(800, 600)))
    }

    @Test
    fun aSystemFrameIsMeasuredAroundTheContent() {
        // A title bar 31 high and 8-point borders, as Windows draws them.
        assertEquals(Insets(31, 8, 8, 8), frameInsets(Rectangle(100, 50, 816, 639), Point(108, 81), Dimension(800, 600)))
    }
}
