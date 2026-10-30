package dev.supermux.webview.wintest

import androidx.compose.runtime.withFrameNanos
import java.awt.event.InputEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Latency from a page change to the Compose frame that shows it (C4): 200 changes by script and 40 by a mouse press,
 * plus the script round trip through the document-creation receiver.
 */
suspend fun Harness.latency() {
    requireFrames()
    web.call("animate", "off")
    delay(500)
    resetTimelines()
    val scripted = ArrayList<Stimulus>()
    repeat(200) { i ->
        val marker = 1_000 + i
        scripted += Stimulus(marker, System.nanoTime())
        web.call("setMarker", marker)
        delay(100)
    }
    delay(1_000)
    val s = LatencyMatcher.match(takeMarks(), drainArrivals(), web.buffer.takeDraws(), scripted)
    report.line("L1-capture-to-drawn", verdict(s.arriveToDrawnMs.p95 <= 20.0), s.arriveToDrawnMs.pairs())
    report.line("L2-page-to-drawn", verdict(s.pageToDrawnMs.p95 <= 50.0), s.pageToDrawnMs.pairs())
    report.line("L3-script-to-drawn", Verdict.INFO, s.stimulusToDrawnMs.pairs())
    report.line("L4-page-to-capture", Verdict.INFO, s.pageToArriveMs.pairs() + s.renderToArriveMs.pairs())
    report.check("L5-every-change-drawn", s.unmatched == 0, "unmatched" to s.unmatched, "changes" to scripted.size)

    val roundTrip = Samples("round_trip_ms")
    repeat(200) {
        val start = System.nanoTime()
        web.call("clock")
        roundTrip.add((System.nanoTime() - start) / 1e6)
    }
    report.line("L6-script-round-trip", Verdict.INFO, roundTrip.pairs())

    val hit = pageRectOnScreen("hit")
    moveTo(hit.x + hit.width / 2, hit.y + hit.height / 2)
    delay(200)
    resetTimelines()
    val pressSince = pageClock()
    // System.nanoTime -> the epoch clock the page's event times use (both clocks read back to back).
    val epochOffsetNs = WinNative.epochMicros() * 1_000 - System.nanoTime()
    val pressed = ArrayList<Stimulus>()
    repeat(40) { i ->
        val marker = 5_000 + i
        web.call("markNextPress", marker)
        pressed += Stimulus(marker, System.nanoTime())
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        delay(600)  // past the double-click interval, so every press is a plain press
    }
    delay(500)
    val p = LatencyMatcher.match(takeMarks(), drainArrivals(), web.buffer.takeDraws(), pressed)
    report.line("L7-input-to-drawn", Verdict.INFO, p.stimulusToDrawnMs.pairs() + listOf("unmatched" to p.unmatched))
    // L8: where L7 goes. Press -> the page's pointerdown is the input path (Robot, AWT, Compose, SendMouseInput,
    // the browser); the rest of L7 is the page's next frame, its capture and Compose's draw.
    val downs = pageEvents(pressSince).filter { it.type == "pointerdown" && it.id == "hit" }.map { it.t }
    val toPage = Samples("press_to_pointerdown_ms")
    pressed.zip(downs).forEach { (s, t) -> toPage.add(t - (s.sentNs + epochOffsetNs) / 1e6) }
    report.line("L8-press-to-pointerdown", Verdict.INFO, toPage.pairs() + listOf("pointerdowns" to downs.size))
    // L9: from the frame the page drew for the press to Compose drawing it (capture and Compose only).
    report.line("L9-press-frame-to-drawn", Verdict.INFO, p.pageToDrawnMs.pairs())
}

/**
 * Frame rates and per-frame cost while the page animates (C2, C3, C5): `full` repaints every pixel each frame, `small`
 * only a 64×64 spinner. The surface's size comes from the run: the window, or -Dwv2.surfaceWidth/-Dwv2.surfaceHeight.
 */
suspend fun Harness.fps() {
    requireFrames()
    for (mode in listOf("full", "small")) {
        web.call("animate", "off")
        delay(500)
        resetTimelines()
        val statsBefore = captureStats()
        val intervals = Samples("compose_frame_ms")
        val pacing = app.scope.launch(Dispatchers.Main) {
            var last = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (last != 0L) intervals.add((now - last) / 1e6)
                    last = now
                }
            }
        }
        web.call("animate", mode)
        delay(10_000)
        val raf = web.call("rafStats")
        web.call("animate", "off")
        pacing.cancelAndJoin()
        delay(500)
        val arrivals = drainArrivals()
        val draws = web.buffer.takeDraws()
        val rates = FrameRates.compute(takeMarks(), arrivals, draws, seconds = 10.0)
        val statsAfter = captureStats()
        val copy = Samples("copy_ms").apply { arrivals.forEach { add(it.copyNs / 1e6) } }
        val upload = Samples("upload_ms").apply { draws.forEach { add(it.uploadNs / 1e6) } }
        val size = arrivals.lastOrNull()?.let { "${it.width}x${it.height}" } ?: "none"
        val pageFps = raf.field("fps").double()
        val label = "$mode-$size"
        val copied = (statsAfter[1] - statsBefore[1]).coerceAtLeast(1)
        report.info(
            "FPS-$label",
            "page_fps" to pageFps, "capture_fps" to rates.captureFps, "drawn_fps" to rates.drawnFps,
            "page_frames" to rates.pageFrames, "missed_by_capture" to rates.missedByCapture,
            "not_drawn" to rates.notDrawn, "dirty_px_per_frame" to (statsAfter[6] - statsBefore[6]) / copied,
            "full_copies" to statsAfter[7] - statsBefore[7], "capture" to web.captureStartedText,
        )
        report.line(
            "FPS-$label-delivery", verdict(rates.captured >= 0.95 * rates.pageFrames),
            listOf("captured" to rates.captured, "page_frames" to rates.pageFrames),
        )
        report.line(
            "FPS-$label-60", if (pageFps < 57.0) Verdict.INCONCLUSIVE else verdict(rates.drawnFps >= 57.0),
            listOf("drawn_fps" to rates.drawnFps, "page_fps" to pageFps),
        )
        report.line("COPY-$label", verdict(copy.p95 + upload.p95 <= 4.0), copy.pairs() + upload.pairs())
        // Where the copy and the upload go: GPU -> staging and the CPU buffer's lock on the capture thread; the pull
        // into the Skia bitmap and the drawImage call on the UI thread.
        val read = Samples("read_ms").apply { arrivals.forEach { add(it.readNs / 1e6) } }
        val lockWait = Samples("lock_ms").apply { arrivals.forEach { add(it.lockNs / 1e6) } }
        val pull = Samples("pull_ms").apply { draws.forEach { add(it.pullNs / 1e6) } }
        val drawCall = Samples("draw_call_ms").apply { draws.forEach { add(it.drawCallNs / 1e6) } }
        report.line("COPY-PARTS-$label", Verdict.INFO, read.pairs() + lockWait.pairs() + pull.pairs() + drawCall.pairs())
        report.line("PACING-$label", Verdict.INFO, intervals.pairs())
    }
}
