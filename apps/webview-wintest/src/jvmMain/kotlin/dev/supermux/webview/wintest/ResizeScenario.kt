package dev.supermux.webview.wintest

import java.awt.Dimension
import java.awt.event.InputEvent
import kotlinx.coroutines.delay

/**
 * Resize (C9, spec §7): during a live resize the last frame is drawn scaled, and the engine is resized once, after the
 * size settles. Four programmatic drags (repeatable), then one real drag of the border through Windows' sizing loop.
 */
suspend fun Harness.resize() {
    requireFrames()
    web.call("animate", "off")
    val base = onUi { app.window.size }
    val targets = listOf(
        Dimension(base.width - 240, base.height - 160), base,
        Dimension(base.width - 120, base.height - 60), base,
    )
    val settle = Samples("new_size_frame_ms")
    var resizedMidDrag = 0
    var viewportMismatches = 0
    val scaledBefore = web.buffer.scaledDraws
    val blankBefore = captureStats()[3]
    for (target in targets) {
        val from = onUi { app.window.size }
        val startSurface = app.surfaceInWindow
        app.appliedBounds.clear()
        resetTimelines()
        val dragStart = System.nanoTime()
        for (step in 1..30) {
            val w = from.width + (target.width - from.width) * step / 30
            val h = from.height + (target.height - from.height) * step / 30
            onUi { app.window.setSize(w, h) }
            delay(20)
        }
        val dragEnd = System.nanoTime()
        resizedMidDrag += app.appliedBounds.count { (at, r) -> at in dragStart..dragEnd && !r.sameSize(startSurface) }
        val arrivals = ArrayList<Arrival>()
        var applied: Pair<Long, BoundsDebouncer.Rect>? = null
        val settled = waitUntil(3_000, 10) {
            arrivals += drainArrivals()
            applied = app.appliedBounds.lastOrNull { it.first > dragEnd }
            val candidate = applied
            candidate != null && arrivals.any {
                it.arriveNs > candidate.first && it.width == candidate.second.width && it.height == candidate.second.height
            }
        }
        val done = applied
        if (settled && done != null) {
            val first = arrivals.first {
                it.arriveNs > done.first && it.width == done.second.width && it.height == done.second.height
            }
            settle.add((first.arriveNs - done.first) / 1e6)
            val viewport = web.call("viewport").list().map { it.double() }
            val viewportWidth = (viewport[0] * viewport[2]).toInt()
            val viewportHeight = (viewport[1] * viewport[2]).toInt()
            if (viewportWidth != done.second.width || viewportHeight != done.second.height) viewportMismatches++
        }
        delay(300)
    }
    val scaledDraws = web.buffer.scaledDraws - scaledBefore
    report.check("R1-engine-not-resized-mid-drag", resizedMidDrag == 0, "size_changes_during_drags" to resizedMidDrag)
    report.check("R2-scaled-frame-during-drag", scaledDraws > 0, "scaled_draws" to scaledDraws)
    report.line(
        "R3-new-size-frame-after-settle", verdict(settle.count == targets.size && settle.p95 <= 200.0), settle.pairs(),
    )
    report.check(
        "R4-viewport-follows", viewportMismatches == 0 && settle.count == targets.size,
        "mismatches" to viewportMismatches, "settled" to settle.count,
    )
    report.check("R5-no-blank-frames", captureStats()[3] == blankBefore, "blank" to captureStats()[3] - blankBefore)

    // R6: one real drag of the window's bottom-right corner, through Windows' modal sizing loop (informational).
    val bounds = onUi { app.window.bounds }
    robot.mouseMove(bounds.x + bounds.width - 4, bounds.y + bounds.height - 4)
    delay(200)
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    delay(100)
    for (step in 1..30) {
        robot.mouseMove(bounds.x + bounds.width - 4 - step * 5, bounds.y + bounds.height - 4 - step * 3)
        delay(20)
    }
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    delay(1_000)
    val after = onUi { app.window.size }
    report.info(
        "R6-real-border-drag",
        "before" to "${bounds.width}x${bounds.height}", "after" to "${after.width}x${after.height}",
        "frame" to "${web.buffer.width}x${web.buffer.height}",
        "surface" to "${app.surfaceInWindow.width}x${app.surfaceInWindow.height}",
        "blank" to captureStats()[3] - blankBefore, "shot" to screenshot("after-border-drag").name,
    )
}
