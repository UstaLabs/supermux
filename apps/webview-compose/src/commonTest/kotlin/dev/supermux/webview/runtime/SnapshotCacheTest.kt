package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class SnapshotCacheTest {
    private fun key(i: Int) = SnapshotKey("c$i", 10, 10, dark = false, density = 2f)
    private fun image(config: ImageBitmapConfig = ImageBitmapConfig.Argb8888) = StubImage(10, 10, config) // 400 bytes as Argb8888

    @Test fun storesAndReturnsSnapshots() {
        val cache = SnapshotCache(10_000)
        val image = image()
        cache.put(key(1), image)
        assertSame(image, cache[key(1)])
    }

    @Test fun evictsTheLeastRecentlyUsedFirst() {
        val cache = SnapshotCache(1_200)
        cache.put(key(1), image())
        cache.put(key(2), image())
        cache.put(key(3), image())
        cache[key(1)]
        cache.put(key(4), image())
        assertNull(cache[key(2)])
        assertNotNull(cache[key(1)])
        assertNotNull(cache[key(3)])
        assertNotNull(cache[key(4)])
        assertEquals(1_200, cache.sizeBytes)
    }

    @Test fun replacingAKeyDoesNotDoubleCount() {
        val cache = SnapshotCache(10_000)
        cache.put(key(1), image())
        cache.put(key(1), image())
        assertEquals(400, cache.sizeBytes)
        assertEquals(1, cache.count)
    }

    @Test fun aSnapshotLargerThanTheWholeCacheIsNotKept() {
        val cache = SnapshotCache(100)
        cache.put(key(1), image())
        assertNull(cache[key(1)])
        assertEquals(0, cache.sizeBytes)
    }

    @Test fun trimToFreesDownToTheLimit() {
        val cache = SnapshotCache(10_000)
        repeat(5) { cache.put(key(it), image()) }
        cache.trimTo(800)
        assertEquals(800, cache.sizeBytes)
        assertNull(cache[key(0)])
        assertNotNull(cache[key(4)])
    }

    @Test fun keysIncludeSizeAndTheme() {
        val cache = SnapshotCache(10_000)
        cache.put(SnapshotKey("c", 10, 10, false, 2f), image())
        assertNull(cache[SnapshotKey("c", 10, 10, true, 2f)])
        assertNull(cache[SnapshotKey("c", 20, 10, false, 2f)])
    }

    @Test fun f16ImagesCostEightBytesAPixel() {
        val cache = SnapshotCache(10_000)
        cache.put(key(1), image(ImageBitmapConfig.F16))
        assertEquals(800, cache.sizeBytes)
    }

    @Test fun everyPixelFormatCostsItsOwnBytes() {
        for ((config, bytes) in listOf(
            ImageBitmapConfig.Alpha8 to 100L,
            ImageBitmapConfig.Rgb565 to 200L,
            ImageBitmapConfig.Argb8888 to 400L,
            ImageBitmapConfig.Gpu to 400L,
        )) {
            val cache = SnapshotCache(10_000)
            cache.put(key(1), image(config))
            assertEquals(bytes, cache.sizeBytes, "$config")
        }
    }

    @Test fun anOversizedSnapshotLeavesTheRestAlone() {
        val cache = SnapshotCache(1_000)
        cache.put(key(1), image())
        cache.put(key(2), image())
        cache.put(key(3), StubImage(30, 30, ImageBitmapConfig.Argb8888)) // 3,600 bytes
        assertNull(cache[key(3)])
        assertNotNull(cache[key(1)])
        assertNotNull(cache[key(2)])
        assertEquals(800, cache.sizeBytes)
    }

    @Test fun anImageThatChangesFormatAfterwardsStillLeavesExactly() {
        // Android can reconfigure a bitmap in place. However the image leaves, the cache gives back what it took.
        fun cacheWithAChangedImage(): SnapshotCache {
            val cache = SnapshotCache(1_000)
            val image = image(ImageBitmapConfig.F16) // 800 bytes
            cache.put(key(1), image)
            image.config = ImageBitmapConfig.Alpha8 // now it would count 100
            return cache
        }
        val removed = cacheWithAChangedImage()
        removed.remove(key(1))
        assertEquals(0, removed.sizeBytes)
        val trimmed = cacheWithAChangedImage()
        trimmed.trimTo(0)
        assertEquals(0, trimmed.sizeBytes)
        val evicted = cacheWithAChangedImage()
        evicted.put(key(2), image()) // 1,200 bytes: the changed image goes
        assertNull(evicted[key(1)])
        assertNotNull(evicted[key(2)])
        assertEquals(400, evicted.sizeBytes)
    }

    @Test fun densityIsPartOfTheKey() {
        val cache = SnapshotCache(10_000)
        val image = image()
        cache.put(SnapshotKey("c", 10, 10, false, density = 2f), image)
        assertNull(cache[SnapshotKey("c", 10, 10, false, density = 3f)])
        assertSame(image, cache[SnapshotKey("c", 10, 10, false, density = 2f)])
    }
}

/** A [width]×[height] bitmap with no pixels: the cache only reads its size and [config]. Needs no Skia, so it runs anywhere. */
internal class StubImage(
    override val width: Int,
    override val height: Int,
    override var config: ImageBitmapConfig,
) : ImageBitmap {
    override val colorSpace: ColorSpace get() = ColorSpaces.Srgb
    override val hasAlpha: Boolean get() = true

    override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int): Unit =
        error("unused")

    override fun prepareToDraw(): Unit = error("unused")
}
