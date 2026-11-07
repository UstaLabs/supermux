package dev.supermux.webview.engine.mac

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import dev.supermux.webview.runtime.OverlayShape
import dev.supermux.webview.runtime.ScrimShape
import dev.supermux.webview.runtime.computeCutouts
import org.junit.Assume.assumeTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `InputRegion` through the glue's own path (`WVRegionPath`, which `hitTest:` uses): a 400 × 300 px page at density 2
 * (a 200 × 150 pt view) at (100, 50) in its window, its top 30 px clipped away, with a menu over it whose corners are
 * rounded at 8 px. Points are page-local window px. On a Mac only.
 */
class InputRegionMacTest {
    private val page = Rect(100f, 50f, 500f, 350f)
    private val visible = Rect(100f, 80f, 500f, 350f)
    private val menu = OverlayShape(RoundRect(Rect(200f, 100f, 300f, 200f), CornerRadius(8f)))

    @BeforeTest fun onlyOnAMac() {
        assumeTrue("the glue runs on a Mac only", MacPlatform.isMac)
        assertNull(MacNativeLibrary.ensureLoaded(), "the packaged glue loads")
    }

    private fun takes(region: InputRegion, xPx: Float, yPx: Float): Boolean =
        MacGlue.testRegionTakes(region.geometry, region.holeCount, 2f, 150f, xPx / 2f, yPx / 2f)

    @Test fun thePageTakesItsVisibleAreaOutsideTheMenu() {
        val region = InputRegion.of(computeCutouts(page, visible, listOf(menu), emptyList()))
        assertTrue(takes(region, 50f, 200f), "the page, beside the menu")
        assertFalse(takes(region, 150f, 100f), "the menu")
        assertFalse(takes(region, 50f, 10f), "the clipped top")
        assertFalse(takes(region, 450f, 200f), "outside the page")
        assertTrue(takes(region, 101f, 51f), "the menu's square corner, outside its rounding: the page's")
        assertFalse(takes(region, 104f, 54f), "inside the menu's rounded corner: the menu's")
    }

    @Test fun aScrimTakesEverythingUnderIt() {
        val scrim = ScrimShape(Rect(0f, 0f, 1000f, 1000f), Color.Black.copy(alpha = 0.32f))
        val region = InputRegion.of(computeCutouts(page, visible, emptyList(), listOf(scrim)))
        assertFalse(takes(region, 50f, 200f))
    }

    @Test fun aPageWithNothingVisibleTakesNothing() {
        assertFalse(takes(InputRegion.None, 50f, 200f))
    }
}
