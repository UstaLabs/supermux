package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntSize
import dev.supermux.webview.WebProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Compact snapshots (keep pages alive, Task 5): a picture of every page a chat showed, within the cache's budget. */
class SnapshotScalingTest {
    private fun solid(width: Int, height: Int, color: Color): ImageBitmap {
        val image = ImageBitmap(width, height)
        Canvas(image).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.color = color })
        return image
    }

    @Test fun aSnapshotIsKeptAtOnePixelPerDpWithinThePixelCapAndItsAspectRatio() {
        // A phone's embed, 411 × 300 dp at density 2.625: taken at full density, or at 2 as Android takes it.
        assertEquals(IntSize(411, 300), compactSize(1_079, 788, viewWidthDp = 411f))
        assertEquals(IntSize(411, 300), compactSize(822, 600, viewWidthDp = 411f))
        // A desktop pane, 1,200 × 800 dp at density 1: within the cap, as wide for its height as it was.
        val pane = compactSize(1_200, 800, viewWidthDp = 1_200f)
        assertTrue(pane.width.toLong() * pane.height <= MAX_SNAPSHOT_PIXELS, "$pane")
        assertTrue(pane.width >= 730, "$pane")
        assertEquals(1.5f, pane.width.toFloat() / pane.height, 0.01f)
    }

    @Test fun aSnapshotIsNeverScaledUp() {
        assertEquals(IntSize(200, 100), compactSize(200, 100, viewWidthDp = 400f))
        assertEquals(IntSize(500, 700), compactSize(500, 700, viewWidthDp = 0f))
        assertEquals(IntSize(0, 0), compactSize(0, 0, viewWidthDp = 100f))
    }

    @Test fun anOpaquePageIsKeptIn16BitPixelsAndATransparentOneIn32() {
        val image = solid(800, 600, Color.Red)
        val opaque = compactSnapshot(image, viewWidthDp = 400f, opaque = true)
        assertEquals(IntSize(400, 300), IntSize(opaque.width, opaque.height))
        assertEquals(ImageBitmapConfig.Rgb565, opaque.config)
        assertEquals(Color.Red, opaque.toPixelMap()[200, 150])
        val transparent = compactSnapshot(image, viewWidthDp = 400f, opaque = false)
        assertEquals(IntSize(400, 300), IntSize(transparent.width, transparent.height))
        assertEquals(ImageBitmapConfig.Argb8888, transparent.config)
        assertEquals(Color.Red, transparent.toPixelMap()[200, 150])
    }

    @Test fun theCacheCountsACompactSnapshotAtWhatItCosts() {
        val image = compactSnapshot(solid(1_079, 788, Color.Blue), viewWidthDp = 411f, opaque = true)
        assertEquals(411L * 300 * 2, pixelBytes(image))
        val cache = SnapshotCache(32L shl 20)
        cache.put(SnapshotKey("k", WebProfile.Default, image.width, image.height, false, 2.625f), image)
        assertEquals(pixelBytes(image), cache.sizeBytes)
        // 32 MB holds a hundred such pictures, where it held nine at full resolution.
        assertTrue((32L shl 20) / pixelBytes(image) >= 100)
    }

    @Test fun aSnapshotWithNothingToGainIsKeptAsItIs() {
        val small = solid(100, 50, Color.Red)
        assertSame(small, compactSnapshot(small, viewWidthDp = 100f, opaque = false))
    }
}
