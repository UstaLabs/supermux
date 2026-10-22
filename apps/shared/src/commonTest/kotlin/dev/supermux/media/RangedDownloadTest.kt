package dev.supermux.media

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RangedDownloadTest {
    private val content = ByteArray(10) { it.toByte() }

    private suspend fun collect(download: RangeDownload, rangeBytes: Long, sizeHint: Long? = null): Pair<Boolean, ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        val ok = downloadInRanges(download, rangeBytes, sizeHint, append = { buf, n -> chunks += buf.copyOf(n) })
        return ok to concat(chunks)
    }

    @Test fun aFileArrivesInRangesAndTheLastOneIsClampedToTheEnd() = runTest {
        val calls = mutableListOf<Pair<Long, Long>>()
        val (ok, got) = collect(rangeServer(content, calls), rangeBytes = 4)
        assertTrue(ok)
        assertContentEquals(content, got)
        // The broker answers 416 to a range past the end, so once the size is known the end is clamped.
        assertEquals(listOf(0L to 3L, 4L to 7L, 8L to 9L), calls)
    }

    @Test fun aSizeHintClampsTheFirstRequest() = runTest {
        val calls = mutableListOf<Pair<Long, Long>>()
        collect(rangeServer(content, calls), rangeBytes = 64, sizeHint = 10)
        assertEquals(listOf(0L to 9L), calls)
    }

    @Test fun aHintSmallerThanTheFileDoesNotStopTheDownloadEarly() = runTest {
        val (ok, got) = collect(rangeServer(content), rangeBytes = 64, sizeHint = 3)
        assertTrue(ok)
        assertContentEquals(content, got)
    }

    @Test fun aDroppedRangeResumesWhereTheBytesStopped() = runTest {
        var dropped = false
        val calls = mutableListOf<Pair<Long, Long>>()
        val flaky: RangeDownload = { from, to, sink ->
            calls += from to to
            if (!dropped && from == 4L) {
                dropped = true
                sink(content.copyOfRange(4, 6), 2) // two bytes arrive, then the connection drops
                null
            } else {
                rangeServer(content)(from, to, sink)
            }
        }
        val (ok, got) = collect(flaky, rangeBytes = 4)
        assertTrue(ok)
        assertContentEquals(content, got)
        assertEquals(6L, calls[2].first, "the retry asks for the bytes after the ones that arrived")
    }

    @Test fun threeFailuresInARowGiveUp() = runTest {
        var calls = 0
        val (ok, _) = collect({ _, _, _ -> calls++; null }, rangeBytes = 4)
        assertFalse(ok)
        assertEquals(3, calls)
    }

    @Test fun anEmptyFileIsComplete() = runTest {
        val (ok, got) = collect(rangeServer(ByteArray(0)), rangeBytes = 4)
        assertTrue(ok)
        assertEquals(0, got.size)
    }

    @Test fun aRangeThatDeliversNothingCountsAsAFailure() = runTest {
        var calls = 0
        val (ok, _) = collect({ _, _, _ -> calls++; RangeResult(10) }, rangeBytes = 4)
        assertFalse(ok)
        assertEquals(3, calls)
    }

    @Test fun aSlowLinkThatDropsEveryRangePartwayStillFinishes() = runTest {
        // Every request brings two bytes and then times out; only a request past the end gets an answer.
        val slow: RangeDownload = { from, _, sink ->
            if (from >= content.size) {
                RangeResult(content.size.toLong())
            } else {
                val end = minOf(from + 2, content.size.toLong()).toInt()
                sink(content.copyOfRange(from.toInt(), end), end - from.toInt())
                null
            }
        }
        val (ok, got) = collect(slow, rangeBytes = 4)
        assertTrue(ok, "progress on every request is not a dead link")
        assertContentEquals(content, got)
    }
}
