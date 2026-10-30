package dev.supermux.webview.engine.mac

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import dev.supermux.webview.runtime.ProvideWebViewOverlays
import dev.supermux.webview.webViewOverlay
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
    /**
     * Records what its surface tells it; draws [draw]. The composition has no AWT window: the handle is [handle], and the
     * window's backing scale [scale].
     */
    private class FakeHost(private val handle: Long = 7, val scale: Float = 2f) : SurfaceHost {
        val placed = mutableListOf<Pair<Long, Rect>>()
        var placedScale = 0f
        var attached: MacSurfaceNode? = null
        var detached = 0
        var unplaced = 0
        var draw: SurfaceDraw = SurfaceDraw.Hole

        override fun windowHandleOf(window: java.awt.Window?): Long = handle

        override fun scaleOf(window: java.awt.Window?): Float = scale

        override fun surfaceAttached(node: MacSurfaceNode) {
            attached = node
        }

        override fun surfaceDetached(node: MacSurfaceNode) {
            detached++
        }

        override fun surfacePlaced(node: MacSurfaceNode, window: Long, bounds: Rect, visible: Rect, scale: Float): Boolean {
            placed += window to bounds
            placedScale = scale
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
        var d = 0f
        setContent {
            d = LocalDensity.current.density
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(x = left, y = 20.dp).size(50.dp, 40.dp).then(MacSurfaceElement(host, inList = false)))
            }
        }
        waitForIdle()
        assertNotNull(host.attached, "attached")
        assertTrue(d > 0f)
        assertEquals(7L to Rect(10f * d, 20f * d, 60f * d, 60f * d), host.placed.last())
        left = 30.dp
        waitForIdle()
        assertEquals(30f * d, host.placed.last().second.left, "a move is reported")
    }

    /**
     * I3: an app's UI zoom (its own `LocalDensity`) lays out more pixels a dp, but Compose's window pixels stay the
     * window's backing pixels: the slot goes to the glue at the window's backing scale, never at the app's density.
     */
    @Test fun theSlotIsReportedAtTheWindowsBackingScaleWhateverTheAppsDensity() = runComposeUiTest {
        val host = FakeHost(scale = 2f)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2.5f)) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.offset(x = 10.dp, y = 20.dp).size(40.dp, 30.dp).then(MacSurfaceElement(host, inList = false)))
                }
            }
        }
        waitForIdle()
        assertEquals(2f, host.placedScale, "the window's backing scale")
        assertEquals(7L to Rect(25f, 50f, 125f, 125f), host.placed.last(), "laid out at the app's 2.5 px a dp")
    }

    /** A scaled ancestor: the slot is where, and as big as, Compose draws it (the page is resized, not scaled). */
    @Test fun aScaledAncestorShrinksTheSlot() = runComposeUiTest {
        val host = FakeHost()
        var d = 0f
        setContent {
            d = LocalDensity.current.density
            Box(Modifier.offset(x = 10.dp).graphicsLayer(scaleX = 0.5f, scaleY = 0.5f, transformOrigin = TransformOrigin(0f, 0f))) {
                Box(Modifier.size(100.dp, 80.dp).then(MacSurfaceElement(host, inList = false)))
            }
        }
        waitForIdle()
        assertEquals(7L to Rect(10f * d, 0f, 60f * d, 40f * d), host.placed.last())
    }

    /** I1: a press the page took reaches Compose (to close a popup) only while the window has a marked overlay. */
    @Test fun aPressThePageTookIsHandedToComposeOnlyWhileAnOverlayIsOpen() = runComposeUiTest {
        val host = FakeHost()
        val sent = mutableListOf<Pair<Int, Int>>()
        var menu by mutableStateOf(false)
        setContent {
            ProvideWebViewOverlays {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(40.dp).then(MacSurfaceElement(host, inList = false) { _, x, y -> sent += x to y }))
                    if (menu) Box(Modifier.offset(x = 60.dp).size(20.dp).webViewOverlay())
                }
            }
        }
        waitForIdle()
        runOnUiThread { host.attached!!.dismissPopupsAt(5.0, 6.0) }
        assertEquals(emptyList<Pair<Int, Int>>(), sent, "no overlay open: nothing to close, nothing sent")
        menu = true
        waitForIdle()
        runOnUiThread { host.attached!!.dismissPopupsAt(5.0, 6.0) }
        assertEquals(listOf(5 to 6), sent)
    }

    @Test fun itDrawsAHoleTheCoverOrNothingAsItsEngineSays() = runComposeUiTest {
        val host = FakeHost()
        setContent {
            Box(Modifier.size(40.dp).background(Color.Blue).testTag("slot").then(MacSurfaceElement(host, inList = true)))
        }
        waitForIdle()
        assertEquals(0f, center("slot").alpha, "the hole: what is under the canvas shows")
        host.draw = SurfaceDraw.Cover(solid(Color.Red), scale = 20f, background = Color.Red)
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
