package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.shell.LocalWindowDragChrome
import dev.supermux.ui.shell.LocalWindowDragLayer
import dev.supermux.ui.shell.excludeFromWindowDrag
import dev.supermux.ui.shell.windowDragCover
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BandWindowDragTest {
    private val band = 32.dp

    private fun grab(r: MacChromeRegions, x: Float, y: Float, button: Int = MouseEvent.BUTTON1, scale: Double = 1.0) =
        bandGrab(button, Offset(x, y), band, scale, r)

    @Test
    fun theWholeBandIsASoftHandleWithNothingRegistered() {
        val r = MacChromeRegions()
        assertEquals(BandGrab.Soft, grab(r, 10f, 10f))
        assertEquals(BandGrab.Soft, grab(r, 900f, 31f))
        assertEquals(BandGrab.None, grab(r, 10f, 32f), "below the band")
        assertEquals(BandGrab.None, grab(r, 10f, 10f, MouseEvent.BUTTON3), "right-click")
    }

    @Test
    fun theBandScalesWithTheMonitor() {
        val r = MacChromeRegions()
        assertEquals(BandGrab.Soft, grab(r, 10f, 40f, scale = 2.0))
        assertEquals(BandGrab.None, grab(r, 10f, 64f, scale = 2.0))
    }

    @Test
    fun dragRegionsAreNativeAndHolesNeverMove() {
        val r = MacChromeRegions()
        r.set("strip", Rect(0f, 0f, 400f, 32f), hole = false)
        r.set("tabs", Rect(0f, 0f, 200f, 32f), hole = true)
        r.set("seam", Rect(500f, 0f, 512f, 900f), hole = true)
        assertEquals(BandGrab.Native, grab(r, 300f, 10f), "strip tail")
        assertEquals(BandGrab.None, grab(r, 100f, 10f), "a tab")
        assertEquals(BandGrab.None, grab(r, 505f, 10f), "a splitter seam")
        assertEquals(BandGrab.Soft, grab(r, 700f, 10f), "unregistered band")
    }

    @Test
    fun aCoverHidesTheLayersBelowIt() {
        val r = MacChromeRegions()
        r.set("strip", Rect(0f, 0f, 400f, 32f), hole = false)
        r.set("tabs", Rect(0f, 0f, 200f, 32f), hole = true)
        r.setCover("settings", Rect(0f, 0f, 1000f, 800f), layer = 1)
        assertEquals(BandGrab.Soft, grab(r, 300f, 10f), "Home's strip tail under Settings")
        assertEquals(BandGrab.Soft, grab(r, 100f, 10f), "Home's tabs under Settings")
        r.set("settings-seam", Rect(100f, 0f, 112f, 800f), hole = true, layer = 1)
        assertEquals(BandGrab.None, grab(r, 105f, 10f), "the overlay's own hole")
        r.set("window-buttons", Rect(900f, 0f, 1000f, 32f), hole = true, layer = Int.MAX_VALUE)
        assertEquals(BandGrab.None, grab(r, 950f, 10f), "window buttons outrank every cover")
        r.removeCover("settings")
        assertEquals(BandGrab.Native, grab(r, 300f, 10f))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun sharedUiExclusionsAndCoversLandInTheRegistry() = runComposeUiTest {
        val r = MacChromeRegions()
        setContent {
            CompositionLocalProvider(
                LocalMacWindowChrome provides r,
                LocalWindowDragChrome provides DesktopWindowDragChrome,
            ) {
                Box(Modifier.size(40.dp).excludeFromWindowDrag())
                CompositionLocalProvider(LocalWindowDragLayer provides 1) {
                    Box(Modifier.size(200.dp).windowDragCover())
                }
            }
        }
        waitForIdle()
        // The exclusion sits on layer 0, under the layer-1 cover: hidden there.
        assertFalse(r.isHole(Offset(5f, 5f)))
        assertEquals(BandGrab.Soft, bandGrab(MouseEvent.BUTTON1, Offset(5f, 5f), band, 1.0, r))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anExclusionIsAHole() = runComposeUiTest {
        val r = MacChromeRegions()
        setContent {
            CompositionLocalProvider(
                LocalMacWindowChrome provides r,
                LocalWindowDragChrome provides DesktopWindowDragChrome,
            ) {
                Box(Modifier.size(40.dp).excludeFromWindowDrag())
            }
        }
        waitForIdle()
        assertTrue(r.isHole(Offset(5f, 5f)))
    }
}
