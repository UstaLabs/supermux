package dev.supermux.webview.wintest

import androidx.compose.runtime.withFrameNanos
import java.awt.event.InputEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scriptedArrivals = drainArrivals()
    val s = LatencyMatcher.match(takeMarks(), scriptedArrivals, web.buffer.takeDraws(), scripted)
    report.line("L1-capture-to-drawn", verdict(s.arriveToDrawnMs.p95 <= 20.0), s.arriveToDrawnMs.pairs())
    report.line("L2-page-to-drawn", verdict(s.pageToDrawnMs.p95 <= 50.0), s.pageToDrawnMs.pairs())
    report.line("L3-script-to-drawn", Verdict.INFO, s.stimulusToDrawnMs.pairs())
    report.line("L4-page-to-capture", Verdict.INFO, s.pageToArriveMs.pairs() + s.renderToArriveMs.pairs())
    report.check("L5-every-change-drawn", s.unmatched == 0, "unmatched" to s.unmatched, "changes" to scripted.size)
    // The smallest change there is: a static page whose 144x8 marker changes. What WGC reports dirty for it.
    report.line("DIRTY-scripted-marker", Verdict.INFO, dirtyPairs(scriptedArrivals))

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
    val pressStartNs = System.nanoTime()
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
    // L10: what the presses did to focus: MoveFocus calls (pressFocus=unfocused skips them while the page has focus) and
    // the page's own focusin/focusout events.
    val notes = app.focusLog.toList().filter { (it.substringBefore(' ').toLongOrNull() ?: 0L) >= pressStartNs }
    val pageFocus = pageEvents(pressSince)
    report.line(
        "L10-press-focus-churn", Verdict.INFO,
        listOf(
            "presses" to pressed.size, "move_focus_calls" to notes.count { "moveFocus reason" in it },
            "skipped" to notes.count { "no moveFocus" in it },
            "page_focusin" to pageFocus.count { it.type == "focusin" }, "page_focusout" to pageFocus.count { it.type == "focusout" },
        ),
    )
}

/**
 * Whether WGC's visual capture reports fine dirty regions at all, away from WebView2: native code captures a Composition
 * visual it draws itself while it changes a 144x8 rectangle of a surface, moves a 64x64 sprite, and redraws the whole
 * surface (native/dirty_probe.cpp). The size is the window-size surface's.
 */
suspend fun Harness.dirtyProbe() {
    requireFrames()
    val surface = app.surfaceInWindow
    val result = withContext(Dispatchers.IO) { WinNative.dirtyProbe(surface.width, surface.height, 40, 60) }
    for (phase in result.split("; ")) {
        val words = phase.split(' ')
        report.line("DP-${words.first()}", Verdict.INFO, words.drop(1).map { it.substringBefore('=') to it.substringAfter('=') })
    }
}

/** How fine WGC's dirty regions are: what each captured frame copied, and how often that was the whole frame. */
private fun dirtyPairs(arrivals: List<Arrival>): List<Pair<String, Any?>> {
    val dirtyKpx = Samples("dirty_kpx").apply { arrivals.forEach { add(it.dirtyPx / 1000.0) } }
    val rects = Samples("rects").apply { arrivals.forEach { add(it.rects.toDouble()) } }
    val whole = arrivals.count { it.width > 0 && it.dirtyPx >= it.width.toLong() * it.height }
    return dirtyKpx.pairs() + rects.pairs() + listOf("whole_frames" to whole, "frames" to arrivals.size)
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
        val cpuBefore = CpuSnapshot.take()
        val retriesBefore = web.buffer.stillDrawing
        web.call("animate", mode)
        delay(10_000)
        val cpuAfter = CpuSnapshot.take()
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
        // Chain 3 only: the pull's Map of the staging texture, the test's marker probe on the capture thread (not part
        // of the chain, so not in copy_ms), and pulls that found the GPU copy still running.
        val chain3Parts = if (!app.options.chain3) emptyList() else {
            val map = Samples("map_ms").apply { draws.forEach { add(it.mapNs / 1e6) } }
            val probe = Samples("probe_ms").apply { arrivals.forEach { add(it.probeNs / 1e6) } }
            map.pairs() + probe.pairs() + listOf("still_drawing" to web.buffer.stillDrawing - retriesBefore)
        }
        report.line(
            "COPY-PARTS-$label", Verdict.INFO,
            read.pairs() + lockWait.pairs() + pull.pairs() + drawCall.pairs() + chain3Parts,
        )
        report.line("PACING-$label", Verdict.INFO, intervals.pairs())
        report.line("DIRTY-$label", Verdict.INFO, dirtyPairs(arrivals))
        // CPU per drawn frame over the 10 s, the threads the timers above can't see included (skiko_render: Skia's
        // playback of the frame and the image's upload to the GPU; wv2_native: the capture copies).
        report.line("CPU-$label", Verdict.INFO, cpuAfter.perFrameSince(cpuBefore, draws.size))
    }
}
