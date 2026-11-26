package dev.supermux.desktop.applets

import dev.supermux.desktop.platform.SavedWindow
import java.awt.Rectangle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ahmet: an applet's window opens where it was, at its size and state, never off screen. */
class AppletWindowPlacementsTest {
    private val laptop = Rectangle(0, 25, 1512, 920)
    private val monitor = Rectangle(1512, 0, 2560, 1415)

    @Test fun aSavedPlaceOnAScreenOpensThereAtItsSizeAndState() {
        val place = AppletWindowPlace(SavedWindow(1700, 120, 900, 700, maximized = false))
        val o = appletWindowOpening(place, listOf(laptop, monitor))
        assertEquals(listOf(900, 700, 1700, 120), listOf(o.bounds.width, o.bounds.height, o.bounds.x, o.bounds.y))
        assertFalse(o.fullscreen)
        assertTrue(appletWindowOpening(place.copy(window = place.window.copy(maximized = true)), listOf(laptop, monitor)).bounds.maximized)
        assertTrue(appletWindowOpening(place.copy(fullscreen = true), listOf(laptop, monitor)).fullscreen)
    }

    @Test fun aPlaceOnAnUnpluggedMonitorOpensOnAScreenThatIsThereAtItsSizeClamped() {
        // Left on the monitor, which is gone: the OS places it on the laptop, its size kept but no bigger than the screen.
        val o = appletWindowOpening(AppletWindowPlace(SavedWindow(2000, 200, 1800, 1200)), listOf(laptop))
        assertNull(o.bounds.x)
        assertNull(o.bounds.y)
        assertEquals(1512, o.bounds.width)
        assertEquals(920, o.bounds.height)
    }

    @Test fun anAppletWithNoPlaceOpensAtTheDefaultSize() {
        val o = appletWindowOpening(null, listOf(laptop))
        assertEquals(APPLET_WINDOW_WIDTH, o.bounds.width)
        assertEquals(APPLET_WINDOW_HEIGHT, o.bounds.height)
        assertNull(o.bounds.x)
    }

    @Test fun placesArePerHostAndAppletAndOutliveTheStore() {
        val dir = Files.createTempDirectory("applet-windows")
        val file = dir.resolve("applet-windows.json")
        val a = AppletWindowPlace(SavedWindow(10, 40, 800, 600), fullscreen = true)
        AppletWindowPlacements(file).put("host-a", "issues", a)
        AppletWindowPlacements(file).put("host-b", "issues", AppletWindowPlace(SavedWindow(30, 60, 500, 400)))
        val again = AppletWindowPlacements(file)
        assertEquals(a, again.get("host-a", "issues"))
        assertEquals(500, again.get("host-b", "issues")?.window?.width)
        assertNull(again.get("host-a", "job-hunt"))
        // A broken file is a fresh start, not a crash.
        Files.writeString(file, "{not json")
        assertNull(AppletWindowPlacements(file).get("host-a", "issues"))
        dir.toFile().deleteRecursively()
    }
}
