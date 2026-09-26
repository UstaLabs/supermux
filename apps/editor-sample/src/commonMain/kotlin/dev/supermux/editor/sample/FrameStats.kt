package dev.supermux.editor.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Frame times for the overlay and the in-app benchmark: each frame's WORK (from the start of the
 * Compose frame to the end of the editor's draw) and the latency from the last document change to
 * the paint that shows it.
 */
@Stable
class FrameStats(private val capacity: Int = 240) {
    fun now(): Double = platformNowMs()

    private val work = ArrayList<Double>()
    private var frameStart = -1.0
    private var pendingChange = -1.0

    /** Draws so far (the benchmark waits on it). */
    var draws = 0
        private set
    var lastDrawEnd = 0.0
        private set
    /** The work of the last painted frame (frame start -> paint end), ms. */
    var lastWork = 0.0
        private set

    /** Shown by the overlay; refreshed a few times a second, not every frame. */
    var summary: String by mutableStateOf("")
        private set
    private var lastSummary = 0.0

    /** Every keystroke latency measured so far (document change -> paint), ms. */
    val keystrokes = ArrayList<Double>()

    fun frameStart() { frameStart = now() }

    /** Key event -> the paint that shows its edit, ms (the benchmark's typing phase). */
    val inputLatencies = ArrayList<Double>()
    /** Key event -> the document change it made, ms. */
    val inputToChange = ArrayList<Double>()
    /** Changes made synchronously inside a key event's dispatch. */
    var changesInsideKeyEvent = 0
    private var pendingInput = -1.0
    private var lastInputSeen = -1.0

    /** A document change happened; the next paint closes its latency (and the key event's that caused it). */
    fun changed() {
        if (insideKeyEvent()) changesInsideKeyEvent++
        if (pendingChange < 0) pendingChange = now()
        val input = lastInputEventMs()
        if (input > lastInputSeen) { lastInputSeen = input; pendingInput = input; inputToChange += now() - input }
    }

    fun drawEnd() {
        val t = now()
        draws++
        lastDrawEnd = t
        if (frameStart >= 0) {
            lastWork = t - frameStart
            work += t - frameStart
            if (work.size > capacity) work.removeAt(0)
            frameStart = -1.0
        }
        if (pendingInput >= 0) {
            inputLatencies += t - pendingInput
            pendingInput = -1.0
        }
        if (pendingChange >= 0) {
            keystrokes += t - pendingChange
            if (keystrokes.size > 2000) keystrokes.removeAt(0)
            pendingChange = -1.0
        }
        if (t - lastSummary > 250) {
            lastSummary = t
            summary = "frame work p50 ${fmt(pct(work, 50))} p95 ${fmt(pct(work, 95))} ms" +
                (if (keystrokes.isEmpty()) "" else " · edit->paint p95 ${fmt(pct(keystrokes.takeLast(200), 95))} ms")
        }
    }

    /** The frame work times recorded so far (the last [capacity]). */
    fun workTimes(): List<Double> = work.toList()

    fun resetWork() { work.clear() }

    companion object {
        fun pct(xs: List<Double>, p: Int): Double = if (xs.isEmpty()) 0.0 else xs.sorted()[(xs.size * p / 100).coerceAtMost(xs.size - 1)]
        fun fmt(d: Double): String = ((d * 10).toLong() / 10.0).toString()
    }
}
