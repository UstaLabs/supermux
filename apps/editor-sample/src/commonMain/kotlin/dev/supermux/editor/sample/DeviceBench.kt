package dev.supermux.editor.sample

import androidx.compose.runtime.withFrameNanos
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.TransactionSpec
import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * The device pass's benchmark (spec §6.6), run in the app on a phone or tablet: settings → "device
 * bench", or launched with it (Android: `am start … --ez bench true`; iOS: the `-bench` launch
 * argument). It needs nobody at the device:
 * - open the 10k-line file: from the switch to its first painted frame;
 * - flings through it (programmatic, iOS's normal deceleration, alternating down and up): the frame
 *   interval (the display's pace: 8.3 ms at 120 Hz) and each frame's work;
 * - keystrokes in the middle of it through `EditorView.typeText` (the entry point the soft
 *   keyboard's field edits reach): dispatch to the painted frame;
 * - the same flings in the M3c demo (gutter markers, fold arrows, a review-thread widget).
 * The result is one line of JSON: printed as `M3C-BENCH {...}` (the device console / logcat) and
 * shown in the status line.
 */
suspend fun runDeviceBench(stats: FrameStats, open: suspend (SampleFile) -> Pair<Double, EditorView>): String {
    val (openMs, tenK) = open(SampleFile.KOTLIN_10K)
    repeat(30) { withFrameNanos { } }
    val fling10k = flings(tenK, stats)
    val keys = keystrokes(tenK, stats)
    val (_, demo) = open(SampleFile.DEMO)
    repeat(30) { withFrameNanos { } }
    val flingDemo = flings(demo, stats)
    fun f(d: Double) = FrameStats.fmt(d)
    fun dist(xs: List<Double>) = """{"n":${xs.size},"p50":${f(FrameStats.pct(xs, 50))},"p95":${f(FrameStats.pct(xs, 95))},"max":${f(xs.maxOrNull() ?: 0.0)}}"""
    fun fling(r: Fling): String {
        val vsync = FrameStats.pct(r.intervals, 50)
        val dropped = r.intervals.count { it > vsync * 1.5 }
        return """{"hz":${f(1000.0 / vsync)},"interval":${dist(r.intervals)},"work":${dist(r.work)},"dropped":$dropped}"""
    }
    return """{"open10kMs":${f(openMs)},"fling10k":${fling(fling10k)},"keystroke":${dist(keys)},"flingDemo":${fling(flingDemo)}}"""
}

private class Fling(val intervals: List<Double>, val work: List<Double>)

/** Four flings (down, up, down, up) of [seconds] each from [v0] px/s, decelerating like iOS's normal rate. */
private suspend fun flings(view: EditorView, stats: FrameStats, v0: Float = 9000f, seconds: Double = 1.8): Fling {
    val intervals = ArrayList<Double>()
    val work = ArrayList<Double>()
    view.dispatch(TransactionSpec(selection = EditorSelection.cursor(0), scrollIntoView = true))
    repeat(10) { withFrameNanos { } }
    for (i in 0 until 4) {
        stats.resetWork()
        var v = if (i % 2 == 0) v0 else -v0
        var last = -1L
        var elapsed = 0.0
        while (elapsed < seconds) withFrameNanos { t ->
            if (last > 0) {
                val dtMs = (t - last) / 1e6
                intervals += dtMs
                elapsed += dtMs / 1000
                view.scrollState.scrollBy(0f, (v * dtMs / 1000).toFloat())
                v *= 0.998.pow(dtMs).toFloat()
            }
            last = t
        }
        withFrameNanos { }
        work += stats.workTimes()
    }
    return Fling(intervals, work)
}

/** 200 keystrokes (after 30 of warm-up) in the middle of the document, each timed from its dispatch to the frame showing it. */
private suspend fun keystrokes(view: EditorView, stats: FrameStats): List<Double> {
    val doc = view.state.doc
    view.dispatch(TransactionSpec(selection = EditorSelection.cursor(doc.lineStart(doc.lineCount / 2) + 4), scrollIntoView = true))
    repeat(20) { withFrameNanos { } }
    val out = ArrayList<Double>()
    val random = kotlin.random.Random(7)
    repeat(230) { i ->
        delay(random.nextLong(0, 17))
        val before = stats.draws
        val t0 = stats.now()
        if (i % 8 == 7) {
            val at = view.state.selection.main.head
            view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at - 1, at)), scrollIntoView = true, userEvent = "delete.backward"))
        } else view.typeText("x")
        while (stats.draws == before) withFrameNanos { }
        if (i >= 30) out += stats.lastDrawEnd - t0
        repeat(3) { withFrameNanos { } }
    }
    return out
}
