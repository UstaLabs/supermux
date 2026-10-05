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
