package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MacWindowChromeTest {

    // ── MacChromeRegions decision logic (what the AWT listener feeds to forceHitTest) ──

    @Test
    fun nativeDragRequiresADragRegion() {
        val r = MacChromeRegions()
        assertFalse(r.allowsNativeDrag(Offset(10f, 10f)), "empty registry must stay client")
        r.set("band", Rect(0f, 0f, 100f, 28f), hole = false)
        assertTrue(r.allowsNativeDrag(Offset(10f, 10f)))
        assertFalse(r.allowsNativeDrag(Offset(150f, 10f)), "outside the region must stay client")
    }

    @Test
    fun aHolePunchesOutOfAnOverlappingDragRegion() {
        val r = MacChromeRegions()
        r.set("band", Rect(0f, 0f, 200f, 28f), hole = false)
        r.set("toggle", Rect(78f, 0f, 106f, 28f), hole = true)
        assertTrue(r.allowsNativeDrag(Offset(50f, 10f)), "band left of the toggle drags")
        assertFalse(r.allowsNativeDrag(Offset(90f, 10f)), "the toggle hole must stay client")
        assertTrue(r.allowsNativeDrag(Offset(150f, 10f)), "band right of the toggle drags")
    }

    @Test
    fun removingARegionRevertsItsArea() {
        val r = MacChromeRegions()
        r.set("band", Rect(0f, 0f, 100f, 28f), hole = false)
        r.remove("band", hole = false)
        assertFalse(r.allowsNativeDrag(Offset(10f, 10f)))
    }

    // ── Modifier plumbing: bounds land in (and leave) the provided registry ──

    @Test
    fun modifiersRegisterBoundsIntoTheLocalRegistry() = runComposeUiTest {
        val regions = MacChromeRegions()
        setContent {
            CompositionLocalProvider(LocalMacWindowChrome provides regions) {
                Row {
                    Box(Modifier.size(40.dp).macTitleBarDragRegion("drag"))
                    Box(Modifier.size(40.dp).macTitleBarNoDragRegion("hole"))
                }
            }
        }
        waitForIdle()
        // Test density is 1: dp == px. First box = drag, second (offset 40) = hole.
        assertTrue(regions.allowsNativeDrag(Offset(5f, 5f)))
        assertFalse(regions.allowsNativeDrag(Offset(45f, 5f)), "hole box is not a drag region")
        assertFalse(regions.allowsNativeDrag(Offset(85f, 5f)), "unregistered space stays client")
    }

    @Test
    fun modifiersAreNoOpsWithoutAnInstalledChrome() = runComposeUiTest {
        // LocalMacWindowChrome defaults to null (non-mac / non-JBR): composing the modifiers
        // must not throw and must register nowhere.
        setContent {
            Box(Modifier.size(40.dp).macTitleBarDragRegion("drag"))
        }
        waitForIdle()
    }

    @Test
    fun theViewTabStripRegistersTailDragAndTabsHole() = runComposeUiTest {
        val regions = MacChromeRegions()
        setContent {
            CompositionLocalProvider(LocalMacWindowChrome provides regions) {
                Box(Modifier.size(400.dp, 32.dp)) {
                    ViewTabStrip(
                        viewIds = listOf("v1"),
                        activeViewId = "v1",
                        titleFor = { "t" },
                        onSelect = {},
                        onClose = {},
                    )
                }
            }
        }
        waitForIdle()
        // The strip tail (far right, no tab content) drags; the tab area does not.
        assertTrue(regions.allowsNativeDrag(Offset(390f, 10f)), "empty strip tail must drag")
        assertFalse(regions.allowsNativeDrag(Offset(10f, 10f)), "tab content must stay client")
    }
}
