package dev.supermux.webview.engine.mac

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The `Surface` node against a fake engine: what it reports, and what it draws. */
@OptIn(ExperimentalTestApi::class)
class MacSurfaceTest {
    /** Records what its surface tells it; draws [draw]. The composition has no AWT window: the handle is [handle]. */
    private class FakeHost(private val handle: Long = 7) : SurfaceHost {
        val placed = mutableListOf<Pair<Long, Rect>>()
        var density = 0f
        var attached: MacSurfaceNode? = null
        var detached = 0
        var unplaced = 0
        var draw: SurfaceDraw = SurfaceDraw.Hole

        override fun windowHandleOf(window: java.awt.Window?): Long = handle

        override fun surfaceAttached(node: MacSurfaceNode) {
            attached = node
        }

        override fun surfaceDetached(node: MacSurfaceNode) {
            detached++
        }

        override fun surfacePlaced(node: MacSurfaceNode, window: Long, bounds: Rect, visible: Rect, density: Float): Boolean {
            placed += window to bounds
            this.density = density
            return true
        }

        override fun surfaceUnplaced(node: MacSurfaceNode): Boolean {
            unplaced++
            return true
        }

        override fun drawFor(node: MacSurfaceNode): SurfaceDraw = draw
    }

    private fun solid(color: Color): ImageBitmap {
        val image = ImageBitmap(8, 8)
        Canvas(image).drawRect(0f, 0f, 8f, 8f, Paint().apply { this.color = color })
        return image
    }

    private fun ComposeUiTest.center(tag: String): Color {
        val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    @Test fun theSurfaceReportsItsSlotInWindowPixels() = runComposeUiTest {
        val host = FakeHost()
        var left by mutableStateOf(10.dp)
        setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(x = left, y = 20.dp).size(50.dp, 40.dp).then(MacSurfaceElement(host, inList = false)))
            }
        }
        waitForIdle()
        assertNotNull(host.attached, "attached")
        val d = host.density
        assertTrue(d > 0f)
        assertEquals(7L to Rect(10f * d, 20f * d, 60f * d, 60f * d), host.placed.last())
        left = 30.dp
        waitForIdle()
        assertEquals(30f * d, host.placed.last().second.left, "a move is reported")
    }

    @Test fun itDrawsAHoleTheCoverOrNothingAsItsEngineSays() = runComposeUiTest {
        val host = FakeHost()
        setContent {
            Box(Modifier.size(40.dp).background(Color.Blue).testTag("slot").then(MacSurfaceElement(host, inList = true)))
        }
        waitForIdle()
        assertEquals(0f, center("slot").alpha, "the hole: what is under the canvas shows")
        host.draw = SurfaceDraw.Cover(solid(Color.Red))
        runOnUiThread { host.attached!!.redraw() }
        waitForIdle()
        assertEquals(Color.Red, center("slot"), "the motion cover")
        host.draw = SurfaceDraw.Nothing
        runOnUiThread { host.attached!!.redraw() }
        waitForIdle()
        assertEquals(Color.Blue, center("slot"), "nothing: Compose's own content")
    }

    @Test fun aSlotNoLongerPlacedIsReported() = runComposeUiTest {
        val host = FakeHost()
        var place by mutableStateOf(true)
        setContent {
            Layout(content = { Box(Modifier.size(20.dp).then(MacSurfaceElement(host, inList = false))) }) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints) }
                layout(100, 100) { if (place) placeables.forEach { it.place(0, 0) } }
            }
        }
        waitForIdle()
        place = false
        waitForIdle()
        assertTrue(host.unplaced >= 1, "unplaced ${host.unplaced} times")
    }

    @Test fun leavingTheCompositionLetsTheEngineGo() = runComposeUiTest {
        val host = FakeHost()
        var shown by mutableStateOf(true)
        setContent {
            if (shown) Box(Modifier.size(40.dp).then(MacSurfaceElement(host, inList = false)))
        }
        waitForIdle()
        shown = false
        waitForIdle()
        assertEquals(1, host.detached)
    }
}
