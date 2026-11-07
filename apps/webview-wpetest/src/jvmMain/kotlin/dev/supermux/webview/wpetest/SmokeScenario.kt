package dev.supermux.webview.wpetest

import dev.supermux.webview.wpetest.Verdict.FAIL
import dev.supermux.webview.wpetest.Verdict.INFO
import dev.supermux.webview.wpetest.Verdict.PASS
import kotlinx.coroutines.delay

/** S1–S8: WebKit runs on our display, frames arrive as SHM with usable damage, Compose draws them, resize works. */
suspend fun smoke(lab: Lab) {
    val s = "smoke"
    val r = lab.report
    val o = lab.options
    r.line(s, "S1-init", if (lab.host.version.startsWith("2.54")) PASS else FAIL, listOf("wpe" to lab.host.version, "init_ms" to lab.host.initMs) + o.variant)

    // S2: a new view's first frame, with the page's marker in it.
    val t0 = System.nanoTime()
    val view = lab.host.create(o.width, o.height, 1.0)
    view.configure()
    view.loadLab()
    // Shown at once: in pull mode WebKit sends its next frame only after the surface has pulled the last one.
    lab.show(view)
    val first = view.awaitEvent(WpeNative.FRAME, t0, 10_000)
    val content = view.firstContentFrame(t0, 10_000)
    r.line(s, "S2-first-frame", if (first != null && content != null) PASS else FAIL,
        "first_frame_ms" to first?.let { (it.atNs - t0) / 1e6 }, "first_content_ms" to content?.let { (it.arrivalNs - t0) / 1e6 },
        "marker" to content?.let { Markers.counter(it.marker) })

    // S3: Compose draws it in place: the screen's pixel under the marker is the page's marker colour.
    val drawn = lab.waitUntil(5_000) { lab.stage.frames.draws.isNotEmpty() }
    delay(300)
    val expected = lab.counter(view)
    val pixel = lab.pixel(8, 8)
    r.line(s, "S3-drawn-in-place", if (drawn && Markers.counter(pixel.toLong()) == expected) PASS else FAIL,
        "pixel" to "0x%06x".format(pixel), "counter" to expected, "draws" to lab.stage.frames.draws.size)

    // S4: an idle page sends (almost) nothing; a 256x256 change sends a small damage.
    view.eval("__labPage.setMode('idle')")
    delay(1_000)
    view.drain()
    delay(2_000)
    val idle = view.drain().size
    view.eval("__labPage.setMode('box')")
    delay(2_000)
    val boxFrames = view.drain()
    val dirty = Samples("dirty_px")
    boxFrames.forEach { dirty.add(it.dirtyPx.toDouble()) }
    r.line(s, "S4-damage-size", if (idle <= 2 && boxFrames.isNotEmpty() && dirty.p95 <= 4.0 * 256 * 256) PASS else FAIL,
        listOf("idle_frames_2s" to idle, "box_frames" to boxFrames.size) + dirty.pairs())

    // S5: copying only the damage loses nothing: in verify mode no pixel changes outside the reported damage.
    for (scale in listOf(1.0, 2.0)) {
        WpeNative.frameMode(view.id, true, true, o.damageScale)
        WpeNative.setSize(view.id, (o.width / scale).toInt(), (o.height / scale).toInt(), scale)
        delay(800)
        view.drain()
        view.eval("__labPage.setMode('full')")
        delay(1_000)
        view.eval("__labPage.setMode('box')")
        delay(1_000)
        val records = view.drain()
        val outside = records.sumOf { it.outsidePx }
        r.line(s, "S5-damage-complete", if (records.size >= 30 && outside == 0L) PASS else FAIL,
            "scale" to scale, "frames" to records.size, "outside_px" to outside, "frames_with_outside" to records.count { it.outsidePx > 0 },
            "damage_scale" to o.damageScale)
        WpeNative.frameMode(view.id, false, false, o.damageScale)
    }
    WpeNative.setSize(view.id, o.width, o.height, 1.0)
    view.eval("__labPage.setMode('idle')")
    delay(500)

    // S6: a resize delivers a frame at the new size, with content.
    val t6 = System.nanoTime()
    WpeNative.setSize(view.id, 800, 600, 1.0)
    val resized = lab.waitUntil(3_000) { lab.stage.frames.width == 800 && lab.stage.frames.height == 600 }
    val ms6 = (System.nanoTime() - t6) / 1e6
    r.line(s, "S6-resize", if (resized && ms6 <= 500 && Markers.counter(lab.stage.frames.lastMarker) != null) PASS else FAIL,
        "ms" to ms6, "marker" to Markers.counter(lab.stage.frames.lastMarker))
    WpeNative.setSize(view.id, o.width, o.height, 1.0)

    // S7: the WebKit thread's own main context carries IPC, promises and page timers.
    val timers = view.call(
        "let n = 0; const t0 = performance.now(); " +
            "await new Promise(r => { const tick = () => { if (++n === 20) r(); else setTimeout(tick, 10); }; tick(); }); " +
            "return performance.now() - t0;",
    )
    val ms7 = timers?.toDoubleOrNull()
    r.line(s, "S7-main-context", if (ms7 != null && ms7 < 1_000) PASS else FAIL, "timers_ms" to ms7, "global_context" to o.globalContext)

    // S8: what this run ran on.
    val bench = LongArray(6)
    val renderer = WpeNative.uploadBench(256, 256, 16, 16, 3, bench)
    r.line(s, "S8-environment", INFO, listOf(
        "wpe" to lab.host.version, "java" to System.getProperty("java.version"), "skiko_api" to lab.window.renderApi,
        "gl" to renderer, "cpu_model" to cpuModel(), "cores" to Runtime.getRuntime().availableProcessors(), "load" to loadAverage(),
    ) + o.variant)
    view.destroy()
}
