package dev.supermux.webview.engine.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.WebContent
import dev.supermux.webview.runtime.SnapshotCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sharp snapshots, the quality bar (decision 5), on the phone. A cover as the runtime keeps and draws it (the page's
 * own draw at the screen's density, stored as a JPEG at quality 92, decoded, and drawn over the view) against the live
 * page as the screen shows it at the same moment (a copy of the window), on a still page, text included: the mean
 * absolute channel difference is at most 2, and the 99th percentile at most 16. The view is a chat's embed, the
 * screen's width and 480 dp tall, in the middle of the screen, clear of the system bars, which the system draws over
 * the window (a copy of it has them; the page's own draw doesn't). The sizes, and the encode and decode times over 20,
 * go to logcat.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotQualityTest {
    @Test fun aCoverOfAnAgentDemoLooksLikeThePage() = coverLooksLikeThePage(TestPages.demo, "an agent demo")

    @Test fun aCoverOfAnArticleLooksLikeThePage() = coverLooksLikeThePage(TestPages.article, "an article")

    private fun coverLooksLikeThePage(page: WebContent, what: String) {
        PageDrawBudget.maxPixels = null
        val harness = EngineHarness.open(surface = { engine ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                engine.Surface(Modifier.fillMaxWidth().height(480.dp))
            }
        })
        try {
            runBlocking {
                harness.loadAndPaint(page)
                // A still page: painted, laid out, its fonts in.
                delay(1_000)
                val (width, height) = harness.main { webView.width to webView.height }
                assertTrue(harness.main { wholeOnScreen(webView) }, "the view is wholly on screen")
                val density = harness.activity.resources.displayMetrics.density
                // A draw that takes too long gives none, and the next one fits.
                val snapshot = harness.main { snapshot() }
                    ?: assertNotNull(harness.main { snapshot() }, "the page's own draw")
                // The live page, as the screen shows it.
                val live = assertNotNull(harness.main { copyPixels(webView, IntSize(width, height)) }, "a window copy")
                assertEquals(IntSize(width, height), IntSize(live.width, live.height))
                val encodes = LongArray(20)
                val decodes = LongArray(20)
                var stored = ByteArray(0)
                var decoded: Bitmap? = null
                for (i in 0 until 20) {
                    var start = SystemClock.elapsedRealtimeNanos()
                    stored = assertNotNull(SnapshotCodec.encodeNow(snapshot))
                    encodes[i] = SystemClock.elapsedRealtimeNanos() - start
                    start = SystemClock.elapsedRealtimeNanos()
                    decoded = assertNotNull(SnapshotCodec.decodeNow(stored)).asAndroidBitmap()
                    decodes[i] = SystemClock.elapsedRealtimeNanos() - start
                }
                // Drawn over the view as the cover is: scaled to it, filtered, if it was taken smaller.
                val cover = overView(assertNotNull(decoded), width, height)
                val drawn = PictureDifference.of(live, overView(snapshot.asAndroidBitmap(), width, height))
                val kept = PictureDifference.of(live, cover)
                Log.i(
                    TAG,
                    "snapshot quality, $what: ${snapshot.width}x${snapshot.height} of ${width}x$height " +
                        "(${"%.3f".format(snapshot.width * density / width)} px a dp), stored ${stored.size} bytes " +
                        "(${snapshot.width.toLong() * snapshot.height * 4} as pixels); the cover against the screen: " +
                        "$kept; the page's own draw against the screen: $drawn; encode p50 ${encodes.p50()} ms, " +
                        "max ${encodes.most()} ms; decode p50 ${decodes.p50()} ms, max ${decodes.most()} ms",
                )
                assertTrue(kept.mean <= 2.0, "$what: the cover against the screen, $kept")
                assertTrue(kept.p99 <= 16, "$what: the cover against the screen, $kept")
            }
        } finally {
            PageDrawBudget.maxPixels = null
            harness.close()
        }
    }

    /** [bitmap] at the view's size, as the cover draws it: as it is if it is that size, else scaled, filtered. */
    private fun overView(bitmap: Bitmap, width: Int, height: Int): Bitmap =
        if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }

    private fun LongArray.p50(): String = "%.1f".format(sorted()[size / 2] / 1e6)

    private fun LongArray.most(): String = "%.1f".format(max() / 1e6)
}
