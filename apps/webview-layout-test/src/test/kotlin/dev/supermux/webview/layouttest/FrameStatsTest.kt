package dev.supermux.webview.layouttest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameStatsTest {
    @Test fun anEmptyRunHasNoFrames() {
        val summary = FrameStats.summarize(emptyList(), 16.7)
        assertEquals(0, summary.count)
        assertEquals("frames=0", summary.line())
    }

    @Test fun percentilesUseTheNearestRank() {
        val summary = FrameStats.summarize((1..100).map { it.toDouble() }, 16.7)
        assertEquals(51.0, summary.p50)
        assertEquals(95.0, summary.p95)
        assertEquals(99.0, summary.p99)
        assertEquals(100.0, summary.max)
    }

    @Test fun lateFramesAreThoseLongerThanOneAndAHalfIntervals() {
        assertEquals(3, FrameStats.summarize(listOf(16.0, 16.5, 25.0, 25.1, 33.3), 16.0).late)
    }

    @Test fun intervalsComeFromConsecutiveTimestamps() {
        assertEquals(listOf(16.0, 17.0), FrameStats.intervals(listOf(0L, 16_000_000L, 33_000_000L)))
    }

    @Test fun theLineReadsTheSameInEveryLocale() {
        val line = FrameStats.summarize(listOf(16.25, 16.25), 16.7).line()
        assertTrue(line.startsWith("frames=2 p50=16.3ms"), line)
    }
}
