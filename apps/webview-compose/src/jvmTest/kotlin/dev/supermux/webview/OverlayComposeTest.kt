package dev.supermux.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.boundingRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import dev.supermux.webview.runtime.OverlayShape
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.ScrimShape
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class OverlayComposeTest {
    private fun ComposeUiTest.px(dp: Dp): Float = with(density) { dp.toPx() }

    private val RoundRect.corners: List<CornerRadius>
        get() = listOf(topLeftCornerRadius, topRightCornerRadius, bottomRightCornerRadius, bottomLeftCornerRadius)

    private fun assertBounds(expected: Rect, actual: RoundRect, tolerance: Float = 0.01f) {
        val message = "expected $expected, was ${actual.boundingRect}"
        assertEquals(expected.left, actual.left, tolerance, message)
        assertEquals(expected.top, actual.top, tolerance, message)
        assertEquals(expected.right, actual.right, tolerance, message)
        assertEquals(expected.bottom, actual.bottom, tolerance, message)
    }

    private fun assertCorners(expected: CornerRadius, actual: RoundRect, tolerance: Float = 0.01f) {
        for (corner in actual.corners) {
            assertEquals(expected.x, corner.x, tolerance, "corners ${actual.corners}")
            assertEquals(expected.y, corner.y, tolerance, "corners ${actual.corners}")
        }
    }

    @Test fun anOverlayInsideAPopupReportsItsWindowBoundsAndCorners() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(400.dp)) {
                    Popup(offset = IntOffset(50, 60)) {
                        Box(Modifier.size(100.dp, 40.dp).webViewOverlay(RoundedCornerShape(8.dp)).testTag("menu"))
                    }
                }
            }
        }
        waitForIdle()
        val shape = runtime.overlays.shapes.single()
        assertEquals(onNodeWithTag("menu").fetchSemanticsNode().boundsInWindow, shape.bounds)
        assertEquals(Offset(50f, 60f), shape.bounds.topLeft)
        assertCorners(CornerRadius(px(8.dp)), shape.shape, tolerance = 0.5f)
    }

    @Test fun anOverlayLeavingTheCompositionIsForgotten() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                if (shown) Box(Modifier.size(50.dp).webViewOverlay())
            }
        }
        waitForIdle()
        assertEquals(1, runtime.overlays.shapes.size)
        shown = false
        waitForIdle()
        assertTrue(runtime.overlays.shapes.isEmpty())
    }

    @Test fun aScrimIsRegisteredWithItsWindowBoundsWhileShown() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(200.dp)) {
                    if (shown) Box(Modifier.offset(10.dp, 20.dp).size(50.dp).webViewScrim(Color.Black).testTag("scrim"))
                }
            }
        }
        waitForIdle()
        val bounds = onNodeWithTag("scrim").fetchSemanticsNode().boundsInWindow
        assertEquals(Rect(px(10.dp), px(20.dp), px(60.dp), px(70.dp)), bounds)
        assertEquals(listOf(ScrimShape(bounds, Color.Black)), runtime.overlays.scrims)
        shown = false
        waitForIdle()
        assertTrue(runtime.overlays.scrims.isEmpty())
    }

    /** A scrim has no corners to round: clipped, it reports what is drawn, so the page doesn't dim where it isn't. */
    @Test fun aClippedScrimReportsWhatIsDrawn() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(100.dp).clipToBounds()) {
                    Box(Modifier.offset(60.dp, 10.dp).size(80.dp, 40.dp).webViewScrim(Color.Black))
                }
            }
        }
        waitForIdle()
        assertEquals(listOf(ScrimShape(Rect(px(60.dp), px(10.dp), px(100.dp), px(50.dp)), Color.Black)), runtime.overlays.scrims)
    }

    /** Clipped bounds with the outline's radii would round the corners at the clipped edge, and cut too little there. */
    @Test fun aClippedOverlayReportsItsWholeShape() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(100.dp).clipToBounds()) {
                    Box(Modifier.offset(60.dp, 10.dp).size(80.dp, 40.dp).webViewOverlay(RoundedCornerShape(8.dp)).testTag("menu"))
                }
            }
        }
        waitForIdle()
        assertEquals(px(100.dp), onNodeWithTag("menu").fetchSemanticsNode().boundsInWindow.right, "the ancestor clips it")
        val shape = runtime.overlays.shapes.single().shape
        assertBounds(Rect(px(60.dp), px(10.dp), px(140.dp), px(50.dp)), shape)
        assertCorners(CornerRadius(px(8.dp)), shape)
    }

    /** A bottom sheet's shape: rounded top corners, square bottom ones. topStart is the left corner in LTR, the right in RTL. */
    @Test fun everyCornerKeepsItsOwnRadiusInEitherLayoutDirection() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        var direction by mutableStateOf(LayoutDirection.Ltr)
        setContent {
            ProvideWebViewRuntime(runtime) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(Modifier.size(100.dp, 40.dp).webViewOverlay(RoundedCornerShape(topStart = 16.dp, topEnd = 8.dp)))
                }
            }
        }
        waitForIdle()
        val start = CornerRadius(px(16.dp))
        val end = CornerRadius(px(8.dp))
        assertEquals(listOf(start, end, CornerRadius.Zero, CornerRadius.Zero), runtime.overlays.shapes.single().shape.corners)
        direction = LayoutDirection.Rtl
        waitForIdle()
        assertEquals(listOf(end, start, CornerRadius.Zero, CornerRadius.Zero), runtime.overlays.shapes.single().shape.corners)
    }

    @Test fun anyOtherOutlineBecomesItsBoundsInTheWindow() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        // A path over the left half of its layout: its bounds, not the layout's.
        val leftHalf = GenericShape { size, _ ->
            moveTo(0f, 0f)
            lineTo(size.width / 2, size.height / 2)
            lineTo(0f, size.height)
            close()
        }
        // A rectangle outline smaller than its layout: that rectangle.
        val inset = object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
                Outline.Rectangle(Rect(10f, 5f, size.width - 10f, size.height - 5f))
        }
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box {
                    Box(Modifier.offset(10.dp, 20.dp).size(100.dp, 40.dp).webViewOverlay(leftHalf))
                    Box(Modifier.offset(200.dp, 20.dp).size(100.dp, 40.dp).webViewOverlay(inset))
                }
            }
        }
        waitForIdle()
        assertEquals(
            setOf(
                OverlayShape(Rect(px(10.dp), px(20.dp), px(10.dp) + px(100.dp) / 2, px(60.dp))),
                OverlayShape(Rect(px(200.dp) + 10f, px(20.dp) + 5f, px(300.dp) - 10f, px(60.dp) - 5f)),
            ),
            runtime.overlays.shapes.toSet(),
        )
    }

    /** Material's menus and tooltips scale in: what is cut out is what is drawn, at every frame. */
    @Test fun aScaledOverlayReportsTheShapeItIsDrawnAt() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        var scaled by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(
                    Modifier.offset(20.dp, 20.dp).graphicsLayer {
                        scaleX = if (scaled) 0.5f else 1f
                        scaleY = if (scaled) 0.25f else 1f
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                ) {
                    Box(Modifier.size(100.dp, 40.dp).webViewOverlay(RoundedCornerShape(8.dp)))
                }
            }
        }
        waitForIdle()
        var shape = runtime.overlays.shapes.single().shape
        assertBounds(Rect(px(20.dp), px(20.dp), px(70.dp), px(30.dp)), shape)
        assertCorners(CornerRadius(px(4.dp), px(2.dp)), shape)
        // Only the layer changes: no recomposition, no layout.
        scaled = false
        waitForIdle()
        shape = runtime.overlays.shapes.single().shape
        assertBounds(Rect(px(20.dp), px(20.dp), px(120.dp), px(60.dp)), shape)
        assertCorners(CornerRadius(px(8.dp)), shape)
    }

    /** Turned, an overlay's corners can't be mapped: the box around it is cut out, square, which covers it. */
    @Test fun aRotatedOverlayReportsTheBoxAroundIt() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(
                    Modifier.offset(100.dp, 100.dp).size(100.dp, 40.dp).graphicsLayer { rotationZ = 45f }
                        .webViewOverlay(RoundedCornerShape(8.dp)),
                )
            }
        }
        waitForIdle()
        val shape = runtime.overlays.shapes.single().shape
        // 100 × 40 turned 45° around its centre (150, 120): (100 + 40) / √2 on each side.
        val half = 140f / 2 / kotlin.math.sqrt(2f)
        val box = Rect(Offset(px(150.dp), px(120.dp)), radius = px(half.dp))
        assertBounds(box, shape, tolerance = 0.5f)
        assertCorners(CornerRadius.Zero, shape)
    }

    @Test fun anOverlayFollowsItsComposableAndItsShape() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        var x by mutableStateOf(0.dp)
        var shape by mutableStateOf<Shape>(RectangleShape)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.offset(x, 0.dp).size(50.dp).webViewOverlay(shape))
            }
        }
        waitForIdle()
        assertEquals(listOf(OverlayShape(Rect(0f, 0f, px(50.dp), px(50.dp)))), runtime.overlays.shapes)
        x = 30.dp
        waitForIdle()
        assertEquals(listOf(OverlayShape(Rect(px(30.dp), 0f, px(80.dp), px(50.dp)))), runtime.overlays.shapes)
        // Nothing moves: the new shape alone is reported.
        shape = RoundedCornerShape(10.dp)
        waitForIdle()
        val reported = runtime.overlays.shapes.single().shape
        assertBounds(Rect(px(30.dp), 0f, px(80.dp), px(50.dp)), reported)
        assertCorners(CornerRadius(px(10.dp)), reported)
    }

    @Test fun anOverlayAddedToALaidOutComposableIsReported() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        var overlaid by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(50.dp).then(if (overlaid) Modifier.webViewOverlay() else Modifier))
            }
        }
        waitForIdle()
        assertTrue(runtime.overlays.shapes.isEmpty())
        overlaid = true
        waitForIdle()
        assertEquals(listOf(OverlayShape(Rect(0f, 0f, px(50.dp), px(50.dp)))), runtime.overlays.shapes)
        overlaid = false
        waitForIdle()
        assertTrue(runtime.overlays.shapes.isEmpty())
    }

    @Test fun stackedScrimsComeInTheOrderTheyAppearedAndKeepItWhenTheyChange() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        var outer by mutableStateOf(Color.Black)
        var nested by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(200.dp).webViewScrim(outer)) {
                    if (nested) Box(Modifier.size(100.dp).webViewScrim(Color.Red))
                }
            }
        }
        waitForIdle()
        nested = true
        waitForIdle()
        assertEquals(listOf(Color.Black, Color.Red), runtime.overlays.scrims.map { it.color })
        outer = Color.Blue
        waitForIdle()
        assertEquals(
            listOf(
                ScrimShape(Rect(0f, 0f, px(200.dp), px(200.dp)), Color.Blue),
                ScrimShape(Rect(0f, 0f, px(100.dp), px(100.dp)), Color.Red),
            ),
            runtime.overlays.scrims,
        )
    }

    @Test fun overlaysAndScrimsInsideADialogAreReported() = runComposeUiTest {
        val runtime = testRuntime(FakeWebEngineFactory())
        setContent {
            ProvideWebViewRuntime(runtime) {
                Dialog(onDismissRequest = {}) {
                    Box(Modifier.size(100.dp).webViewScrim(Color.Black).webViewOverlay(RoundedCornerShape(28.dp)).testTag("dialog"))
                }
            }
        }
        waitForIdle()
        val bounds = onNodeWithTag("dialog").fetchSemanticsNode().boundsInWindow
        assertEquals(bounds, runtime.overlays.shapes.single().bounds)
        assertEquals(listOf(ScrimShape(bounds, Color.Black)), runtime.overlays.scrims)
    }

    @Test fun anOverlayRegistersWithTheRuntimeProvidedAndMovesWithIt() = runComposeUiTest {
        val first = testRuntime(FakeWebEngineFactory())
        val second = testRuntime(FakeWebEngineFactory())
        var provided by mutableStateOf(first)
        setContent {
            ProvideWebViewRuntime(provided) {
                Box(Modifier.size(50.dp).webViewOverlay().webViewScrim(Color.Black))
            }
        }
        waitForIdle()
        assertEquals(1, first.overlays.shapes.size)
        assertEquals(1, first.overlays.scrims.size)
        assertTrue(second.overlays.shapes.isEmpty() && second.overlays.scrims.isEmpty())
        provided = second
        waitForIdle()
        assertTrue(first.overlays.shapes.isEmpty() && first.overlays.scrims.isEmpty())
        assertEquals(1, second.overlays.shapes.size)
        assertEquals(1, second.overlays.scrims.size)
    }

    @Test fun withoutAProvidedRuntimeOverlaysGoToTheDefaultOne() = runComposeUiTest {
        var shown by mutableStateOf(true)
        setContent {
            if (shown) Box(Modifier.size(50.dp).webViewOverlay())
        }
        waitForIdle()
        assertEquals(listOf(OverlayShape(Rect(0f, 0f, px(50.dp), px(50.dp)))), WebViewRuntime.Default.overlays.shapes)
        shown = false
        waitForIdle()
        assertTrue(WebViewRuntime.Default.overlays.shapes.isEmpty())
    }
}
