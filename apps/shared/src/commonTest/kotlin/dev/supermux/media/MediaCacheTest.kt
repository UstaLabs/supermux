package dev.supermux.media

import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.proto.Attachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaCacheTest {
    private val host = "rec-1"
    private val id = "0123456789abcdef0123456789abcdef"
    private val png = byteArrayOf(1, 2, 3, 4, 5)

    private fun att(fileId: String = id, name: String? = "shot.png", mime: String? = "image/png", size: Long? = null) =
        Attachment(file_id = fileId, kind = "image", mime = mime, size = size, name = name)

    // The test's own scope, not `backgroundScope`: `advanceUntilIdle()` never runs background-only
    // work, so a write-behind or a seed launched there would silently not happen before an assertion.
    private fun TestScope.cache(store: MediaStore, log: MutableList<String> = mutableListOf()) =
        MediaCache(store, this, rangeBytes = 4, log = { log += it })

    private class Network(val body: ByteArray?) {
        var fetches = 0
        val fetch: suspend () -> ByteArray? = {
            fetches++
            body
        }
    }

    @Test fun aMemoryHitTouchesNeitherTheStoreNorTheNetwork() = runTest {
        val store = FakeMediaStore()
        val log = mutableListOf<String>()
        val cache = cache(store, log)
        val net = Network(png)
        cache.bytes(host, att(), net.fetch, rangeServer(png))
        store.failReads = true
        val again = cache.bytes(host, att(), net.fetch, download = { _, _, _ -> error("no network") })
        assertContentEquals(png, again)
        assertTrue(log.last().endsWith("<- memory"), "log: $log")
        assertContentEquals(png, cache.peek(host, att()))
    }

    @Test fun aDiskHitIsPromotedToMemoryWithoutTheNetwork() = runTest {
        val store = FakeMediaStore().apply { entries["$host/$id.png"] = png }
        val log = mutableListOf<String>()
        val net = Network(null)
        val cache = cache(store, log)
        val got = cache.bytes(host, att(), net.fetch, download = { _, _, _ -> error("no network") })
        assertContentEquals(png, got)
        assertEquals(0, net.fetches)
        assertTrue(log.last().endsWith("<- disk"), "log: $log")
        assertContentEquals(png, cache.peek(host, att()))
    }

    @Test fun onAFilesPlatformTheDownloadStreamsIntoTheStore() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val calls = mutableListOf<Pair<Long, Long>>()
        val net = Network(null)
        val got = cache(store).bytes(host, att(), net.fetch, rangeServer(png, calls))
        assertContentEquals(png, got)
        assertContentEquals(png, store.entries["$host/$id.png"])
        assertEquals(0, net.fetches, "a files platform never buffers the plain GET")
        assertEquals(2, calls.size, "5 bytes at 4 per range")
    }

    @Test fun onTheWebTheFetchIsWrittenBehind() = runTest {
        val store = FakeMediaStore(hasFiles = false)
        val net = Network(png)
        val got = cache(store).bytes(host, att(), net.fetch, download = null)
        assertContentEquals(png, got)
        testScheduler.advanceUntilIdle()
        assertContentEquals(png, store.entries["$host/$id.png"])
    }

    @Test fun aMemoryOnlyStoreIsNeverWritten() = runTest {
        val store = FakeMediaStore(hasFiles = false, persistent = false)
        cache(store).bytes(host, att(), Network(png).fetch, download = null)
        testScheduler.advanceUntilIdle()
        assertTrue(store.writes.isEmpty())
    }

    @Test fun twoCallersShareOneDownload() = runTest {
        val cache = cache(FakeMediaStore(hasFiles = false))
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val fetch: suspend () -> ByteArray? = {
            fetches++
            gate.await()
            png
        }
        val a = async { cache.bytes(host, att(), fetch, download = null) }
        val b = async { cache.bytes(host, att(), fetch, download = null) }
        runCurrent()
        gate.complete(Unit)
        assertContentEquals(png, a.await())
        assertContentEquals(png, b.await())
        assertEquals(1, fetches)
    }

    // #95: a chat row awaits this from its own scope, and leaving the chat cancels that caller. The
    // download itself runs on the cache's scope and must finish, so the file is there next time.
    @Test fun aDownloadOutlivesTheCallerThatStartedIt() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val gate = CompletableDeferred<Unit>()
        val slow: RangeDownload = { from, to, sink ->
            gate.await()
            rangeServer(png)(from, to, sink)
        }
        val cache = cache(store)
        val caller = launch { cache.bytes(host, att(), Network(null).fetch, slow) }
        runCurrent()
        caller.cancel()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(caller.isCancelled)
        assertContentEquals(png, store.entries["$host/$id.png"])
        assertContentEquals(png, cache.peek(host, att()))
    }

    @Test fun aFailedDownloadIsNullAndKeepsNothing() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        assertNull(cache.bytes(host, att(), Network(null).fetch, download = { _, _, _ -> null }))
        assertNull(cache.peek(host, att()))
        assertTrue(store.entries.isEmpty())
    }

    @Test fun aDiskReadFailureFallsBackToTheNetwork() = runTest {
        val store = FakeMediaStore(hasFiles = false).apply { failReads = true }
        assertContentEquals(png, cache(store).bytes(host, att(), Network(png).fetch, download = null))
    }

    @Test fun aDiskWriteFailureStillShowsTheFile() = runTest {
        val store = FakeMediaStore(hasFiles = true).apply { failWrites = true }
        val net = Network(png)
        assertContentEquals(png, cache(store).bytes(host, att(), net.fetch, rangeServer(png)))
        assertEquals(1, net.fetches, "the plain GET is the fallback when the store cannot take the stream")
    }

    @Test fun aHalfFinishedDownloadLeavesNoEntry() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val half: RangeDownload = { from, _, sink ->
            if (from == 0L) {
                sink(png, 4)
                RangeResult(png.size.toLong())
            } else {
                null
            }
        }
        assertNull(cache(store).bytes(host, att(), Network(null).fetch, half))
        assertTrue(store.entries.isEmpty())
    }

    @Test fun refreshSkipsEveryCachedCopy() = runTest {
        val stale = byteArrayOf(9, 9)
        val store = FakeMediaStore(hasFiles = true).apply { entries["$host/$id.png"] = stale }
        val cache = cache(store)
        assertContentEquals(stale, cache.bytes(host, att(), Network(null).fetch, rangeServer(png)))
        assertContentEquals(png, cache.bytes(host, att(), Network(null).fetch, rangeServer(png), refresh = true))
        assertContentEquals(png, store.entries["$host/$id.png"])
        assertContentEquals(png, cache.peek(host, att()))
    }

    @Test fun anIdOrHostThatCouldNameAPathIsShownButNeverStored() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        val cases = listOf(
            host to "../../x", host to "a/b", host to "a\\b", host to "", host to id.dropLast(1),
            host to id.uppercase(), "../h" to id, "a/b" to id,
        )
        for ((h, fileId) in cases) {
            val got = cache.bytes(h, att(fileId = fileId), Network(png).fetch, rangeServer(png))
            assertContentEquals(png, got, "'$h' / '$fileId' must still be shown")
            assertContentEquals(png, cache.peek(h, att(fileId = fileId)))
        }
        testScheduler.advanceUntilIdle()
        assertTrue(store.writes.isEmpty(), "nothing invalid may reach the store: ${store.writes}")
    }

    // ── Files for the video player ──────────────────────────────────────────────────────

    @Test fun aVideoDownloadsOnceThenPlaysFromTheStore() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        val video = att(name = "clip.mov", mime = "video/quicktime")
        val calls = mutableListOf<Pair<Long, Long>>()
        val first = cache.playableUri(host, video, rangeServer(png, calls))
        assertEquals("file:///cache/$host/$id.mov", first)
        val second = cache.playableUri(host, video, { _, _, _ -> error("must not download twice") })
        assertEquals(first, second)
        assertNull(cache.peek(host, video), "a video never enters the memory tier")
    }

    @Test fun noFileUriWithoutFilesOrForAnInvalidKey() = runTest {
        assertNull(cache(FakeMediaStore(hasFiles = false)).playableUri(host, att(), rangeServer(png)))
        assertNull(cache(FakeMediaStore(hasFiles = true)).playableUri(host, att(fileId = "../x"), rangeServer(png)))
    }

    @Test fun aFailedVideoDownloadIsNullAndLeavesNoFile() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        assertNull(cache(store).playableUri(host, att(), { _, _, _ -> null }))
        assertTrue(store.entries.isEmpty())
    }

    // ── Files for a download that outlives its screen (#95) ─────────────────────────────

    @Test fun aFileDownloadIsStoredWithoutPassingThroughMemory() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        val got = assertNotNull(cache.file(host, att(), rangeServer(png)))
        assertEquals("/cache/$host/$id.png", got.path)
        assertEquals(5L, got.size)
        assertNull(cache.peek(host, att()), "never read into the memory tier")
    }

    @Test fun aStoredFileIsAnsweredWithoutTheNetwork() = runTest {
        val store = FakeMediaStore(hasFiles = true).apply { entries["$host/$id.png"] = png }
        val got = assertNotNull(cache(store).file(host, att(), { _, _, _ -> error("no network") }))
        assertEquals("/cache/$host/$id.png", got.path)
        assertEquals(5L, got.size)
    }

    @Test fun aCallerThatJoinsARunningDownloadSeesItsProgress() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        val gate = CompletableDeferred<Unit>()
        val slow: RangeDownload = { from, to, sink ->
            if (from > 0) gate.await()
            rangeServer(png)(from, to, sink)
        }
        val video = async { cache.playableUri(host, att(), slow) }
        runCurrent()
        val seen = mutableListOf<Long>()
        val file = async { cache.file(host, att(), slow) { r, _ -> seen += r } }
        runCurrent()
        gate.complete(Unit)
        assertNotNull(file.await())
        assertNotNull(video.await())
        assertEquals(4L, seen.first(), "a caller that joins hears at once where the transfer already is")
        assertEquals(5L, seen.last())
    }

    @Test fun aFailedFileDownloadResumesWhereItStopped() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        val calls = mutableListOf<Pair<Long, Long>>()
        val firstRangeOnly: RangeDownload = { from, to, sink -> if (from == 0L) rangeServer(png, calls)(from, to, sink) else null }
        assertNull(cache.file(host, att(), firstRangeOnly))
        assertEquals(4, store.partials["$host/$id.png"]?.size)
        assertNotNull(cache.file(host, att(), rangeServer(png, calls)))
        assertEquals(4L, calls.last().first, "the retry asked only for the rest")
        assertContentEquals(png, store.entries["$host/$id.png"])
    }

    @Test fun cancelStopsTheDownloadKeepsItsBytesAndWaitersSeeNull() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        val gate = CompletableDeferred<Unit>()
        val stalls: RangeDownload = { from, to, sink ->
            if (from > 0) gate.await()
            rangeServer(png)(from, to, sink)
        }
        val waiting = async { cache.file(host, att(), stalls) }
        val video = async { cache.playableUri(host, att(), stalls) }
        runCurrent()
        cache.cancelFile(host, id)
        assertNull(waiting.await())
        assertEquals(4, store.partials["$host/$id.png"]?.size)
        assertNull(video.await(), "a clip waiting on the same transfer hears null, not a cancellation")
    }

    @Test fun aCancelBeforeTheTransferStartsStillStopsIt() = runTest {
        val cache = cache(FakeMediaStore(hasFiles = true))
        val calls = mutableListOf<Pair<Long, Long>>()
        // Undispatched: the call has made the download's slot, whose transfer has not begun yet.
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { cache.file(host, att(), rangeServer(png, calls)) }
        cache.cancelFile(host, id)
        assertNull(waiting.await())
        assertEquals(emptyList(), calls, "nothing was asked for")
        assertNotNull(cache.file(host, att(), rangeServer(png)), "a cancelled download can be started again")
    }

    @Test fun anOverlongPartialIsDiscardedAndDownloadedAgain() = runTest {
        val store = FakeMediaStore(hasFiles = true).apply { partials["$host/$id.png"] = ByteArray(9) }
        assertNotNull(cache(store).file(host, att(), rangeServer(png)))
        assertContentEquals(png, store.entries["$host/$id.png"])
    }

    @Test fun noCallerSeesMoreBytesThanTheFileHolds() = runTest {
        // A kept partial of 9 bytes for a 5-byte file: the first answer reports 9 of 5 just before it is thrown away.
        val store = FakeMediaStore(hasFiles = true).apply { partials["$host/$id.png"] = ByteArray(9) }
        val cache = cache(store)
        val seen = mutableListOf<Pair<Long, Long?>>()
        // Unconfined, so the caller hears every value the download passes through, not only the latest.
        withContext(UnconfinedTestDispatcher(testScheduler)) {
            assertNotNull(cache.file(host, att(), rangeServer(png)) { r, t -> seen += r to t })
        }
        assertTrue(seen.none { (r, t) -> t != null && r > t }, "seen: $seen")
        assertEquals(5L to 5L, seen.last())
    }

    @Test fun aDownloadThatStopsAfterAClearKeepsNothing() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        val gate = CompletableDeferred<Unit>()
        val dropsAfterTheGate: RangeDownload = { from, to, sink ->
            if (from > 0) {
                gate.await()
                null
            } else {
                rangeServer(png)(from, to, sink)
            }
        }
        val seen = mutableListOf<Long>()
        val waiting = async { cache.file(host, att(), dropsAfterTheGate) { r, _ -> seen += r } }
        runCurrent()
        assertEquals(4L, seen.lastOrNull(), "the first range arrived before the Clear")
        cache.clear()
        gate.complete(Unit)
        assertNull(waiting.await())
        assertTrue(store.partials.isEmpty(), "a cleared cache keeps no partial: ${store.partials.keys}")
    }

    @Test fun aStoreThatCannotKeepAPartialStillGetsTheFile() = runTest {
        val store = FakeMediaStore(hasFiles = true, resumable = false)
        val seen = mutableListOf<Long>()
        val got = assertNotNull(cache(store).file(host, att(), rangeServer(png)) { r, _ -> seen += r })
        assertEquals(5L, got.size)
        assertContentEquals(png, store.entries["$host/$id.png"])
        assertEquals(5L, seen.last())
    }

    @Test fun theWebHasNoFileDownload() = runTest {
        assertNull(cache(FakeMediaStore(hasFiles = false)).file(host, att(), rangeServer(png)))
    }

    // ── Seeding, clear, forget host, size ───────────────────────────────────────────────

    @Test fun anUploadIsSeededIntoTheStore() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        cache.seed(host, id, "shot.png", "image/png", png.size.toLong(), ByteArrayChunkSource(png))
        testScheduler.advanceUntilIdle()
        assertContentEquals(png, store.entries["$host/$id.png"])
        val net = Network(null)
        assertContentEquals(png, cache.bytes(host, att(), net.fetch, download = { _, _, _ -> error("seeded") }))
        assertEquals(0, net.fetches)
    }

    @Test fun seedingSkipsAStoredFileAndAMemoryOnlyStore() = runTest {
        val store = FakeMediaStore(hasFiles = true).apply { entries["$host/$id.png"] = png }
        cache(store).seed(host, id, "shot.png", "image/png", 1, ByteArrayChunkSource(byteArrayOf(7)))
        testScheduler.advanceUntilIdle()
        assertTrue(store.writes.isEmpty())
        val memoryOnly = FakeMediaStore(hasFiles = false, persistent = false)
        cache(memoryOnly).seed(host, id, "shot.png", "image/png", png.size.toLong(), ByteArrayChunkSource(png))
        testScheduler.advanceUntilIdle()
        assertTrue(memoryOnly.writes.isEmpty())
    }

    @Test fun aFailedSeedIsDropped() = runTest {
        val store = FakeMediaStore(hasFiles = true).apply { failWrites = true }
        val log = mutableListOf<String>()
        cache(store, log).seed(host, id, "shot.png", "image/png", png.size.toLong(), ByteArrayChunkSource(png))
        testScheduler.advanceUntilIdle()
        assertTrue(store.entries.isEmpty())
        assertTrue(log.any { it.contains("seed failed") }, "log: $log")
    }

    @Test fun clearEmptiesMemoryAndStore() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        cache.bytes(host, att(), Network(null).fetch, rangeServer(png))
        assertEquals(png.size.toLong(), cache.sizeBytes())
        cache.clear()
        assertNull(cache.peek(host, att()))
        assertTrue(store.entries.isEmpty())
        assertEquals(0L, cache.sizeBytes())
    }

    @Test fun forgettingAHostDropsOnlyItsFiles() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        cache.bytes(host, att(), Network(null).fetch, rangeServer(png))
        cache.bytes("rec-2", att(), Network(null).fetch, rangeServer(png))
        cache.removeHost(host)
        assertNull(cache.peek(host, att()))
        assertNotNull(cache.peek("rec-2", att()))
        assertEquals(setOf("rec-2/$id.png"), store.entries.keys)
    }

    @Test fun aDownloadThatFinishesAfterAClearDoesNotComeBack() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        val gate = CompletableDeferred<Unit>()
        val slow: RangeDownload = { from, to, sink ->
            gate.await()
            rangeServer(png)(from, to, sink)
        }
        val video = async { cache.playableUri(host, att(), slow) }
        runCurrent()
        cache.clear()
        gate.complete(Unit)
        video.await()
        assertTrue(store.entries.isEmpty(), "a cleared cache stays cleared")
    }

    @Test fun sizeIsTheMemoryTiersWhenNothingPersists() = runTest {
        val cache = cache(FakeMediaStore(hasFiles = false, persistent = false))
        cache.bytes(host, att(), Network(png).fetch, download = null)
        assertEquals(png.size.toLong(), cache.sizeBytes())
    }

    // ── Review fixes ────────────────────────────────────────────────────────────────────

    @Test fun aNetworkFailureIsNotDownloadedAgainWholeInMemory() = runTest {
        val net = Network(png)
        assertNull(cache(FakeMediaStore(hasFiles = true)).bytes(host, att(), net.fetch, download = { _, _, _ -> null }))
        assertEquals(0, net.fetches, "the one-shot GET is only for a store that failed, not a network that did")
    }

    @Test fun whereEntriesAreNotFilesOnlySmallUploadsAreSeeded() = runTest {
        val store = FakeMediaStore(hasFiles = false)
        val cache = cache(store)
        val big = ByteArray((MediaCache.SEED_MAX_WITHOUT_FILES + 1).toInt())
        cache.seed(host, id, "big.png", "image/png", big.size.toLong(), ByteArrayChunkSource(big))
        testScheduler.advanceUntilIdle()
        assertTrue(store.writes.isEmpty(), "a big upload is not read back synchronously in the browser")
        cache.seed(host, id, "shot.png", "image/png", png.size.toLong(), ByteArrayChunkSource(png))
        testScheduler.advanceUntilIdle()
        assertContentEquals(png, store.entries["$host/$id.png"])
    }

    @Test fun aSourceThatNoLongerMatchesTheUploadIsNotSeeded() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val log = mutableListOf<String>()
        val cache = cache(store, log)
        // The file shrank after the upload (deleted, truncated, re-captured): its size no longer matches.
        cache.seed(host, id, "shot.png", "image/png", 10, ByteArrayChunkSource(png))
        // It grew (re-captured larger): its first bytes are not the upload either.
        cache.seed("rec-2", id, "shot.png", "image/png", 3, ByteArrayChunkSource(png))
        // The size matches but a read comes back short mid-copy.
        val shortRead = object : dev.supermux.net.ChunkSource {
            override val size: Long = png.size.toLong()
            override fun read(offset: Long, len: Int): ByteArray = if (offset == 0L) png.copyOf(2) else ByteArray(0)
        }
        cache.seed(host, id, "shot.png", "image/png", png.size.toLong(), shortRead)
        testScheduler.advanceUntilIdle()
        assertTrue(store.entries.isEmpty(), "entries: ${store.entries.keys}")
        assertTrue(log.any { it.contains("not seeded") }, "log: $log")
    }

    @Test fun forgettingOneHostKeepsAnotherHostsDownload() = runTest {
        val store = FakeMediaStore(hasFiles = true)
        val cache = cache(store)
        val gate = CompletableDeferred<Unit>()
        val slow: RangeDownload = { from, to, sink ->
            gate.await()
            rangeServer(png)(from, to, sink)
        }
        val video = async { cache.playableUri("rec-2", att(), slow) }
        runCurrent()
        cache.removeHost(host)
        gate.complete(Unit)
        assertEquals("file:///cache/rec-2/$id.png", video.await())
        assertContentEquals(png, store.entries["rec-2/$id.png"])
    }

    @Test fun aRefreshWaitsForALoadAlreadyUnderWay() = runTest {
        val bad = byteArrayOf(9, 9)
        val cache = cache(FakeMediaStore(hasFiles = false))
        val gate = CompletableDeferred<Unit>()
        val slowBad: suspend () -> ByteArray? = {
            gate.await()
            bad
        }
        val first = async { cache.bytes(host, att(), slowBad, download = null) }
        runCurrent()
        val refreshed = async { cache.bytes(host, att(), Network(png).fetch, download = null, refresh = true) }
        runCurrent()
        gate.complete(Unit)
        first.await()
        assertContentEquals(png, refreshed.await())
        assertContentEquals(png, cache.peek(host, att()), "the older load must not put its copy back over the refresh")
    }
}
