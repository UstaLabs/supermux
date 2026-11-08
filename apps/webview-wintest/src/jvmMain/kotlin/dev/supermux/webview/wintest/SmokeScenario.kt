package dev.supermux.webview.wintest

import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * Does capture work at all? WebView2 in visual hosting, captured off screen, drawn by Compose: the first frame, the
 * marker at the frame's origin, the clocks, the viewport, no frames while idle, no blank frames, the window tree.
 */
suspend fun Harness.smoke() {
    val firstFrame = waitUntil(10_000) { web.buffer.seq > 0 }
    val afterNavigationMs = if (firstFrame) (web.buffer.firstFrameNs - web.navigationCompletedNs) / 1e6 else Double.NaN
    report.check(
        "S1-first-frame", firstFrame && afterNavigationMs <= 1_000.0,
        "ms_after_navigation" to afterNavigationMs, "capture" to web.captureStartedText,
        "frame" to "${web.buffer.width}x${web.buffer.height}",
    )
    if (!firstFrame) {
        report.note("window-tree", WinNative.windowTree(WinNative.parentHwnd()))
        return
    }

    web.call("setMarker", 0x5A5A)
    val markerSeen = waitUntil(3_000) { web.buffer.marker == 0x5A5A }
    report.check("S2-marker-at-frame-origin", markerSeen, "marker" to web.buffer.marker)

    val before = System.nanoTime()
    val native = WinNative.nowNanos()
    val after = System.nanoTime()
    val offsetUs = (native - (before + after) / 2) / 1_000.0
    report.check("S3-native-clock-is-nanoTime", abs(offsetUs) < 200.0, "offset_us" to offsetUs)

    val surface = app.surfaceInWindow
    val viewport = web.call("viewport").list().map { it.double() }
    val viewportPx = "${(viewport[0] * viewport[2]).toInt()}x${(viewport[1] * viewport[2]).toInt()}"
    val surfacePx = "${surface.width}x${surface.height}"
    val framePx = "${web.buffer.width}x${web.buffer.height}"
    report.check(
        "S4-viewport-is-surface", viewportPx == surfacePx && framePx == surfacePx,
        "viewport_px" to viewportPx, "surface" to surfacePx, "frame" to framePx,
    )

    delay(500)
    val arrivedBefore = captureStats()[0]
    delay(2_000)
    val idleFrames = captureStats()[0] - arrivedBefore
    report.check("S5-no-frames-while-idle", idleFrames <= 1, "frames_in_2s" to idleFrames)
    report.check("S6-no-blank-frames", captureStats()[3] == 0L, "blank" to captureStats()[3])

    report.info("S7-webgl", "renderer" to web.call("gpu").str())
    val tree = WinNative.windowTree(WinNative.parentHwnd())
    report.note("window-tree", tree + "\n--- browser top-level windows ---\n" + WinNative.topLevelWindows(WinNative.browserPid()))
    report.info(
        "S8-window-tree",
        "children" to tree.lines().count { it.trimStart().startsWith("child") },
        "chrome_children" to tree.lines().count { "Chrome_" in it },
    )
    report.info("S9-screenshot", "file" to screenshot("smoke").name)
}
