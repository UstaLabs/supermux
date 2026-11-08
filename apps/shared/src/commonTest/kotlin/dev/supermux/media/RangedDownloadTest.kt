package dev.supermux.media

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RangedDownloadTest {
    private val content = ByteArray(10) { it.toByte() }

    private suspend fun collect(download: RangeDownload, rangeBytes: Long, sizeHint: Long? = null): Pair<RangeOutcome, ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        val outcome = downloadInRanges(download, rangeBytes, sizeHint, append = { buf, n -> chunks += buf.copyOf(n) })
        return outcome to concat(chunks)
    }

    @Test fun aFileArrivesInRangesAndTheLastOneIsClampedToTheEnd() = runTest {
        val calls = mutableListOf<Pair<Long, Long>>()
        val (outcome, got) = collect(rangeServer(content, calls), rangeBytes = 4)
        assertEquals(RangeOutcome.Complete, outcome)
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
        val (outcome, got) = collect(rangeServer(content), rangeBytes = 64, sizeHint = 3)
        assertEquals(RangeOutcome.Complete, outcome)
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
        val (outcome, got) = collect(flaky, rangeBytes = 4)
        assertEquals(RangeOutcome.Complete, outcome)
        assertContentEquals(content, got)
        assertEquals(6L, calls[2].first, "the retry asks for the bytes after the ones that arrived")
    }

    @Test fun threeFailuresInARowGiveUp() = runTest {
        var calls = 0
        val (outcome, _) = collect({ _, _, _ -> calls++; null }, rangeBytes = 4)
        assertEquals(RangeOutcome.Failed, outcome)
        assertEquals(3, calls)
    }

    @Test fun anEmptyFileIsComplete() = runTest {
        val (outcome, got) = collect(rangeServer(ByteArray(0)), rangeBytes = 4)
        assertEquals(RangeOutcome.Complete, outcome)
        assertEquals(0, got.size)
    }

    @Test fun aRangeThatDeliversNothingCountsAsAFailure() = runTest {
        var calls = 0
        val (outcome, _) = collect({ _, _, _ -> calls++; RangeResult(10) }, rangeBytes = 4)
        assertEquals(RangeOutcome.Failed, outcome)
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
        val (outcome, got) = collect(slow, rangeBytes = 4)
        assertEquals(RangeOutcome.Complete, outcome, "progress on every request is not a dead link")
        assertContentEquals(content, got)
    }

    @Test fun aResumedDownloadAsksOnlyForTheRest() = runTest {
        val content = byteArrayOf(1, 2, 3, 4, 5)
        val calls = mutableListOf<Pair<Long, Long>>()
        val got = mutableListOf<Byte>()
        val outcome = downloadInRanges(rangeServer(content, calls), 4, 5, { b, n -> repeat(n) { got += b[it] } }, startAt = 3)
        assertEquals(RangeOutcome.Complete, outcome)
        assertEquals(listOf(3L to 4L), calls)
        assertEquals(listOf<Byte>(4, 5), got)
    }

    @Test fun progressClimbsToTheTotal() = runTest {
        val seen = mutableListOf<Pair<Long, Long?>>()
        downloadInRanges(rangeServer(ByteArray(10) { it.toByte() }), 4, null, { _, _ -> }, onProgress = { r, t -> seen += r to t })
        assertEquals(10L to 10L, seen.last())
        assertEquals(seen.map { it.first }, seen.map { it.first }.sorted())
    }

    @Test fun aKeptPartialThatIsTheWholeFileCompletesWithoutNewBytes() = runTest {
        // The app died after the last byte was kept but before the file was committed.
        val seen = mutableListOf<Pair<Long, Long?>>()
        val outcome = downloadInRanges(
            rangeServer(byteArrayOf(1, 2, 3)), 4, 3, { _, _ -> error("nothing is left to download") },
            startAt = 3, onProgress = { r, t -> seen += r to t },
        )
        assertEquals(RangeOutcome.Complete, outcome)
        assertEquals(listOf<Pair<Long, Long?>>(3L to 3L), seen, "no chunk arrives, yet the total is still announced")
    }

    @Test fun aPartialLongerThanTheFileIsOverlong() = runTest {
        val outcome = downloadInRanges(rangeServer(byteArrayOf(1, 2, 3)), 4, 3, { _, _ -> }, startAt = 5)
        assertEquals(RangeOutcome.Overlong, outcome)
    }
}
