package dev.supermux.webview

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * C2: a cover is a page's picture at its true size, top-left, the rest the page's own background, on every engine: a
 * page that reflows keeps its text size when its viewport changes, so a scaled picture is never what the page will
 * show. Never magnified, never squeezed.
 */
@OptIn(ExperimentalTestApi::class)
class CoverDrawingTest {
    /** A [width] × [height] page: [background], a [marker]-coloured square at ([x], [y]) of [side] px. */
    private fun page(width: Int, height: Int, background: Color, marker: Color = Color.Red, x: Int = 4, y: Int = 4, side: Int = 4): ImageBitmap {
        val image = ImageBitmap(width, height)
        val canvas = Canvas(image)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = background })
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + side).toFloat(), (y + side).toFloat(), Paint().apply { color = marker })
        return image
    }

    private fun draw(width: Int, height: Int, block: DrawScope.() -> Unit): PixelMap {
        val target = ImageBitmap(width, height)
        Canvas(target).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.Green })
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(width.toFloat(), height.toFloat()), block)
        return target.toPixelMap()
    }

    @Test fun aCoverIsItsTrueSizeTopLeftAndTheRestThePagesBackground() {
        // The reviewer's probe: an embed grown taller than its picture. The old cover filled the slot by its larger ratio.
        val picture = page(40, 20, Color.Blue)
        val pixels = draw(80, 80) { drawCover(picture, scale = 1f, background = Color.Blue) }
        assertEquals(Color.Red, pixels[5, 5], "the marker where the page has it")
        assertEquals(Color.Blue, pixels[17, 17], "and nowhere a magnified picture would put it")
        assertEquals(Color.Blue, pixels[60, 10], "the right: the page's background")
        assertEquals(Color.Blue, pixels[10, 60], "below: the page's background")
        assertEquals(Color.Blue, pixels[79, 79], "nothing of what is under it shows")
    }

    @Test fun itsScaleIsTheScreensNotTheSlots() {
        val picture = page(40, 20, Color.Blue)
        val pixels = draw(100, 30) { drawCover(picture, scale = 2f, background = Color.White) }
        assertEquals(Color.Red, pixels[9, 9], "the marker at twice its pixels: (8, 8) to (16, 16)")
        assertEquals(Color.Blue, pixels[20, 20], "the page's own pixels, cut at the slot's bottom")
        assertEquals(Color.White, pixels[90, 10], "past the picture: the background given")
    }

    @Test fun thePagesBackgroundIsTheColourAlongItsEdges() {
        // A page whose header runs into its top corners: its edges are still mostly its background.
        val picture = page(60, 40, Color(0xFF1E3A8A), marker = Color(0xFFF59E0B), x = 0, y = 0, side = 12)
        assertEquals(Color(0xFF1E3A8A), coverBackgroundOf(picture))
        assertEquals(Color.Transparent, coverBackgroundOf(ImageBitmap(1, 1)), "a transparent page fills nothing")
    }

    @Test fun aViewOfThePicturesShapeIsFilledAndAnyOtherKeepsItsTrueSize() {
        // Unchanged (a cover is the same size as its view), whatever its pixels a dp: filled exactly.
        assertEquals(1f, coverScale(400, 300, Size(400f, 300f), density = 2f, imageDensity = 2f))
        assertEquals(400f / 300f, coverScale(300, 225, Size(400f, 300f), density = 2f, imageDensity = 2f), "drawn at 1.5 px a dp")
        // Grown taller (an embed), or wider (a pane): its true size, at the density it was taken at.
        assertEquals(1f, coverScale(400, 300, Size(400f, 500f), density = 2f, imageDensity = 2f))
        assertEquals(1f, coverScale(400, 300, Size(600f, 300f), density = 2f, imageDensity = 2f))
        // Moved to a screen of another density: still its size in dp.
        assertEquals(0.5f, coverScale(400, 300, Size(200f, 200f), density = 1f, imageDensity = 2f))
    }

    /** An engine whose snapshots have their top-left fifth red: how wide a cover draws it tells the scale it drew at. */
    private class Marked(val fake: FakeWebEngine) : WebEngine by fake {
        override suspend fun snapshot(): ImageBitmap? {
            val image = fake.snapshot() ?: return null
            Canvas(image).drawRect(0f, 0f, image.width / 5f, image.height / 5f, Paint().apply { color = Color.Red })
            return image
        }
    }

    /** How many pixels of [pixels]' row [y], from its left, are red. */
    private fun redRun(pixels: PixelMap, y: Int): Int {
        var x = 0
        while (x < pixels.width && pixels[x, y].let { it.red > 0.6f && it.blue < 0.4f }) x++
        return x
    }

    /**
     * A snapshot the runtime keeps at fewer pixels a dp than the screen has (three at most; here the screen has four) is
     * still drawn at its true size in a view of another shape: the cover's scale comes from the pixels a dp it was kept
     * at, not from the screen's density then.
     */
    @Test fun aSnapshotKeptBelowTheScreensDensityCoversAtItsTrueSize() = runComposeUiTest {
        val fakes = FakeWebEngineFactory(behavior = FakeEngineBehavior(color = Color.Blue))
        val runtime = testRuntime(
            WebEngineFactory { config -> WebEngineResult.Created(Marked((fakes.create(config) as WebEngineResult.Created).engine as FakeWebEngine)) },
        )
        val state = WebViewState(WebContent.Url("https://example.test/"))
        val light = WebSettings(darkMode = DarkMode.Light)
        var composed by mutableStateOf(true)
        var activation by mutableStateOf<Activation>(Activation.WhenVisible(settleMillis = 0))
        var height by mutableStateOf(50.dp)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(4f)) {
                ProvideWebViewRuntime(runtime) {
                    if (composed) WebView(state, Modifier.size(50.dp, height).testTag("view"), settings = light, activation = activation)
                }
            }
        }
        waitUntil(conditionDescription = "live", timeoutMillis = TIMEOUT) { state.phase == WebViewPhase.Live }
        mainClock.advanceTimeBy(600)
        waitUntil(conditionDescription = "the settle snapshot", timeoutMillis = TIMEOUT) { runOnUiThread { state.snapshotKey } != null }
        val key = assertNotNull(runOnUiThread { state.snapshotKey })
        assertEquals(150, key.width, "taken at 200 px, kept at three pixels a dp")
        assertEquals(3f, key.pixelsPerDp, "its key says so")
        // The view goes, and comes back taller, not to be activated: its cover, in a view of another shape.
        runOnUiThread {
            composed = false
            activation = Activation.OnInteraction
        }
        waitForIdle()
        runOnUiThread {
            height = 80.dp
            composed = true
        }
        waitForIdle()
        val pixels = onNodeWithTag("view").captureToImage().toPixelMap()
        assertEquals(200, pixels.width, "the view, 50 dp at four px a dp")
        // The page's red fifth is 10 dp of its 50: 40 px here. Drawn at the screen's density then, 30 px.
        val red = redRun(pixels, 4)
        assertTrue(red in 38..42, "the page's red fifth drawn $red px wide, not 40: the cover isn't at its true size")
    }
}
