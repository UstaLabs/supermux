package dev.supermux.desktop.platform

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class InitialWindowTest {
    @Test fun keepsTheDefaultWhenItFits() {
        assertEquals(InitialWindow(1440, 900), initialWindow(Rectangle(0, 25, 1920, 1055)))
        assertEquals(InitialWindow(1440, 900), initialWindow(Rectangle(0, 0, 1440, 900)))
    }

    @Test fun shrinksAndCentresOnASmallScreen() {
        // The Windows VM: 1280×800 with a 48 px taskbar at the bottom.
        assertEquals(InitialWindow(1152, 677, 64, 37), initialWindow(Rectangle(0, 0, 1280, 752)))
    }

    @Test fun onlyTheDimensionThatDoesNotFitShrinks() {
        assertEquals(InitialWindow(1440, 765, 240, 42), initialWindow(Rectangle(0, 0, 1920, 850)))
    }

    @Test fun unknownScreenKeepsTheDefault() {
        assertEquals(InitialWindow(1440, 900), initialWindow(null))
        assertEquals(InitialWindow(1440, 900), initialWindow(Rectangle(0, 0, 0, 0)))
    }
}

/** #134 (ADEMark): the window comes back where and how big it was, when that is still on a screen. */
class RestoredWindowTest {
    private val fallback = InitialWindow(1440, 900)
    private val laptop = Rectangle(0, 25, 1440, 875)
    private val external = Rectangle(1440, 0, 2560, 1440)

    @Test fun nothingSavedIsTheDefault() {
        assertEquals(fallback, restoredWindow(null, listOf(laptop), fallback))
    }

    @Test fun aSavedWindowOnAScreenComesBackAsItWas() {
        assertEquals(InitialWindow(1000, 700, 100, 80), restoredWindow(SavedWindow(100, 80, 1000, 700), listOf(laptop), fallback))
        assertEquals(InitialWindow(1600, 1000, 1800, 100), restoredWindow(SavedWindow(1800, 100, 1600, 1000), listOf(laptop, external), fallback))
    }

    @Test fun aWindowLeftOnAScreenThatIsGoneFallsBack() {
        // It was on the external display, now unplugged: never open it where nobody can reach its title bar.
        assertEquals(fallback, restoredWindow(SavedWindow(1800, 100, 1600, 1000), listOf(laptop), fallback))
    }

    @Test fun aWindowBiggerThanItsScreenIsClampedToIt() {
        assertEquals(InitialWindow(1440, 875, 0, 25), restoredWindow(SavedWindow(0, 25, 2400, 1300), listOf(laptop), fallback))
    }

    @Test fun maximisedIsRemembered() {
        assertEquals(true, restoredWindow(SavedWindow(0, 25, 1440, 875, maximized = true), listOf(laptop), fallback).maximized)
    }
}
