package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class SnapshotCacheTest {
    private fun key(i: Int) = SnapshotKey("c$i", 10, 10, dark = false)
    private fun image() = ImageBitmap(10, 10) // 400 bytes

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
        cache.put(SnapshotKey("c", 10, 10, false), image())
        assertNull(cache[SnapshotKey("c", 10, 10, true)])
        assertNull(cache[SnapshotKey("c", 20, 10, false)])
    }
}
