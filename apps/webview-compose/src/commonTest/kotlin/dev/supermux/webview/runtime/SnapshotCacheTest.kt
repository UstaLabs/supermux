package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import dev.supermux.webview.WebProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Cache v3 (sharp snapshots, Task 2): snapshots stored encoded, within a budget of stored bytes, next to a
 * least-recently-used set of decoded ones, within a budget of pixel bytes, that a request fills on demand.
 */
class SnapshotCacheTest {
    private fun key(i: Int) = SnapshotKey("c$i", WebProfile.Default, 10, 10, dark = false, density = 2f)

    /** 400 stored bytes of a 10×10 snapshot, whose pixels are 400 bytes too. */
    private fun bytes(size: Int = 400) = FakeCodec.stored(size)

    private fun image(config: ImageBitmapConfig = ImageBitmapConfig.Argb8888) = StubImage(10, 10, config) // 400 bytes as Argb8888

    private fun TestScope.cache(maxBytes: Long, decodedMaxBytes: Long = 10_000, codec: ImageCodec = FakeCodec()) =
        SnapshotCache(maxBytes, decodedMaxBytes, backgroundScope, codec)

    /** Records what a view waiting for a decode is given. */
    private class Waiter {
        val got = mutableListOf<Pair<SnapshotKey, ImageBitmap>>()
        val call: (SnapshotKey, ImageBitmap) -> Unit = { key, image -> got += key to image }
    }

    // --- Stored snapshots: the budget in stored bytes ---

    @Test fun storesSnapshotsEncoded() = runTest {
        val cache = cache(10_000)
        val stored = bytes()
        cache.put(key(1), stored)
        assertTrue(key(1) in cache)
        assertSame(stored, cache[key(1)])
        assertEquals(400, cache.sizeBytes)
    }

    @Test fun theBudgetCountsStoredBytesNotPixels() = runTest {
        val cache = cache(10_000)
        // A 1,000 × 1,000 page is 4 MB of pixels; stored, it is what its bytes are.
        cache.put(SnapshotKey("c", WebProfile.Default, 1_000, 1_000, false, 2f), FakeCodec.stored(2_000, 1_000, 1_000))
        assertEquals(2_000, cache.sizeBytes)
        assertEquals(1, cache.count)
    }

    @Test fun evictsTheLeastRecentlyUsedFirst() = runTest {
        val cache = cache(1_200)
        cache.put(key(1), bytes())
        cache.put(key(2), bytes())
        cache.put(key(3), bytes())
        cache[key(1)]
        cache.put(key(4), bytes())
        assertNull(cache[key(2)])
        assertNotNull(cache[key(1)])
        assertNotNull(cache[key(3)])
        assertNotNull(cache[key(4)])
        assertEquals(1_200, cache.sizeBytes)
    }

    @Test fun aRequestCountsAsAUse() = runTest {
        val cache = cache(1_200)
        cache.put(key(1), bytes(), image())
        cache.put(key(2), bytes(), image())
        cache.put(key(3), bytes(), image())
        cache.request(key(1))
        cache.put(key(4), bytes())
        assertTrue(key(1) in cache)
        assertTrue(key(2) !in cache)
    }

    @Test fun replacingAKeyDoesNotDoubleCount() = runTest {
        val cache = cache(10_000)
        cache.put(key(1), bytes(), image())
        cache.put(key(1), bytes(), image())
        assertEquals(400, cache.sizeBytes)
        assertEquals(1, cache.count)
        assertEquals(400, cache.decodedBytes)
        assertEquals(1, cache.decodedCount)
    }

    @Test fun aSnapshotLargerThanTheWholeCacheIsNotKept() = runTest {
        val cache = cache(100)
        cache.put(key(1), bytes(), image())
        assertNull(cache[key(1)])
        assertNull(cache.request(key(1)), "nor its decoded copy")
        assertEquals(0, cache.sizeBytes)
        assertEquals(0, cache.decodedBytes)
    }

    /** A snapshot that doesn't fit isn't stored, says so, and leaves what was stored under its key where it was. */
    @Test fun aSnapshotThatDoesntFitSaysSoAndKeepsWhatWasThere() = runTest {
        val cache = cache(1_000)
        assertTrue(cache.put(key(1), bytes(), image()))
        assertFalse(cache.put(key(1), bytes(3_600)))
        assertNotNull(cache[key(1)], "the snapshot it was to replace is gone")
        assertEquals(400, cache.sizeBytes)
    }

    /** Warnings that keep coming in one memory-pressure window don't keep halving what it stores. */
    @Test fun repeatedWarningsDontKeepHalvingTheStored() = runTest {
        val cache = cache(8_000)
        repeat(8) { cache.put(key(it), bytes()) }
        cache.onMemoryPressure()
        assertEquals(1_600, cache.sizeBytes)
        cache.onMemoryPressure()
        cache.onMemoryPressure()
        assertEquals(1_600, cache.sizeBytes, "halved again")
    }

    @Test fun trimToFreesDownToTheLimit() = runTest {
        val cache = cache(10_000)
        repeat(5) { cache.put(key(it), bytes(), image()) }
        cache.trimTo(800)
        assertEquals(800, cache.sizeBytes)
        assertNull(cache[key(0)])
        assertNotNull(cache[key(4)])
        assertNull(cache.request(key(0)), "what's no longer stored isn't drawn either")
        assertEquals(800, cache.decodedBytes)
    }

    @Test fun keysIncludeSizeAndTheme() = runTest {
        val cache = cache(10_000)
        cache.put(SnapshotKey("c", WebProfile.Default, 10, 10, false, 2f), bytes())
        assertNull(cache[SnapshotKey("c", WebProfile.Default, 10, 10, true, 2f)])
        assertNull(cache[SnapshotKey("c", WebProfile.Default, 20, 10, false, 2f)])
    }

    @Test fun anOversizedSnapshotLeavesTheRestAlone() = runTest {
        val cache = cache(1_000)
        cache.put(key(1), bytes())
        cache.put(key(2), bytes())
        cache.put(key(3), bytes(3_600))
        assertNull(cache[key(3)])
        assertNotNull(cache[key(1)])
        assertNotNull(cache[key(2)])
        assertEquals(800, cache.sizeBytes)
    }

    /** Task 16b: profiles are a boundary. The same content shown in another profile is another user's page. */
    @Test fun theProfileIsPartOfTheKey() = runTest {
        val cache = cache(10_000)
        cache.put(SnapshotKey("c", WebProfile.Named("alice"), 10, 10, false, 2f), bytes())
        assertNull(cache[SnapshotKey("c", WebProfile.Named("bob"), 10, 10, false, 2f)])
        assertNull(cache[SnapshotKey("c", WebProfile.Default, 10, 10, false, 2f)])
        assertNotNull(cache[SnapshotKey("c", WebProfile.Named("alice"), 10, 10, false, 2f)])
    }

    @Test fun forgettingAContentRemovesItsSnapshotsInEveryProfileSizeThemeAndDensity() = runTest {
        val cache = cache(10_000)
        val gone = listOf(
            SnapshotKey("c", WebProfile.Named("alice"), 10, 10, false, 2f),
            SnapshotKey("c", WebProfile.Named("bob"), 10, 10, true, 2f),
            SnapshotKey("c", WebProfile.Default, 20, 10, false, 1f),
        )
        gone.forEach { cache.put(it, bytes(), image()) }
        cache.put(key(1), bytes(), image())
        cache.forget("c")
        gone.forEach {
            assertNull(cache[it])
            assertNull(cache.request(it))
        }
        assertNotNull(cache[key(1)])
        assertEquals(1, cache.count)
        assertEquals(400, cache.sizeBytes)
        assertEquals(400, cache.decodedBytes)
    }

    @Test fun clearingEmptiesIt() = runTest {
        val codec = FakeCodec()
        val cache = cache(10_000, codec = codec)
        repeat(3) { cache.put(key(it), bytes(), image()) }
        cache.put(key(3), bytes())
        codec.gate = CompletableDeferred()
        val waiter = Waiter()
        cache.request(key(3), waiter.call)
        cache.clear()
        codec.gate?.complete(Unit)
        runCurrent()
        assertEquals(0, cache.count)
        assertEquals(0, cache.sizeBytes)
        assertEquals(0, cache.decodedCount)
        assertEquals(0, cache.decodedBytes)
        assertNull(cache[key(0)])
        assertEquals(emptyList(), waiter.got, "a decode under way when it was cleared gives nothing")
        cache.put(key(0), bytes())
        assertEquals(400, cache.sizeBytes)
    }

    @Test fun densityIsPartOfTheKey() = runTest {
        val cache = cache(10_000)
        val stored = bytes()
        cache.put(SnapshotKey("c", WebProfile.Default, 10, 10, false, density = 2f), stored)
        assertNull(cache[SnapshotKey("c", WebProfile.Default, 10, 10, false, density = 3f)])
        assertSame(stored, cache[SnapshotKey("c", WebProfile.Default, 10, 10, false, density = 2f)])
    }

    /** A lower limit, as memory pressure sets: the cache keeps within it, as within its cap, until it is lifted. */
    @Test fun aLimitHoldsUntilItIsLifted() = runTest {
        val cache = cache(4_000)
        repeat(10) { cache.put(key(it), bytes()) }
        cache.limitBytes = 2_000
        assertEquals(2_000, cache.sizeBytes, "it evicts down to the limit at once")
        repeat(5) { cache.put(key(10 + it), bytes()) }
        assertEquals(2_000, cache.sizeBytes)
        assertNotNull(cache[key(14)], "the newest are kept")
        assertNull(cache[key(9)])
        cache.put(key(20), bytes(3_600)) // within the cap, over the limit
        assertNull(cache[key(20)])
        assertEquals(2_000, cache.sizeBytes)
        cache.limitBytes = null
        repeat(5) { cache.put(key(30 + it), bytes()) }
        assertEquals(4_000, cache.sizeBytes, "the whole budget is back")
    }

    @Test fun aLimitNeverRaisesTheCap() = runTest {
        val cache = cache(1_000)
        cache.limitBytes = 10_000
        repeat(5) { cache.put(key(it), bytes()) }
        assertEquals(800, cache.sizeBytes)
    }

    /**
     * Sharp covers for hundreds of pages: a phone-sized cover (a chat's 360 × 280 dp embed at a density of 2.625, a
     * 945 × 735 page of text, a gradient and flat areas) stored as the runtime stores it, at least 150 to the 32 MB
     * phones keep. Its pixels are 2.8 MB: as pixels, 32 MB would hold 11.
     */
    @Test fun thirtyTwoMegabytesHoldAtLeast150PhoneSizedCovers() = runTest {
        assumeRealImages()
        val stored = assertNotNull(SnapshotCodec.encode(pageLike(945, 735)))
        val cache = cache(32L shl 20)
        repeat(200) { cache.put(SnapshotKey("page$it", WebProfile.Default, 945, 735, false, 2.625f), stored) }
        println("snapshot cache: a phone-sized cover is ${stored.size} bytes stored; 32 MB keep ${cache.count} of 200")
        assertTrue(cache.count >= 150, "32 MB keep ${cache.count} covers of ${stored.size} bytes")
    }

    // --- Decoded snapshots: ready to draw, on demand ---

    @Test fun aSnapshotPutWithItsImageIsReadyAtOnce() = runTest {
        val codec = FakeCodec()
        val cache = cache(10_000, codec = codec)
        val image = image()
        cache.put(key(1), bytes(), image)
        assertSame(image, cache.request(key(1)))
        assertEquals(0, codec.decodes)
        assertEquals(400, cache.decodedBytes)
    }

    @Test fun aRequestDecodesWhatIsntReadyAndTellsTheWaiters() = runTest {
        val codec = FakeCodec().apply { gate = CompletableDeferred() }
        val cache = cache(10_000, codec = codec)
        cache.put(key(1), bytes())
        val waiter = Waiter()
        assertNull(cache.request(key(1), waiter.call), "not ready: it decodes")
        assertTrue(cache.isDecoding(key(1)))
        codec.gate?.complete(Unit)
        runCurrent()
        val (key, image) = waiter.got.single()
        assertEquals(key(1), key)
        assertEquals(10 to 10, image.width to image.height)
        assertSame(image, cache.request(key(1)), "ready from then on")
        assertEquals(1, codec.decodes)
    }

    @Test fun aSnapshotIsDecodedOnceForEveryoneWhoAsks() = runTest {
        val codec = FakeCodec().apply { gate = CompletableDeferred() }
        val cache = cache(10_000, codec = codec)
        cache.put(key(1), bytes())
        val first = Waiter()
        val second = Waiter()
        assertNull(cache.request(key(1), first.call))
        assertNull(cache.request(key(1), second.call))
        assertNull(cache.request(key(1), first.call), "the same waiter asking again")
        assertNull(cache.request(key(1)), "a prefetch")
        codec.gate?.complete(Unit)
        runCurrent()
        assertEquals(1, codec.decodes)
        assertEquals(1, first.got.size)
        assertEquals(1, second.got.size)
        assertSame(first.got.single().second, second.got.single().second)
    }

    /**
     * A view that no longer wants a cover (it left, or scrolled out) withdraws from its decode: one nobody waits for any
     * more is given up, and keeps nothing decoded, so the covers about to be seen stay.
     */
    @Test fun aDecodeNobodyWaitsForAnyMoreIsGivenUp() = runTest {
        val codec = FakeCodec().apply { gate = CompletableDeferred() }
        val cache = cache(10_000, codec = codec)
        cache.put(key(1), bytes())
        val waiter = Waiter()
        cache.request(key(1), waiter.call)
        cache.request(key(1))
        cache.withdraw(key(1), waiter.call)
        assertTrue(cache.isDecoding(key(1)), "a prefetch still wants it")
        cache.withdraw(key(1), null)
        assertTrue(!cache.isDecoding(key(1)), "given up")
        codec.gate?.complete(Unit)
        runCurrent()
        assertEquals(emptyList(), waiter.got)
        assertEquals(0, cache.decodedCount, "a decode nobody wanted filled the decoded cache")
        assertTrue(key(1) in cache, "still stored")
    }

    /** One that another view still waits for goes on, for it. */
    @Test fun aDecodeAnotherViewStillWaitsForGoesOn() = runTest {
        val codec = FakeCodec().apply { gate = CompletableDeferred() }
        val cache = cache(10_000, codec = codec)
        cache.put(key(1), bytes())
        val leaving = Waiter()
        val staying = Waiter()
        cache.request(key(1), leaving.call)
        cache.request(key(1), staying.call)
        cache.withdraw(key(1), leaving.call)
        codec.gate?.complete(Unit)
        runCurrent()
        assertEquals(emptyList(), leaving.got)
        assertEquals(1, staying.got.size)
        assertEquals(1, cache.decodedCount)
    }

    @Test fun decodedSnapshotsAreKeptLeastRecentlyUsedFirst() = runTest {
        val codec = FakeCodec()
        val cache = cache(10_000, decodedMaxBytes = 1_200, codec = codec)
        val images = (1..4).associateWith { image() }
        cache.put(key(1), bytes(), images[1])
        cache.put(key(2), bytes(), images[2])
        cache.put(key(3), bytes(), images[3])
        cache.request(key(1))
        cache.put(key(4), bytes(), images[4])
        assertEquals(1_200, cache.decodedBytes)
        assertEquals(3, cache.decodedCount)
        assertSame(images[1], cache.request(key(1)))
        assertSame(images[3], cache.request(key(3)))
        assertSame(images[4], cache.request(key(4)))
        assertEquals(0, codec.decodes)
        assertEquals(4, cache.count, "every snapshot is still stored")
        codec.gate = CompletableDeferred()
        assertNull(cache.request(key(2)), "decoded again, on demand")
        assertEquals(1, codec.decodes)
    }

    @Test fun aSnapshotWhoseBytesDontDecodeIsForgotten() = runTest {
        val codec = FakeCodec().apply { fails = true }
        val cache = cache(10_000, codec = codec)
        cache.put(key(1), bytes())
        val waiter = Waiter()
        assertNull(cache.request(key(1), waiter.call))
        runCurrent()
        assertTrue(key(1) !in cache)
        assertEquals(0, cache.sizeBytes)
        assertEquals(emptyList(), waiter.got)
    }

    @Test fun aSnapshotRemovedWhileItDecodesGivesNothing() = runTest {
        val codec = FakeCodec().apply { gate = CompletableDeferred() }
        val cache = cache(10_000, codec = codec)
        cache.put(key(1), bytes())
        val waiter = Waiter()
        cache.request(key(1), waiter.call)
        cache.remove(key(1))
        codec.gate?.complete(Unit)
        runCurrent()
        assertEquals(emptyList(), waiter.got)
        assertEquals(0, cache.decodedCount)
        assertTrue(!cache.isDecoding(key(1)))
    }

    @Test fun aSnapshotReplacedWhileItDecodesGivesTheWaitersTheNewOne() = runTest {
        val codec = FakeCodec().apply { gate = CompletableDeferred() }
        val cache = cache(10_000, codec = codec)
        cache.put(key(1), bytes())
        val waiter = Waiter()
        cache.request(key(1), waiter.call)
        val newer = image()
        cache.put(key(1), bytes(), newer)
        assertSame(newer, waiter.got.single().second)
        codec.gate?.complete(Unit)
        runCurrent()
        assertEquals(1, waiter.got.size, "the older decode gives nothing")
        assertSame(newer, cache.request(key(1)))
    }

    /**
     * Memory is short: the decoded copies go first, all of them, since they are many times what is stored and decode
     * back from it, and none are kept until the window ends. The stored ones give back half.
     */
    @Test fun memoryPressureEmptiesTheDecodedFirstAndHalvesTheStored() = runTest {
        val codec = FakeCodec()
        val cache = cache(4_000, codec = codec)
        repeat(4) { cache.put(key(it), bytes(), image()) }
        cache.onMemoryPressure()
        assertEquals(0, cache.decodedBytes)
        assertEquals(0, cache.decodedCount)
        assertEquals(800, cache.sizeBytes)
        // A view on screen still gets its cover decoded, and holds it itself; the cache keeps no copy.
        codec.gate = CompletableDeferred()
        val waiter = Waiter()
        assertNull(cache.request(key(3), waiter.call))
        codec.gate?.complete(Unit)
        runCurrent()
        codec.gate = null
        assertEquals(1, waiter.got.size)
        assertEquals(0, cache.decodedCount)
        // A prefetch would decode what nothing keeps: it decodes nothing.
        assertNull(cache.request(key(2)))
        assertEquals(1, codec.decodes)
        cache.onPressureEnded()
        assertNotNull(cache.request(key(3)), "decoded and kept again")
        assertEquals(1, cache.decodedCount)
        repeat(4) { cache.put(key(10 + it), bytes()) }
        assertEquals(2_400, cache.sizeBytes, "the whole budget is back")
    }

    @Test fun everyPixelFormatCostsItsOwnBytesDecoded() = runTest {
        for ((config, bytes) in listOf(
            ImageBitmapConfig.Alpha8 to 100L,
            ImageBitmapConfig.Rgb565 to 200L,
            ImageBitmapConfig.Argb8888 to 400L,
            ImageBitmapConfig.F16 to 800L,
            ImageBitmapConfig.Gpu to 400L,
        )) {
            val cache = cache(10_000)
            cache.put(key(1), bytes(), image(config))
            assertEquals(bytes, cache.decodedBytes, "$config")
        }
    }

    @Test fun aDecodedImageThatChangesFormatAfterwardsStillLeavesExactly() = runTest {
        // Android can reconfigure a bitmap in place. However the image leaves, the cache gives back what it took.
        val cache = cache(10_000, decodedMaxBytes = 1_000)
        val changed = image(ImageBitmapConfig.F16) // 800 bytes
        cache.put(key(1), bytes(), changed)
        changed.config = ImageBitmapConfig.Alpha8 // now it would count 100
        cache.put(key(2), bytes(), image()) // 1,200 bytes: the changed image goes
        assertEquals(400, cache.decodedBytes)
        cache.remove(key(2))
        assertEquals(0, cache.decodedBytes)
    }

    @Test fun aDecodedImageLargerThanItsBudgetIsDrawnButNotKept() = runTest {
        val cache = cache(10_000, decodedMaxBytes = 100)
        cache.put(key(1), bytes(), image())
        assertTrue(key(1) in cache, "stored")
        assertEquals(0, cache.decodedCount)
    }

    // --- The read-only statistics apps and the perf lab read ---

    /** A request answered decoded at once is a hit; one that had to start a decode is a miss, once however many ask. */
    @Test fun hitsAndMissesCountRequestsByWhetherTheyHadToDecode() = runTest {
        val codec = FakeCodec().apply { gate = CompletableDeferred() }
        val cache = cache(10_000, codec = codec)
        cache.put(key(1), bytes(), image())
        cache.put(key(2), bytes())
        assertNotNull(cache.request(key(1)))
        assertEquals(1L to 0L, cache.hits to cache.misses, "decoded with its put")
        assertNull(cache.request(key(2), Waiter().call))
        assertNull(cache.request(key(2), Waiter().call))
        assertNull(cache.request(key(2)))
        assertEquals(1L to 1L, cache.hits to cache.misses, "one decode, joined by the later requests")
        codec.gate?.complete(Unit)
        runCurrent()
        assertNotNull(cache.request(key(2)))
        assertNull(cache.request(key(3)), "nothing stored")
        assertEquals(2L to 1L, cache.hits to cache.misses, "decoded now; what isn't stored is neither")
    }

    /** What the budget made the cache let go counts, memory pressure's halving too; a replaced or removed one doesn't. */
    @Test fun evictionsCountWhatTheBudgetLetGo() = runTest {
        val cache = cache(1_200)
        repeat(3) { cache.put(key(it), bytes()) }
        cache.put(key(0), bytes())
        cache.remove(key(1))
        assertEquals(0L, cache.evictions, "replaced and removed")
        repeat(2) { cache.put(key(10 + it), bytes()) }
        assertEquals(1L, cache.evictions, "the least recently used, for room")
        cache.onMemoryPressure()
        assertEquals(400, cache.sizeBytes, "within half of the 1,200 bytes it stored")
        assertEquals(3L, cache.evictions, "two more, under pressure")
        cache.clear()
        assertEquals(3L, cache.evictions, "a clear forgets; it lets nothing go for room")
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
