package dev.supermux.webview.runtime

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Snapshots stored as JPEG 92 (sharp snapshots, Task 1), on every platform the common tests run on. */
class SnapshotCodecTest {
    @Test fun aRoundTripKeepsTheSize() = runTest {
        val bytes = assertNotNull(SnapshotCodec.encode(pageLike(300, 200)))
        val back = assertNotNull(SnapshotCodec.decode(bytes))
        assertEquals(300 to 200, back.width to back.height)
        assertEquals(ImageBitmapConfig.Argb8888, back.config)
    }

    /** A phone-sized page (text, a gradient, flat areas): at most 400 KB of its 4.3 MB of pixels, to the eye the same. */
    @Test fun aPhoneSizedPageIsSmallAndLooksTheSame() = runTest {
        val page = pageLike(1_080, 1_000)
        val bytes = assertNotNull(SnapshotCodec.encode(page))
        val back = assertNotNull(SnapshotCodec.decode(bytes))
        val difference = meanAbsoluteDifference(page, back)
        println("snapshot codec: 1080x1000 page-like image, ${bytes.size} bytes, mean absolute difference $difference")
        assertTrue(bytes.size <= 400 * 1024, "${bytes.size} bytes")
        assertTrue(difference <= 2.0, "mean absolute difference $difference")
    }

    /** A page with a transparent background shows what's under the view through its snapshot too: stored lossless. */
    @Test fun aTransparentSnapshotKeepsItsAlpha() = runTest {
        val image = ImageBitmap(40, 20)
        val canvas = Canvas(image)
        canvas.drawRect(0f, 0f, 40f, 20f, Paint().apply { blendMode = BlendMode.Clear })
        canvas.drawRect(0f, 0f, 20f, 20f, Paint().apply { color = Color(0xFF3366CC) })
        val bytes = assertNotNull(SnapshotCodec.encode(image, opaque = false))
        val back = assertNotNull(SnapshotCodec.decode(bytes)).toPixelMap()
        assertEquals(Color(0xFF3366CC).toArgb(), back[5, 5].toArgb(), "the page, exactly")
        assertEquals(0f, back[30, 5].alpha, "where the page is transparent")
    }

    @Test fun whatCantBeEncodedOrDecodedIsNullNotAThrow() = runTest {
        assertNull(SnapshotCodec.encode(StubImage(0, 0, ImageBitmapConfig.Argb8888)), "a zero-size image")
        assertNull(SnapshotCodec.encode(StubImage(10, 10, ImageBitmapConfig.Argb8888)), "an image with no pixels")
        assertNull(SnapshotCodec.decode(byteArrayOf(1, 2, 3)), "bytes that aren't an image")
    }
}

/**
 * A page-like picture, as snapshots of agent demos are: a light page with a gradient header, lines of dark glyph-like
 * strokes as text, a bar chart and flat colour blocks.
 */
internal fun pageLike(width: Int, height: Int): ImageBitmap {
    val image = ImageBitmap(width, height)
    val canvas = Canvas(image)
    val w = width.toFloat()
    val h = height.toFloat()
    val paint = Paint()
    paint.color = Color(0xFFF6F8FF)
    canvas.drawRect(0f, 0f, w, h, paint)
    paint.shader = LinearGradientShader(Offset(0f, 0f), Offset(w, h * 0.2f), listOf(Color(0xFF4F7CFF), Color(0xFFB04FFF)))
    canvas.drawRect(0f, 0f, w, h * 0.15f, paint)
    paint.shader = null
    // Text: glyph-like outlines, 2 px strokes, in lines of words.
    val glyph = Paint().apply {
        color = Color(0xFF223344)
        style = PaintingStyle.Stroke
        strokeWidth = 2f
    }
    var y = h * 0.2f
    var line = 0
    while (y + 16f < h * 0.7f) {
        var x = 24f
        val end = w - 24f - (line % 3) * w * 0.12f
        var n = 0
        while (x + 12f < end) {
            val gw = 6f + ((n * 7 + line * 13) % 6)
            canvas.drawRect(x, y, x + gw, y + 14f, glyph)
            if (n % 3 == 0) canvas.drawLine(Offset(x, y + 14f), Offset(x + gw, y), glyph)
            x += gw + if (++n % 6 == 0) 10f else 3f
        }
        y += 26f
        line++
    }
    // A bar chart and flat blocks.
    paint.color = Color(0xFF4F7CFF)
    for (i in 0 until 8) {
        val top = h * (0.75f + (i % 4) * 0.04f)
        canvas.drawRect(24f + i * w / 10f, top, 24f + i * w / 10f + w / 14f, h * 0.95f, paint)
    }
    paint.color = Color(0xFFE8EEFC)
    canvas.drawRect(w * 0.85f, h * 0.75f, w, h, paint)
    return image
}

/** The mean absolute difference between [a] and [b], over every red, green and blue value of every pixel, 0 to 255. */
internal fun meanAbsoluteDifference(a: ImageBitmap, b: ImageBitmap): Double {
    val pa = a.toPixelMap()
    val pb = b.toPixelMap()
    var sum = 0L
    for (y in 0 until a.height) {
        for (x in 0 until a.width) {
            val ca = pa[x, y].toArgb()
            val cb = pb[x, y].toArgb()
            sum += abs((ca shr 16 and 0xFF) - (cb shr 16 and 0xFF)) + abs((ca shr 8 and 0xFF) - (cb shr 8 and 0xFF)) +
                abs((ca and 0xFF) - (cb and 0xFF))
        }
    }
    return sum.toDouble() / (3L * a.width * a.height)
}
