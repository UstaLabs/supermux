package dev.supermux.webview.engine.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.WebContent
import dev.supermux.webview.runtime.SnapshotCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sharp snapshots, the quality bar (decision 5), on the phone. A cover as the runtime keeps and draws it (the page's
 * own draw at the screen's density, stored as a JPEG at quality 92, decoded) against the live page as the screen shows
 * it at the same moment (a copy of the window), on a still page, text included: the mean absolute channel difference
 * is at most 2, and the 99th percentile at most 16. The sizes, and the encode and decode times over 20, go to logcat.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotQualityTest {
    @Test fun aCoverOfAnAgentDemoLooksLikeThePage() = coverLooksLikeThePage(TestPages.demo, "an agent demo")

    @Test fun aCoverOfAnArticleLooksLikeThePage() = coverLooksLikeThePage(TestPages.article, "an article")

    private fun coverLooksLikeThePage(page: WebContent, what: String) {
        PageDrawScale.limit = null
        val harness = EngineHarness.open()
        try {
            runBlocking {
                harness.loadAndPaint(page)
                // A still page: painted, laid out, its fonts in.
                delay(1_000)
                val (width, height) = harness.main { webView.width to webView.height }
                val density = harness.activity.resources.displayMetrics.density
                val snapshot = assertNotNull(harness.main { snapshot() }, "the page's own draw")
                val size = IntSize(snapshot.width, snapshot.height)
                // The live page, as the screen shows it, at the snapshot's size.
                val live = assertNotNull(harness.main { copyPixels(webView, size) }, "a copy of the window")
                assertEquals(size, IntSize(live.width, live.height))
                val encodes = LongArray(20)
                val decodes = LongArray(20)
                var stored = ByteArray(0)
                var cover: Bitmap? = null
                for (i in 0 until 20) {
                    var start = SystemClock.elapsedRealtimeNanos()
                    stored = assertNotNull(SnapshotCodec.encodeNow(snapshot))
                    encodes[i] = SystemClock.elapsedRealtimeNanos() - start
                    start = SystemClock.elapsedRealtimeNanos()
                    cover = assertNotNull(SnapshotCodec.decodeNow(stored)).asAndroidBitmap()
                    decodes[i] = SystemClock.elapsedRealtimeNanos() - start
                }
                val drawn = Difference.of(live, snapshot.asAndroidBitmap())
                val kept = Difference.of(live, assertNotNull(cover))
                Log.i(
                    TAG,
                    "snapshot quality, $what: ${size.width}x${size.height} of ${width}x$height " +
                        "(${"%.3f".format(size.width * density / width)} px a dp), stored ${stored.size} bytes " +
                        "(${size.width.toLong() * size.height * 4} as pixels); the cover against the screen: $kept; " +
                        "the page's own draw against the screen: $drawn; " +
                        "encode p50 ${encodes.p50()} ms, max ${encodes.most()} ms; decode p50 ${decodes.p50()} ms, " +
                        "max ${decodes.most()} ms",
                )
                assertTrue(kept.mean <= 2.0, "$what: the cover against the screen, $kept")
                assertTrue(kept.p99 <= 16, "$what: the cover against the screen, $kept")
            }
        } finally {
            PageDrawScale.limit = null
            harness.close()
        }
    }

    private fun LongArray.p50(): String = "%.1f".format(sorted()[size / 2] / 1e6)

    private fun LongArray.most(): String = "%.1f".format(max() / 1e6)

    /** How far two pictures of the same size are apart, over every red, green and blue value of every pixel. */
    private class Difference(val mean: Double, val p99: Int, val most: Int) {
        override fun toString(): String = "mean ${"%.2f".format(mean)}, p99 $p99, max $most"

        companion object {
            fun of(a: Bitmap, b: Bitmap): Difference {
                val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
                val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
                val counts = LongArray(256)
                var sum = 0L
                for (i in pa.indices) {
                    for (shift in intArrayOf(16, 8, 0)) {
                        val d = abs((pa[i] shr shift and 0xFF) - (pb[i] shr shift and 0xFF))
                        counts[d]++
                        sum += d
                    }
                }
                val total = 3L * pa.size
                var seen = 0L
                var p99 = 255
                for (d in 0..255) {
                    seen += counts[d]
                    if (seen >= total * 0.99) {
                        p99 = d
                        break
                    }
                }
                val most = (255 downTo 0).first { counts[it] > 0 || it == 0 }
                return Difference(sum.toDouble() / total, p99, most)
            }
        }
    }
}
