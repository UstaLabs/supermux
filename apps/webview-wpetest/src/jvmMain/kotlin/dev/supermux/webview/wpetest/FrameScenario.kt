package dev.supermux.webview.wpetest

import dev.supermux.webview.wpetest.Verdict.FAIL
import dev.supermux.webview.wpetest.Verdict.INCONCLUSIVE
import dev.supermux.webview.wpetest.Verdict.INFO
import dev.supermux.webview.wpetest.Verdict.PASS
import kotlinx.coroutines.delay

/**
 * F1–F5 (F6 is this scenario with -Dwpe.cpu=true): a full-window animation's frames, delivered and drawn; the copy
 * into the Skia bitmap (full frame and a 256x256 change); the GL upload of the same on the same driver; §10's bar.
 * At 1080p: lab.sh run frames -Dwpe.width=1920 -Dwpe.height=1080.
 */
suspend fun frames(lab: Lab) {
    val s = "frames"
    val r = lab.report
    val o = lab.options
    val res = "${o.width}x${o.height}"
    val view = lab.host.create(o.width, o.height, 1.0)
    view.configure()
    view.loadLab()
    lab.show(view)
    check(lab.waitUntil(10_000) { lab.stage.frames.draws.isNotEmpty() }) { "no frame drawn in 10 s" }
    var fullCopyP95 = Double.NaN
    for (mode in listOf("full", "box")) {
        // Pull mode, as the engine runs: WebKit renders the next frame once Compose has pulled the last one.
        view.eval("__labPage.setMode('$mode')")
        delay(1_000)
        view.drain()
        lab.stage.frames.pulls.clear()
        lab.stage.frames.draws.clear()
        delay(o.seconds * 1_000L)
        val records = view.drain()
        val pulls = lab.stage.frames.pulls.toList()
        val draws = lab.stage.frames.draws.toList()
        val delivered = Timeline.rate(records.map { it.arrivalNs })
        val drawn = Timeline.rate(draws.map { it.atNs })
        val lost = Timeline.missedPageFrames(records.mapNotNull { Markers.counter(it.marker) })
        val copy = Samples("copy_ms")
        pulls.forEach { copy.add(it.copyNs / 1e6) }
        val copied = Samples("copied_px")
        pulls.forEach { copied.add(it.pixels.toDouble()) }
        val intervals = Samples("draw_interval_ms")
        Timeline.intervalsMs(draws.map { it.atNs }).forEach { intervals.add(it) }
        r.line(s, "F1-delivered-$mode-pull", fpsVerdict(delivered),
            listOf("res" to res, "fps" to delivered, "drawn_fps" to drawn, "page_frames_lost" to lost) + o.variant)
        r.line(s, "F2-copy-$mode", INFO, listOf("res" to res) + copy.pairs() + copied.pairs())
        r.line(s, "F5-pacing-$mode", INFO, listOf("res" to res, "drawn_fps" to drawn) + intervals.pairs())
        if (mode == "full") fullCopyP95 = copy.p95

        // Auto mode: each buffer acknowledged as it arrives, so this is WebKit's own rate on this machine.
        WpeNative.frameMode(view.id, true, false, o.damageScale)
        delay(500)
        view.drain()
        delay(o.seconds * 1_000L)
        val auto = view.drain()
        val autoFps = Timeline.rate(auto.map { it.arrivalNs })
        r.line(s, "F1-delivered-$mode-auto", fpsVerdict(autoFps), listOf("res" to res, "fps" to autoFps,
            "page_frames_lost" to Timeline.missedPageFrames(auto.mapNotNull { Markers.counter(it.marker) })) + o.variant)
        WpeNative.frameMode(view.id, false, false, o.damageScale)
    }
    view.eval("__labPage.setMode('idle')")

    // F3: the GL upload of the same sizes on the same Mesa driver (glTexSubImage2D + glFinish).
    val bench = LongArray(6)
    val renderer = WpeNative.uploadBench(o.width, o.height, 256, 256, 120, bench)
    val uploadP95 = bench[1] / 1e6
    r.line(s, "F3-upload", if (renderer != null) INFO else FAIL, "res" to res, "gl" to renderer,
        "full_p50_ms" to bench[0] / 1e6, "full_p95_ms" to uploadP95, "dirty256_p50_ms" to bench[2] / 1e6,
        "dirty256_p95_ms" to bench[3] / 1e6, "bgra" to bench[4], "error" to bench[5])

    // F4: §10's bar, copy + upload at full frame. Over it only because the GL renders in software: INCONCLUSIVE.
    val sum = fullCopyP95 + uploadP95
    val software = renderer?.let { it.contains("llvmpipe", ignoreCase = true) || it.contains("softpipe", ignoreCase = true) } == true
    val f4 = when {
        sum <= 4.0 -> PASS
        fullCopyP95 <= 4.0 && software -> INCONCLUSIVE
        else -> FAIL
    }
    r.line(s, "F4-copy-plus-upload", f4, "res" to res, "copy_p95_ms" to fullCopyP95, "upload_p95_ms" to uploadP95, "sum_ms" to sum, "gl" to renderer)
    view.destroy()
}

/** 60 fps sustained (§10) is ≥ 57. Below it is INCONCLUSIVE in the lab, which renders in software; never a FAIL alone. */
private fun fpsVerdict(fps: Double): Verdict = when {
    fps.isNaN() -> FAIL
    fps >= 57.0 -> PASS
    else -> INCONCLUSIVE
}
