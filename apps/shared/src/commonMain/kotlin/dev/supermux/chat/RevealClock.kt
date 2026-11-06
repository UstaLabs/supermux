package dev.supermux.chat

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Card #76 (B3, the ink edge): the live reply's display clock. Text arrives in lumps (50 ms batches, empty ticks,
 * bursts); this decides, frame by frame, how much of it is on screen and how opaque each character is.
 *
 * - The head moves toward what may be shown, covering `1 - exp(-dt / tau)` of the backlog each frame ([TAU_MS], and
 *   [DONE_TAU_MS] once the stream has ended), never slower than [FLOOR_CHARS_PER_SECOND].
 * - While the stream is open, the trailing run of letters may be half a word: it waits, unless it is longer than
 *   [LONG_RUN] or nothing new has arrived for [STALL_MS].
 * - The text is laid out to the end of the word the head is in. A character's opacity is the larger of its distance
 *   behind the head over [EDGE_CHARS] and its eased age on screen over [DRY_MS]; past the layout it is zero.
 * - Reduced motion: whole words only, every laid-out character opaque.
 */
class RevealClock {
    /** How far the reveal has got, in characters; continuous. */
    var head = 0.0
        private set

    /** How much of the text to lay out. */
    var laidOut = 0
        private set

    /** Another frame would change what is drawn: still moving, drying, or holding a trailing word for a pause. */
    var animating = false
        private set

    private var reduced = false
    private var lastNow = Double.NaN
    private var arrived = 0
    private var arrivedAt = 0.0

    /** Stretches laid out in the last [DRY_MS], oldest first: what the age term still fades. */
    private val fresh = ArrayDeque<Stretch>()

    private class Stretch(val from: Int, val to: Int, val at: Double)

    fun advance(nowMs: Double, text: String, done: Boolean, reduced: Boolean = false) {
        if (text.length < laidOut) reset()
        this.reduced = reduced
        val dt = if (lastNow.isNaN()) FRAME_MS else (nowMs - lastNow).coerceIn(0.0, MAX_DT_MS)
        lastNow = nowMs
        if (text.length != arrived) { arrived = text.length; arrivedAt = nowMs }
        val target = revealable(text, whole = done || nowMs - arrivedAt >= STALL_MS)
        if (head < target) {
            val gain = (target - head) * (1 - exp(-dt / if (done) DONE_TAU_MS else TAU_MS))
            head = min(target.toDouble(), head + max(gain, FLOOR_CHARS_PER_SECOND * dt / 1000))
        }
        val next = if (reduced) lastWordEnd(text, floor(head).toInt()) else min(wordEndAfter(text, ceil(head).toInt()), max(target, laidOut))
        if (next > laidOut) {
            if (!reduced) fresh.addLast(Stretch(laidOut, next, nowMs))
            laidOut = next
        }
        while (fresh.isNotEmpty() && (reduced || nowMs - fresh.first().at >= DRY_MS)) fresh.removeFirst()
        animating = head < target || fresh.isNotEmpty() || target < text.length
    }

    /** How opaque the character at [index] draws at [nowMs]. */
    fun alphaAt(index: Int, nowMs: Double): Float {
        if (index >= laidOut) return 0f
        if (reduced) return 1f
        val stretch = fresh.lastOrNull { index >= it.from } ?: return 1f
        val behind = ((head - index) / EDGE_CHARS).coerceIn(0.0, 1.0)
        val age = ((nowMs - stretch.at) / DRY_MS).coerceIn(0.0, 1.0)
        return max(behind, easeOut(age)).toFloat()
    }

    /** The first character that is not fully opaque yet ([laidOut] when every one is). */
    fun firstUnsettled(): Int = fresh.firstOrNull()?.from ?: laidOut

    fun reset() {
        head = 0.0
        laidOut = 0
        animating = false
        lastNow = Double.NaN
        arrived = 0
        arrivedAt = 0.0
        fresh.clear()
    }

    companion object {
        const val TAU_MS = 150.0
        const val DONE_TAU_MS = 75.0
        const val FLOOR_CHARS_PER_SECOND = 30.0
        const val EDGE_CHARS = 24.0
        const val DRY_MS = 450.0
        const val STALL_MS = 300.0
        const val LONG_RUN = 32
        private const val FRAME_MS = 16.0
        private const val MAX_DT_MS = 64.0

        /**
         * How much of [text] may be shown: all of it when [whole]; otherwise up to its trailing run of letters, which
         * may be half a word, unless that run is longer than [LONG_RUN] (a long token, or a script without spaces).
         */
        fun revealable(text: String, whole: Boolean): Int {
            if (whole) return text.length
            var i = text.length
            while (i > 0 && !text[i - 1].isWhitespace()) i--
            return if (text.length - i > LONG_RUN) text.length else i
        }

        /** The end of the word [p] is inside; [p] itself at a word boundary. */
        private fun wordEndAfter(text: String, p: Int): Int {
            var i = p.coerceIn(0, text.length)
            if (i == 0 || text[i - 1].isWhitespace()) return i
            while (i < text.length && !text[i].isWhitespace()) i++
            return i
        }

        /** The end of the last whole word at or before [p]: never a fragment. */
        private fun lastWordEnd(text: String, p: Int): Int {
            var i = p.coerceIn(0, text.length)
            while (i > 0 && i < text.length && !text[i - 1].isWhitespace() && !text[i].isWhitespace()) i--
            return i
        }

        private fun easeOut(k: Double) = 1 - (1 - k) * (1 - k) * (1 - k)
    }
}
