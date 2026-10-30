package dev.supermux.webview.wintest

/** A marker the page drew, on the page's clock (performance.timeOrigin + now(): epoch milliseconds). */
data class PageMark(val marker: Int, val pageEpochMs: Double)

/**
 * A captured frame as it arrived natively (FrameCapture's ring): System.nanoTime's clock, plus the wall clock. Of
 * [copyNs], [readNs] is the GPU -> staging part and [lockNs] the wait for the CPU buffer's lock. [dirtyPx] and [rects]
 * are what the frame copied: the pixels and rectangles of WGC's dirty regions, or the whole frame.
 */
data class Arrival(
    val marker: Int,
    val arriveNs: Long,
    val arriveEpochUs: Long,
    val renderNs: Long,
    val copyNs: Long,
    val width: Int,
    val height: Int,
    val readNs: Long = 0,
    val lockNs: Long = 0,
    val dirtyPx: Long = 0,
    val rects: Int = 0,
    /** The three-copy chain: the test's own marker read on the capture thread, not part of the chain's copy. */
    val probeNs: Long = 0,
)

/**
 * A frame Compose drew: the marker in its pixels, when, what pulling it into Skia and drawing cost, and if scaled. Of
 * [uploadNs], [pullNs] is the pull (copyFrame into the bitmap) and [drawCallNs] the drawImage call.
 */
data class Draw(
    val marker: Int,
    val drawnNs: Long,
    val uploadNs: Long,
    val scaled: Boolean,
    val pullNs: Long = 0,
    val drawCallNs: Long = 0,
    /** The three-copy chain: the pull's Map of the staging texture (0 in the five-copy chain). */
    val mapNs: Long = 0,
)

/** The host made the page show [marker] (by script or by input) at [sentNs] (System.nanoTime). */
data class Stimulus(val marker: Int, val sentNs: Long)

class Latencies(
    val pageToArriveMs: Samples,
    val renderToArriveMs: Samples,
    val arriveToDrawnMs: Samples,
    val stimulusToArriveMs: Samples,
    val stimulusToDrawnMs: Samples,
    val pageToDrawnMs: Samples,
    /** Markers the page drew or the host asked for that Compose never drew. */
    val unmatched: Int,
)

/**
 * Lines up one run's timelines by frame marker. Each marker counts once: its first page mark, its first arrival and its
 * first draw. The page and the native wall clock are both epoch time; arrivals and draws share System.nanoTime.
 */
object LatencyMatcher {
    fun match(pages: List<PageMark>, arrivals: List<Arrival>, draws: List<Draw>, stimuli: List<Stimulus>): Latencies {
        val page = pages.groupBy { it.marker }.mapValues { (_, marks) -> marks.minOf { it.pageEpochMs } }
        val arrived = arrivals.filter { it.marker >= 0 }.groupBy { it.marker }
            .mapValues { (_, list) -> list.minBy { it.arriveNs } }
        val drawn = draws.filter { it.marker >= 0 }.groupBy { it.marker }.mapValues { (_, list) -> list.minOf { it.drawnNs } }
        val sent = stimuli.associate { it.marker to it.sentNs }
        val pageToArrive = Samples("page_to_capture_ms")
        val renderToArrive = Samples("dwm_to_capture_ms")
        val arriveToDrawn = Samples("capture_to_drawn_ms")
        val stimulusToArrive = Samples("stimulus_to_capture_ms")
        val stimulusToDrawn = Samples("stimulus_to_drawn_ms")
        val pageToDrawn = Samples("page_to_drawn_ms")
        var unmatched = 0
        for (marker in (page.keys + sent.keys).sorted()) {
            val arrival = arrived[marker]
            val drawnNs = drawn[marker]
            val pageMs = page[marker]
            val sentNs = sent[marker]
            if (drawnNs == null) unmatched++
            if (arrival != null) {
                renderToArrive.add((arrival.arriveNs - arrival.renderNs) / 1e6)
                if (pageMs != null) pageToArrive.add(arrival.arriveEpochUs / 1000.0 - pageMs)
                if (sentNs != null) stimulusToArrive.add((arrival.arriveNs - sentNs) / 1e6)
                if (drawnNs != null) {
                    arriveToDrawn.add((drawnNs - arrival.arriveNs) / 1e6)
                    if (pageMs != null) {
                        pageToDrawn.add(arrival.arriveEpochUs / 1000.0 - pageMs + (drawnNs - arrival.arriveNs) / 1e6)
                    }
                }
            }
            if (sentNs != null && drawnNs != null) stimulusToDrawn.add((drawnNs - sentNs) / 1e6)
        }
        return Latencies(pageToArrive, renderToArrive, arriveToDrawn, stimulusToArrive, stimulusToDrawn, pageToDrawn, unmatched)
    }
}

/** Frame counts over one run of [seconds]: what the page drew, what capture delivered, what Compose drew. */
data class Rates(val pageFrames: Int, val captured: Int, val drawn: Int, val seconds: Double) {
    val missedByCapture: Int get() = pageFrames - captured
    val notDrawn: Int get() = captured - drawn
    val pageFps: Double get() = pageFrames / seconds
    val captureFps: Double get() = captured / seconds
    val drawnFps: Double get() = drawn / seconds
}

object FrameRates {
    fun compute(pages: List<PageMark>, arrivals: List<Arrival>, draws: List<Draw>, seconds: Double): Rates {
        require(seconds > 0.0) { "seconds must be positive: $seconds" }
        val pageMarkers = pages.map { it.marker }.toSet()
        val captured = arrivals.map { it.marker }.filter { it in pageMarkers }.toSet()
        val drawn = draws.map { it.marker }.filter { it in captured }.toSet()
        return Rates(pageMarkers.size, captured.size, drawn.size, seconds)
    }
}
