package dev.supermux.webview.engine.android

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.runtime.OverlayCutouts
import dev.supermux.webview.runtime.OverlayShape
import dev.supermux.webview.runtime.drawScale
import dev.supermux.webview.runtime.snapshotSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Android snapshots are the page drawn by itself, never the app over it (keep pages alive, Task 6), on the phone. */
@RunWith(AndroidJUnit4::class)
class EngineSnapshotDrawTest {
    private fun ImageBitmap.centre(): Int = toPixelMap().let { it[it.width / 2, it.height / 2].toArgb() }

    private fun Int.hex(): String = "#" + Integer.toHexString(this)

    private fun Int.red(): Int = this shr 16 and 0xff

    private fun Int.green(): Int = this shr 8 and 0xff

    private fun Int.blue(): Int = this and 0xff

    /** What a view of the runtime would tell the engine with app UI over the middle of its page. */
    private fun overlaid(width: Int, height: Int): OverlayCutouts {
        val page = Rect(0f, 0f, width.toFloat(), height.toFloat())
        val middle = Rect(width / 3f, height / 3f, width * 2 / 3f, height * 2 / 3f)
        return OverlayCutouts(page, page, listOf(OverlayShape(RoundRect(middle))), emptyList())
    }

    /** A red Compose box over the centre of a blue page as the snapshot is taken: the snapshot's centre is blue. */
    @Test fun aSnapshotNeverIncludesAComposeOverlay() {
        val harness = EngineHarness.open(surface = { engine ->
            Box(Modifier.fillMaxSize()) {
                engine.Surface(Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.Center).size(160.dp).background(Color.Red))
            }
        })
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.marked("#0000ff"))
                delay(300)
                val image = assertNotNull(harness.main { snapshot() }, "no snapshot of the page")
                val centre = image.centre()
                assertTrue(centre.blue() > 180 && centre.red() < 60 && centre.green() < 60, "the page's blue: ${centre.hex()}")
            }
        } finally {
            harness.close()
        }
    }

    /** Half of the view below the window's edge: the snapshot is still the page, which a copy of the window can't give. */
    @Test fun aSnapshotOfAPageHalfOffScreenIsStillThePage() {
        val harness = EngineHarness.open(surface = { engine ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                engine.Surface(Modifier.fillMaxWidth().height(maxHeight).offset(y = maxHeight / 2))
            }
        })
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.marked("#00ff00"))
                delay(300)
                assertTrue(harness.main { !wholeOnScreen(webView) }, "the view is half off screen")
                val image = assertNotNull(harness.main { snapshot() }, "no snapshot of a page half off screen")
                val centre = image.centre()
                assertTrue(centre.green() > 180 && centre.red() < 60 && centre.blue() < 60, "the page's green: ${centre.hex()}")
            }
        } finally {
            harness.close()
        }
    }

    /**
     * A draw that comes out all one colour is discarded: here a page that is all one colour, so the draw is blank for
     * sure. With app UI over the page the engine copies nothing of the window either; with nothing over it, wholly on
     * screen, a copy of the window stands in. A WebGL page, which the software path may not reach, is drawn too, and
     * what it gave is logged: blank, or the page's red, never a white picture kept.
     */
    @Test fun aBlankSoftwareDrawIsDiscarded() {
        val harness = EngineHarness.open()
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.solid("#ff00ff"))
                delay(300)
                val (width, height) = harness.main { webView.width to webView.height }
                val size = snapshotSize(width, height, width / harness.activity.resources.displayMetrics.density)
                val draw = harness.main { drawPage(webView, size) }
                assertTrue(draw is PageDraw.Blank, "a page all of one colour draws blank: $draw")
                harness.main { overlaysAtSnapshot(overlaid(width, height)) }
                assertNull(harness.main { snapshot() }, "a blank draw kept, or the window copied with app UI over the page")
                harness.main { overlaysAtSnapshot(OverlayCutouts(page(width, height), page(width, height), emptyList(), emptyList())) }
                val copy = assertNotNull(harness.main { snapshot() }, "nothing over the page: a copy of the window stands in")
                val centre = copy.centre()
                assertTrue(centre.red() > 180 && centre.blue() > 180 && centre.green() < 60, "the page's magenta: ${centre.hex()}")

                harness.loadAndPaint(TestPages.webGl)
                harness.seenOrAwait("the WebGL page's title") { it is EngineEvent.TitleChanged && it.title?.startsWith("webgl") == true }
                delay(300)
                val webGl = harness.main { drawPage(webView, size) }
                Log.i(TAG, "a WebGL page's own draw: ${webGl::class.simpleName} in ${webGl.nanos / 100_000 / 10.0} ms")
                when (webGl) {
                    is PageDraw.Blank -> Unit
                    is PageDraw.Picture -> {
                        val pixel = webGl.bitmap.getPixel(webGl.bitmap.width / 2, webGl.bitmap.height / 2)
                        assertTrue(pixel.red() > 180 && pixel.green() < 60 && pixel.blue() < 60, "WebGL's red: ${pixel.hex()}")
                    }
                    is PageDraw.Failed -> fail("the WebGL page's draw threw: ${webGl.error}")
                }
            }
        } finally {
            harness.close()
        }
    }

    /**
     * The page's own draw of an agent demo filling the screen, at the screen's density, timed over 20 draws: the first,
     * cold, and the p50 and max of the 19 after it go to logcat, for the report. Then the engine's snapshot of it, at
     * that density: a document's first draw that takes too long is kept, and leaves the budget as it was.
     */
    @Test fun thePagesOwnDrawAtTheScreensDensityIsTimed() {
        val harness = EngineHarness.open()
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.demo)
                delay(500)
                val (width, height) = harness.main { webView.width to webView.height }
                val density = harness.activity.resources.displayMetrics.density
                val size = snapshotSize(width, height, width / density, maxScale = minOf(density, 3f))
                assertEquals(minOf(density, 3f), size.width * density / width, 0.01f, "the screen's density, 3 at most")
                val nanos = ArrayList<Long>()
                repeat(20) {
                    val draw = harness.main { drawPage(webView, size) }
                    assertTrue(draw is PageDraw.Picture, "draw ${it + 1}: $draw")
                    draw.bitmap.recycle()
                    nanos += draw.nanos
                    delay(50)
                }
                val first = nanos.first() / 100_000 / 10.0
                val warm = nanos.drop(1).sorted()
                val p50 = warm[warm.size / 2] / 100_000 / 10.0
                val max = warm.last() / 100_000 / 10.0
                val over = warm.count { it > MAX_PAGE_DRAW_MILLIS * 1_000_000 }
                Log.i(
                    TAG,
                    "the page's own draw at the screen's density (${"%.3f".format(density)}), a demo at " +
                        "${size.width}x${size.height}: the first $first ms; the 19 after it p50 $p50 ms, max $max ms, " +
                        "$over over $MAX_PAGE_DRAW_MILLIS ms",
                )
                PageDrawBudget.maxPixels = null
                val image = assertNotNull(harness.main { snapshot() }, "the engine's snapshot")
                assertEquals(size.width to size.height, image.width to image.height)
                assertEquals(ImageBitmapConfig.Argb8888, image.config)
                assertEquals(null, PageDrawBudget.maxPixels, "the document's first draw leaves the budget as it was")
            }
        } finally {
            PageDrawBudget.maxPixels = null
            harness.close()
        }
    }

    /**
     * A warm draw of the whole screen that took too long (simulated: 40 ms): the engine draws a view as big at the
     * largest scale that fits from then on, while a chat's embed would still be drawn at the screen's density.
     */
    @Test fun aDrawThatTookTooLongMakesTheNextOnesOfAViewAsBigSmaller() {
        val harness = EngineHarness.open()
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.demo)
                delay(300)
                val (width, height) = harness.main { webView.width to webView.height }
                val density = harness.activity.resources.displayMetrics.density
                val full = minOf(density, 3f)
                val slow = 40_000_000L
                val budget = harness.main { PageDrawBudget.overran(width.toLong() * height, slow) }
                val scale = drawScale(width / density, height / density, full, budget)
                assertTrue(scale < full, "$scale")
                assertEquals(full, drawScale(411f, 300f, full, budget), "a chat's embed")
                val image = assertNotNull(harness.main { snapshot() })
                val expected = snapshotSize(width, height, width / density, maxScale = scale)
                assertEquals(expected, IntSize(image.width, image.height))
                val got = "${image.width}x${image.height} of ${width}x$height"
                Log.i(TAG, "after a whole-screen draw that took 40 ms, at most $budget pixels: $scale px a dp, $got")
            }
        } finally {
            PageDrawBudget.maxPixels = null
            harness.close()
        }
    }

    private fun page(width: Int, height: Int) = Rect(0f, 0f, width.toFloat(), height.toFloat())
}
