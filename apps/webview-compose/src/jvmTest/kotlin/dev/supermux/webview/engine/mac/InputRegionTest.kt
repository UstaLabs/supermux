package dev.supermux.webview.engine.mac

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import dev.supermux.webview.runtime.OverlayShape
import dev.supermux.webview.runtime.ScrimShape
import dev.supermux.webview.runtime.computeCutouts
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class InputRegionTest {
    private val page = Rect(100f, 50f, 500f, 350f)
    private val visible = Rect(100f, 80f, 500f, 350f)
    private val menu = OverlayShape(RoundRect(Rect(200f, 100f, 300f, 200f), CornerRadius(8f)))
    private val scrim = ScrimShape(Rect(0f, 0f, 1000f, 1000f), Color.Black.copy(alpha = 0.32f))

    @Test fun holesAndScrimsArePageLocal() {
        val region = InputRegion.of(computeCutouts(page, visible, listOf(menu), listOf(scrim)))
        assertContentEquals(
            floatArrayOf(
                0f, 30f, 400f, 300f, // the visible part of the page
                100f, 50f, 200f, 150f, 8f, 8f, 8f, 8f, 8f, 8f, 8f, 8f, // the menu, its radii kept
                0f, 30f, 400f, 300f, // the scrim, clipped to the visible part
            ),
            region.geometry,
        )
        assertEquals(1, region.holeCount)
        assertEquals(1, region.scrimCount)
    }

    @Test fun aPageWithNothingVisibleTakesNothing() {
        val region = InputRegion.of(computeCutouts(page, Rect.Zero, listOf(menu), emptyList()))
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 0f), region.geometry)
        assertEquals(0, region.holeCount)
        assertEquals(InputRegion.None, region)
    }

    @Test fun regionsCompareByTheirGeometry() {
        val a = InputRegion.of(computeCutouts(page, visible, listOf(menu), emptyList()))
        val b = InputRegion.of(computeCutouts(page, visible, listOf(menu), emptyList()))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, InputRegion.of(computeCutouts(page, visible, emptyList(), emptyList())))
    }
}
