package dev.supermux.webview.wpetest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineTest {
    @Test fun latencyIsToTheFirstFrameWithTheExpectedMarkerThenItsDraw() {
        val stimulus = Stimulus("click", 1_000_000_000, expectMarker = 7)
        val arrivals = listOf(
            Arrival(1, 6, 999_000_000), Arrival(2, 6, 1_005_000_000), Arrival(3, 7, 1_020_000_000), Arrival(4, 7, 1_030_000_000),
        )
        val draws = listOf(Draw(2, 1_008_000_000), Draw(3, 1_031_000_000))
        val latency = Timeline.latencies(listOf(stimulus), arrivals, draws).single()
        assertEquals(20.0, latency.arrivalMs)
        assertEquals(31.0, latency.drawnMs)
    }

    @Test fun aMarkerSeenBeforeTheStimulusDoesNotCount() {
        val stimulus = Stimulus("key", 2_000_000_000, expectMarker = 3)
        val arrivals = listOf(Arrival(1, 3, 1_990_000_000), Arrival(2, 3, 2_016_000_000))
        val latency = Timeline.latencies(listOf(stimulus), arrivals, emptyList()).single()
        assertEquals(16.0, latency.arrivalMs)
        assertNull(latency.drawnMs)
    }

    @Test fun noArrivalMeansNoLatency() {
        val latency = Timeline.latencies(listOf(Stimulus("x", 0, 9)), listOf(Arrival(1, 8, 5)), emptyList()).single()
        assertNull(latency.arrivalMs)
        assertNull(latency.drawnMs)
    }

    @Test fun rateCountsIntervals() {
        assertEquals(60.0, Timeline.rate(List(61) { it * 1_000_000_000L / 60 }), 1e-6)
        assertTrue(Timeline.rate(listOf(5L)).isNaN())
    }

    @Test fun intervalsAreSortedDifferences() {
        assertEquals(listOf(10.0, 20.0), Timeline.intervalsMs(listOf(30_000_000, 0, 10_000_000)))
    }

    @Test fun lostPageFramesFromCounterGapsAcrossTheWrap() {
        assertEquals(3, Timeline.missedPageFrames(listOf(65534, 65535, 1, 2, 5)))
        assertEquals(0, Timeline.missedPageFrames(listOf(4, 4, 5)))
    }

    @Test fun markerPixelsDecodeToTheCounter() {
        assertEquals(0x1234, Markers.counter(0x12345a))
        assertNull(Markers.counter(0x123400))
        assertNull(Markers.counter(-1))
    }
}
