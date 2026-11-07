package dev.supermux.webview.wintest

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineTest {
    private val ms = 1_000_000L

    private fun arrival(marker: Int, arriveMs: Long, epochUs: Long, renderMs: Long) =
        Arrival(marker, arriveMs * ms, epochUs, renderMs * ms, 2 * ms, 10, 10)

    @Test fun latenciesLineUpByMarker() {
        val pages = listOf(PageMark(7, 1_000.0), PageMark(8, 1_100.0))
        val arrivals = listOf(arrival(7, 50, 1_012_000, 46), arrival(8, 150, 1_114_000, 147))
        val draws = listOf(Draw(7, 58 * ms, ms, false), Draw(8, 165 * ms, ms, false))
        val stimuli = listOf(Stimulus(7, 40 * ms), Stimulus(8, 140 * ms))

        val l = LatencyMatcher.match(pages, arrivals, draws, stimuli)

        assertEquals(12.0, l.pageToArriveMs.p50)
        assertEquals(14.0, l.pageToArriveMs.max)
        assertEquals(3.0, l.renderToArriveMs.p50)
        assertEquals(4.0, l.renderToArriveMs.max)
        assertEquals(8.0, l.arriveToDrawnMs.p50)
        assertEquals(15.0, l.arriveToDrawnMs.max)
        assertEquals(10.0, l.stimulusToArriveMs.p50)
        assertEquals(18.0, l.stimulusToDrawnMs.p50)
        assertEquals(25.0, l.stimulusToDrawnMs.max)
        assertEquals(20.0, l.pageToDrawnMs.p50)
        assertEquals(29.0, l.pageToDrawnMs.max)
        assertEquals(0, l.unmatched)
    }

    @Test fun aChangeComposeNeverDrewIsUnmatched() {
        val l = LatencyMatcher.match(
            pages = listOf(PageMark(1, 0.0), PageMark(2, 10.0)),
            arrivals = listOf(arrival(1, 5, 5_000, 4), arrival(2, 15, 15_000, 14)),
            draws = listOf(Draw(1, 9 * ms, ms, false)),
            stimuli = emptyList(),
        )
        assertEquals(1, l.unmatched)
        assertEquals(1, l.arriveToDrawnMs.count)
    }

    @Test fun eachMarkerCountsItsFirstArrivalAndFirstDraw() {
        val l = LatencyMatcher.match(
            pages = listOf(PageMark(3, 0.0)),
            arrivals = listOf(arrival(3, 20, 20_000, 19), arrival(3, 10, 10_000, 9)),
            draws = listOf(Draw(3, 30 * ms, ms, false), Draw(3, 25 * ms, ms, false)),
            stimuli = emptyList(),
        )
        assertEquals(15.0, l.arriveToDrawnMs.p50)
        assertEquals(10.0, l.pageToArriveMs.p50)
    }

    @Test fun ratesCountDistinctMarkersPerStage() {
        val pages = (1..10).map { PageMark(it, it * 16.0) }
        val arrivals = (1..10).filter { it != 4 }.map { arrival(it, it.toLong(), 0, 0) } + arrival(99, 0, 0, 0)
        val draws = listOf(1, 2, 3, 5, 6, 8, 9, 10).map { Draw(it, 0, 0, false) }

        val r = FrameRates.compute(pages, arrivals, draws, seconds = 2.0)

        assertEquals(10, r.pageFrames)
        assertEquals(9, r.captured)
        assertEquals(8, r.drawn)
        assertEquals(1, r.missedByCapture)
        assertEquals(1, r.notDrawn)
        assertEquals(5.0, r.pageFps)
        assertEquals(4.5, r.captureFps)
        assertEquals(4.0, r.drawnFps)
    }
}
