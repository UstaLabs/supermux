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
        assertEquals(ChromeInsets(start = 0.dp, end = 141.dp, band = WindowsTitleBarHeight), i)
        assertEquals(LinuxTitleBarHeight, i.band, "the band is the strip row, as on Linux")
        assertEquals(ChromeInsets.None, chromeInsets(ChromeOs.Windows, customChrome = false))
    }

    @Test fun captionButtonsWidthFallsBackUntilJbrReportsIt() {
        assertEquals(WindowsCaptionButtonsFallbackWidth, WindowsWindowChrome.captionButtonsWidth(null))
        assertEquals(WindowsCaptionButtonsFallbackWidth, WindowsWindowChrome.captionButtonsWidth(0f))
        assertEquals(138.5f.dp, WindowsWindowChrome.captionButtonsWidth(138.5f))
    }
}
