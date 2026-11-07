package dev.supermux.webview.runtime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Snapshots stored as JPEG 92 on the phone (sharp snapshots, Task 1): `Bitmap.compress` and `BitmapFactory`. */
@RunWith(AndroidJUnit4::class)
class SnapshotCodecAndroidTest {
    @Test fun aRoundTripKeepsTheSize() = runBlocking {
        val bytes = assertNotNull(SnapshotCodec.encode(pageLike(300, 200).asImageBitmap()))
        val back = assertNotNull(SnapshotCodec.decode(bytes))
        assertEquals(300 to 200, back.width to back.height)
        assertEquals(ImageBitmapConfig.Argb8888, back.config)
        assertEquals(Bitmap.Config.ARGB_8888, back.asAndroidBitmap().config)
    }

    /**
     * A phone-sized page with real anti-aliased text, a gradient and flat areas: at most 400 KB, to the eye the same,
     * and quick. Logs the size and the encode and decode times over 20 round trips.
     */
    @Test fun aPhoneSizedPageIsSmallAndLooksTheSame() = runBlocking {
        val page = pageLike(1_080, 1_000)
        val image = page.asImageBitmap()
        val encodes = LongArray(20)
        val decodes = LongArray(20)
        var bytes = ByteArray(0)
        var back: ImageBitmap? = null
        for (i in 0 until 20) {
            var start = SystemClock.elapsedRealtimeNanos()
            bytes = assertNotNull(SnapshotCodec.encode(image))
            encodes[i] = SystemClock.elapsedRealtimeNanos() - start
            start = SystemClock.elapsedRealtimeNanos()
            back = assertNotNull(SnapshotCodec.decode(bytes))
            decodes[i] = SystemClock.elapsedRealtimeNanos() - start
        }
        val difference = meanAbsoluteDifference(page, assertNotNull(back).asAndroidBitmap())
        Log.i(
            "WebViewEngine",
            "snapshot codec on the phone, a 1080x1000 page-like image: ${bytes.size} bytes, mean absolute difference " +
                "${"%.2f".format(difference)}; encode p50 ${encodes.p50()} ms, max ${encodes.max()} ms; " +
                "decode p50 ${decodes.p50()} ms, max ${decodes.max()} ms",
        )
        assertTrue(bytes.size <= 400 * 1024, "${bytes.size} bytes")
        assertTrue(difference <= 2.0, "mean absolute difference $difference")
    }

    /**
     * Called on the main thread, both ways suspend off it: a message posted to the main thread just before runs while
     * the image is encoded, and again while it's decoded.
     */
    @Test fun neitherWayHoldsTheMainThread() = runBlocking {
        val image = pageLike(1_080, 1_000).asImageBitmap()
        val main = Handler(Looper.getMainLooper())
        withContext(Dispatchers.Main) {
            var encoding = true
            var ranWhileEncoding = false
            main.post { ranWhileEncoding = encoding }
            val bytes = assertNotNull(SnapshotCodec.encode(image))
            encoding = false
            var decoding = true
            var ranWhileDecoding = false
            main.post { ranWhileDecoding = decoding }
            assertNotNull(SnapshotCodec.decode(bytes))
            decoding = false
            assertTrue(ranWhileEncoding, "the main thread was free while the image was encoded")
            assertTrue(ranWhileDecoding, "the main thread was free while the image was decoded")
        }
    }

    /** A page with a transparent background shows what's under the view through its snapshot too: stored lossless. */
    @Test fun aTransparentSnapshotKeepsItsAlpha() = runBlocking {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(0f, 0f, 20f, 20f, Paint().apply { color = 0xFF3366CC.toInt() })
        val bytes = assertNotNull(SnapshotCodec.encode(bitmap.asImageBitmap(), opaque = false))
        val back = assertNotNull(SnapshotCodec.decode(bytes)).asAndroidBitmap()
        assertEquals(0xFF3366CC.toInt(), back.getPixel(5, 5), "the page, exactly")
        assertEquals(0, back.getPixel(30, 5) ushr 24, "where the page is transparent")
    }

    @Test fun whatCantBeEncodedOrDecodedIsNullNotAThrow() = runBlocking {
        assertNull(SnapshotCodec.encode(NoPixels(0, 0)), "a zero-size image")
        assertNull(SnapshotCodec.encode(NoPixels(10, 10)), "an image with no pixels behind it")
        assertNull(SnapshotCodec.decode(byteArrayOf(1, 2, 3)), "bytes that aren't an image")
        assertNull(SnapshotCodec.decode(ByteArray(0)), "no bytes")
    }

    private fun LongArray.p50(): Long = sorted()[size / 2] / 1_000_000

    private fun LongArray.max(): Long = maxOf { it } / 1_000_000

    /** An image the codec can't read: the zero-size one, or one with no Android bitmap behind it. */
    private class NoPixels(override val width: Int, override val height: Int) : ImageBitmap {
        override val config: ImageBitmapConfig get() = ImageBitmapConfig.Argb8888
        override val colorSpace: ColorSpace get() = ColorSpaces.Srgb
        override val hasAlpha: Boolean get() = false

        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int): Unit =
            error("no pixels")

        override fun prepareToDraw(): Unit = error("no pixels")
    }

    private companion object {
        /** A page as agent demos are: a gradient header, paragraphs of real anti-aliased text, a bar chart, flat blocks. */
        fun pageLike(width: Int, height: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(0xFFF6F8FF.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, w, h * 0.2f, 0xFF4F7CFF.toInt(), 0xFFB04FFF.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h * 0.15f, paint)
            paint.shader = null
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = h * 0.05f
            paint.isFakeBoldText = true
            canvas.drawText("Quarterly numbers", 24f, h * 0.1f, paint)
            paint.isFakeBoldText = false
            paint.color = 0xFF223344.toInt()
            paint.textSize = 40f
            val words = "the quick brown fox jumps over a lazy dog while agents render pages of charts and tables".split(' ')
            var y = h * 0.2f + 40f
            var line = 0
            while (y < h * 0.7f) {
                val text = StringBuilder()
                var i = line * 3
                while (paint.measureText(text.toString()) < w - 48f - (line % 3) * w * 0.12f) {
                    text.append(words[i++ % words.size]).append(' ')
                }
                canvas.drawText(text.toString(), 24f, y, paint)
                y += 52f
                line++
            }
            paint.color = 0xFF4F7CFF.toInt()
            for (i in 0 until 8) {
                val top = h * (0.75f + (i % 4) * 0.04f)
                canvas.drawRect(24f + i * w / 10f, top, 24f + i * w / 10f + w / 14f, h * 0.95f, paint)
            }
            paint.color = 0xFFE8EEFC.toInt()
            canvas.drawRect(w * 0.85f, h * 0.75f, w, h, paint)
            return bitmap
        }

        /** The mean absolute difference over every red, green and blue value of every pixel, 0 to 255. */
        fun meanAbsoluteDifference(a: Bitmap, b: Bitmap): Double {
            val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
            val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            var sum = 0L
            for (i in pa.indices) {
                val ca = pa[i]
                val cb = pb[i]
                sum += abs((ca shr 16 and 0xFF) - (cb shr 16 and 0xFF)) + abs((ca shr 8 and 0xFF) - (cb shr 8 and 0xFF)) +
                    abs((ca and 0xFF) - (cb and 0xFF))
            }
            return sum.toDouble() / (3L * pa.size)
        }
    }
}
