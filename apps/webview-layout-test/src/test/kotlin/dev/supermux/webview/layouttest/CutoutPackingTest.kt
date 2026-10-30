package dev.supermux.webview.layouttest

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.supermux.webview.runtime.OverlayShape
import dev.supermux.webview.runtime.ScrimShape
import dev.supermux.webview.runtime.computeCutouts
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CutoutPackingTest {
    private val page = Rect(100f, 50f, 500f, 350f)
    private val visible = Rect(100f, 80f, 500f, 350f)
    private val menu = OverlayShape(RoundRect(Rect(200f, 100f, 300f, 200f), CornerRadius(8f)))
    private val scrim = ScrimShape(Rect(0f, 0f, 1000f, 1000f), Color.Black.copy(alpha = 0.32f))

    @Test fun holesAndScrimsArePageLocal() {
        val packed = packCutouts(computeCutouts(page, visible, listOf(menu), listOf(scrim)))
        assertContentEquals(
            floatArrayOf(
                0f, 30f, 400f, 300f, // the visible part of the page
                100f, 50f, 200f, 150f, 8f, 8f, 8f, 8f, 8f, 8f, 8f, 8f, // the menu, its radii kept
                0f, 30f, 400f, 300f, // the scrim, clipped to the visible part
            ),
            packed.geometry,
        )
        assertEquals(1, packed.holeCount)
        assertContentEquals(intArrayOf(Color.Black.copy(alpha = 0.32f).toArgb()), packed.scrimColors)
    }

    @Test fun theControlRunKeepsOnlyTheVisibleRect() {
        val packed = packCutouts(computeCutouts(page, visible, listOf(menu), listOf(scrim)), includeHoles = false)
        assertContentEquals(floatArrayOf(0f, 30f, 400f, 300f), packed.geometry)
        assertEquals(0, packed.holeCount)
        assertContentEquals(IntArray(0), packed.scrimColors)
    }

    @Test fun aPageWithNothingVisibleSendsAnEmptyRect() {
        val packed = packCutouts(computeCutouts(page, Rect.Zero, listOf(menu), emptyList()))
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 0f), packed.geometry)
        assertEquals(0, packed.holeCount)
    }
}
