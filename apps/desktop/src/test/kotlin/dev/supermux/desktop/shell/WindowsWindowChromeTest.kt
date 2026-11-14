package dev.supermux.desktop.shell

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsWindowChromeTest {
    @Test fun engagesOnlyOnWindowsWithDecorationsAndNoOptOut() {
        assertTrue(WindowsWindowChrome.shouldEngage(windows = true, optOut = null, decorationsAvailable = true))
        assertFalse(WindowsWindowChrome.shouldEngage(windows = true, optOut = null, decorationsAvailable = false), "no JBR: normal frame")
        assertFalse(WindowsWindowChrome.shouldEngage(windows = true, optOut = "1", decorationsAvailable = true))
        assertFalse(WindowsWindowChrome.shouldEngage(windows = true, optOut = " TRUE ", decorationsAvailable = true))
        assertTrue(WindowsWindowChrome.shouldEngage(windows = true, optOut = "0", decorationsAvailable = true))
        assertFalse(WindowsWindowChrome.shouldEngage(windows = false, optOut = null, decorationsAvailable = true))
    }

    @Test fun insetsReserveTheNativeCaptionButtonsAtTheTopRight() {
        val i = chromeInsets(ChromeOs.Windows, customChrome = true, windowsCaptionButtons = 141.dp)
        assertEquals(ChromeInsets(start = SidebarBandControlsWidth, end = 141.dp, band = WindowsTitleBarHeight), i)
        assertEquals(LinuxTitleBarHeight, i.band, "the band is the strip row, as on Linux")
        assertEquals(ChromeInsets.None, chromeInsets(ChromeOs.Windows, customChrome = false))
    }

    @Test fun captionButtonsWidthFallsBackUntilJbrReportsIt() {
        assertEquals(WindowsCaptionButtonsFallbackWidth, WindowsWindowChrome.captionButtonsWidth(null))
        assertEquals(WindowsCaptionButtonsFallbackWidth, WindowsWindowChrome.captionButtonsWidth(0f))
        assertEquals(138.5f.dp, WindowsWindowChrome.captionButtonsWidth(138.5f))
    }

    /**
     * #150: a tab dragged out of a window on Windows made no window of its own. The strip is the title band, whose
     * events JBR gives to the window's panel, and each was handed on to whatever lay under the pointer: past the
     * window's edge nothing does, so Compose lost the drag there. A press keeps its component until it ends, as AWT's
     * own mouse grab does.
     */
    @Test fun aPressKeepsItsComponentForItsDragAndReleaseEvenOutsideTheWindow() {
        val band = BandRedispatch<String>()
        assertEquals("skia", band.targetFor(java.awt.event.MouseEvent.MOUSE_PRESSED, under = "skia"))
        assertEquals("skia", band.targetFor(java.awt.event.MouseEvent.MOUSE_DRAGGED, under = "skia"))
        assertEquals("skia", band.targetFor(java.awt.event.MouseEvent.MOUSE_DRAGGED, under = null), "past the window's edge")
        assertEquals("skia", band.targetFor(java.awt.event.MouseEvent.MOUSE_RELEASED, under = null), "released out there")
        // The press is over: a move goes to what is under the pointer again.
        assertEquals(null, band.targetFor(java.awt.event.MouseEvent.MOUSE_MOVED, under = null))
        assertEquals("menu", band.targetFor(java.awt.event.MouseEvent.MOUSE_MOVED, under = "menu"))
    }

    @Test fun aPressOnTheCaptionGrabsNothing() {
        val band = BandRedispatch<String>()
        // A caption press (native drag) is handed to no component; its drag isn't either.
        assertEquals(null, band.targetFor(java.awt.event.MouseEvent.MOUSE_PRESSED, under = null))
        assertEquals(null, band.targetFor(java.awt.event.MouseEvent.MOUSE_DRAGGED, under = null))
        assertEquals(null, band.targetFor(java.awt.event.MouseEvent.MOUSE_RELEASED, under = null))
    }
}
