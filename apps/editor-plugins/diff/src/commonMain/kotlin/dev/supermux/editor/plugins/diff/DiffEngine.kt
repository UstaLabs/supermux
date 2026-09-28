package dev.supermux.editor.plugins.diff

import dev.supermux.editor.core.LineMapping
import dev.supermux.editor.core.Rope
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * A changed run: A's 0-based lines [aFrom, aTo) stand where B's [bFrom, bTo) are (either may be
 * empty: a pure insertion into B, a pure deletion from A). A is the base, B the working copy.
 *
 * [chars]: the character changes inside the run (both sides non-empty), each at offsets RELATIVE to
 * the run's text on its side (its lines joined by `\n`, from the first line's start), so an edit
 * elsewhere never moves them; null when not computed (a pure insertion or deletion, a run over the
 * cost cap, a coarse run): the lines are shown changed as a whole.
 */
data class DiffHunk(val aFrom: Int, val aTo: Int, val bFrom: Int, val bTo: Int, val chars: List<CharChange>? = null) {
    val isInsert: Boolean get() = aFrom == aTo
    val isDelete: Boolean get() = bFrom == bTo
    val isChange: Boolean get() = aFrom < aTo && bFrom < bTo
}

/** A's text [aFrom, aTo) was replaced by B's [bFrom, bTo) (either may be empty); offsets relative to a hunk's text. */
data class CharChange(val aFrom: Int, val aTo: Int, val bFrom: Int, val bTo: Int)

/**
 * A diff of two texts' lines: the [hunks] (sorted, never touching: at least one pair of equal lines
 * between two), and [lineMapping] (editor-core's, for linked side-by-side views). [coarse]: some
 * region hit the cost cap and is one hunk where a finer diff exists.
 */
class DiffResult(val hunks: List<DiffHunk>, val coarse: Boolean = false) {
    val lineMapping: LineMapping by lazy { hunks.toLineMapping() }
    override fun toString() = "DiffResult(${hunks.size} hunks${if (coarse) ", coarse" else ""})"
}

internal fun List<DiffHunk>.toLineMapping() = LineMapping(map { LineMapping.Hunk(it.aFrom, it.aTo, it.bFrom, it.bTo) })

/**
 * The engine's budgets.
 * - [maxCost]: Myers work (edit steps × region size) allowed per region between two anchors; past
 *   it the region is one coarse hunk (a text with no unique line and a huge edit distance never hangs).
 * - [charDiff]: diff the characters inside changed runs, for runs of at most [charMaxLines] lines and
 *   [charMaxChars] characters on each side, within [charMaxCost] of work.
 */
data class DiffOptions(
    val maxCost: Int = 2_000_000,
    val charDiff: Boolean = true,
    val charMaxLines: Int = 400,
    val charMaxChars: Int = 20_000,
    val charMaxCost: Int = 400_000,
)

/**
 * The line diff: patience first (the lines unique on both sides of a region anchor it, their
 * longest increasing run pairs them; git's `--patience`, readable diffs of code), Myers's O(ND) diff
 * inside the gaps between anchors and wherever no line is unique (the shortest edit script), with a
 * cost cap ([DiffOptions.maxCost]). Then the characters inside every changed run ([CharDiff]).
 * 10,000 lines with 1,000 changes: a few ms on the JVM (`DiffPerfTest`).
 */
object LineDiff {
    /** The lines of [doc] as the editor counts them (split at `\n`; "a\n" is two lines). */
    fun lines(doc: Rope): List<String> = lines(doc.toString())
    fun lines(text: String): List<String> = text.split('\n')

    /** [a] against [b] at once (the caller's thread). */
    fun diff(a: List<String>, b: List<String>, options: DiffOptions = DiffOptions()): DiffResult = runNow { compute(a, b, options) {} }

    /**
     * The same, calling [pause] every few thousand steps: the caller gives its thread back there (a
     * browser's event loop) or checks for cancellation.
     */
    suspend fun diffSliced(a: List<String>, b: List<String>, options: DiffOptions = DiffOptions(), pause: suspend () -> Unit): DiffResult =
        compute(a, b, options, pause)

    internal suspend fun compute(a: List<String>, b: List<String>, options: DiffOptions, pause: suspend () -> Unit): DiffResult {
        val t = Ticker(pause)
        val ids = HashMap<String, Int>()
        val ai = IntArray(a.size) { ids.getOrPut(a[it]) { ids.size } }
        if (t.due(a.size)) t.pause()
        val bi = IntArray(b.size) { ids.getOrPut(b[it]) { ids.size } }
        if (t.due(b.size)) t.pause()
        val d = LineDiffer(ai, bi, ids.size, options.maxCost, t)
        d.run(0, ai.size, 0, bi.size, 0)
        val hunks = ArrayList<DiffHunk>(d.out.size)
        for (h in d.out) hunks += withChars(a, b, h[0], h[1], h[2], h[3], options, t)
        return DiffResult(hunks, d.coarse)
    }

    /** [a]'s lines [a0, a1) against [b]'s [b0, b1): hunks in whole-text line numbers (the incremental re-diff). */
    internal suspend fun computeRange(a: List<String>, a0: Int, a1: Int, b: List<String>, b0: Int, b1: Int, options: DiffOptions, t: Ticker): Pair<List<DiffHunk>, Boolean> {
        val r = compute(a.subList(a0, a1), b.subList(b0, b1), options, t.pause)
        return r.hunks.map { it.copy(aFrom = it.aFrom + a0, aTo = it.aTo + a0, bFrom = it.bFrom + b0, bTo = it.bTo + b0) } to r.coarse
    }

    internal suspend fun withChars(a: List<String>, b: List<String>, a0: Int, a1: Int, b0: Int, b1: Int, options: DiffOptions, t: Ticker): DiffHunk {
        if (!options.charDiff || a0 == a1 || b0 == b1 || a1 - a0 > options.charMaxLines || b1 - b0 > options.charMaxLines) return DiffHunk(a0, a1, b0, b1)
        var la = a1 - a0 - 1
        for (i in a0 until a1) { la += a[i].length; if (la > options.charMaxChars) return DiffHunk(a0, a1, b0, b1) }
        var lb = b1 - b0 - 1
        for (i in b0 until b1) { lb += b[i].length; if (lb > options.charMaxChars) return DiffHunk(a0, a1, b0, b1) }
        val at = a.subList(a0, a1).joinToString("\n")
        val bt = b.subList(b0, b1).joinToString("\n")
        return DiffHunk(a0, a1, b0, b1, CharDiff.compute(at, bt, options.charMaxCost, t))
    }
}

/**
 * The character diff inside a changed run: words first (runs of letters, digits and `_`; runs of
 * spaces; any other character alone), then characters inside a replaced pair of short runs when
 * that finds more in common than not ("count" -> "counter" is "er" inserted; "foo" -> "bar" stays
 * one replacement), and changes split only by a little whitespace are merged into one.
 */
object CharDiff {
    /** The changes from [a] to [b], or null when the run is over the cost cap or rewritten through and through (over 70 % of both sides). */
    fun diff(a: String, b: String, maxCost: Int = DiffOptions().charMaxCost): List<CharChange>? = runNow { compute(a, b, maxCost, Ticker {}) }

    private const val REFINE_MAX = 64

    internal suspend fun compute(a: String, b: String, maxCost: Int, t: Ticker): List<CharChange>? {
        val ta = tokens(a)
        val tb = tokens(b)
        val ids = Interner(ta.size + tb.size)
        val ia = IntArray(ta.size - 1) { ids.id(a, ta[it], ta[it + 1]) }
        val ib = IntArray(tb.size - 1) { ids.id(b, tb[it], tb[it + 1]) }
        val runs = ArrayList<IntArray>()
        if (!Myers.diff(ia, 0, ia.size, ib, 0, ib.size, maxCost, t) { a0, a1, b0, b1 -> runs += intArrayOf(a0, a1, b0, b1) }) return null
        val out = ArrayList<CharChange>()
        for (r in runs) {
            val af = ta[r[0]]; val aTo = ta[r[1]]
            val bf = tb[r[2]]; val bTo = tb[r[3]]
            if (aTo > af && bTo > bf && aTo - af <= REFINE_MAX && bTo - bf <= REFINE_MAX) {
                val refined = refine(a, af, aTo, b, bf, bTo, t)
                if (refined != null) { out += refined; continue }
            }
            out += CharChange(af, aTo, bf, bTo)
        }
        val merged = merge(a, out)
        // A run rewritten through and through: marking nearly every character says nothing the
        // line tint does not (the few spaces and colons in common would only fragment the marks).
        val changedA = merged.sumOf { it.aTo - it.aFrom }
        val changedB = merged.sumOf { it.bTo - it.bFrom }
        if (changedA > a.length * REWRITTEN && changedB > b.length * REWRITTEN) return null
        return merged
    }

    /** More than this share of both sides changed: the run is rewritten, no character marks. */
    private const val REWRITTEN = 0.7

    /**
     * A replaced pair of short runs, grapheme by grapheme (the caret's rules: an emoji, its skin tone,
     * a flag or a letter with its accent is one unit, never split), when more than half of it is common.
     */
    private suspend fun refine(a: String, af: Int, aTo: Int, b: String, bf: Int, bTo: Int, t: Ticker): List<CharChange>? {
        val ga = graphemes(a, af, aTo)
        val gb = graphemes(b, bf, bTo)
        val ids = HashMap<String, Int>()
        val ca = IntArray(ga.size - 1) { ids.getOrPut(a.substring(ga[it], ga[it + 1])) { ids.size } }
        val cb = IntArray(gb.size - 1) { ids.getOrPut(b.substring(gb[it], gb[it + 1])) { ids.size } }
        val sub = ArrayList<CharChange>()
        var changed = 0
        Myers.diff(ca, 0, ca.size, cb, 0, cb.size, Int.MAX_VALUE, t) { a0, a1, b0, b1 ->
            sub += CharChange(ga[a0], ga[a1], gb[b0], gb[b1])
            changed += maxOf(a1 - a0, b1 - b0)
        }
        val common = maxOf(ca.size, cb.size) - changed
        return if (common * 2 >= maxOf(ca.size, cb.size)) sub else null
    }

    /** The grapheme boundaries of [s] from [from] to [to]: from, ..., to. */
    private fun graphemes(s: String, from: Int, to: Int): IntArray {
        val out = ArrayList<Int>()
        var i = from
        out += i
        while (i < to) { i = minOf(to, dev.supermux.editor.compose.Graphemes.next(s, i)); out += i }
        return out.toIntArray()
    }

    /** Changes separated only by up to 3 spaces (no line break) become one. */
    private fun merge(a: String, c: List<CharChange>): List<CharChange> {
        if (c.size < 2) return c
        val out = ArrayList<CharChange>(c.size)
        var cur = c[0]
        for (i in 1 until c.size) {
            val n = c[i]
            val gap = n.aFrom - cur.aTo
            if (gap <= 3 && (cur.aTo until n.aFrom).all { a[it] == ' ' || a[it] == '\t' }) cur = CharChange(cur.aFrom, n.aTo, cur.bFrom, n.bTo)
            else { out += cur; cur = n }
        }
        out += cur
        return out
    }

    /** Ids for text ranges, equal text equal id (open addressing; no substring per token). */
    private class Interner(expected: Int) {
        // At least twice the tokens: never more than half full, no growing.
        private val cap = maxOf(16, expected * 2).takeHighestOneBit() * 2
        private val keys = arrayOfNulls<String>(cap)
        private val from = IntArray(cap)
        private val to = IntArray(cap)
        private val ids = IntArray(cap)
        private var size = 0

        fun id(s: String, f: Int, t: Int): Int {
            var h = 0
            for (i in f until t) h = 31 * h + s[i].code
            var k = (h xor (h ushr 16)) and (cap - 1)
            while (true) {
                val key = keys[k] ?: break
                if (to[k] - from[k] == t - f && key.regionMatches(from[k], s, f, t - f)) return ids[k]
                k = (k + 1) and (cap - 1)
            }
            keys[k] = s; from[k] = f; to[k] = t; ids[k] = size
            return size++
        }
    }

    /** Token boundaries of [s]: 0, each token's end, ..., s.length. */
    private fun tokens(s: String): IntArray {
        var out = IntArray(minOf(s.length, 64) + 2)
        var n = 0
        fun add(v: Int) { if (n == out.size) out = out.copyOf(out.size * 2); out[n++] = v }
        add(0)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            var j: Int
            when {
                // A word: graphemes starting with a letter, digit or `_` (its accents come with it).
                isWord(c) -> { j = next(s, i); while (j < s.length && isWord(s[j])) j = next(s, j) }
                c == ' ' || c == '\t' -> { j = i + 1; while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++ }
                // Anything else is one grapheme (an emoji with its skin tone, a flag).
                else -> j = next(s, i)
            }
            add(j)
            i = j
        }
        return out.copyOf(n)
    }

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun next(s: String, i: Int) = maxOf(i + 1, dev.supermux.editor.compose.Graphemes.next(s, i))
}

/** Work counting: [pause] every [EVERY] steps (a slice's end, a cancellation check). */
internal class Ticker(val pause: suspend () -> Unit) {
    private var n = 0

    /**
     * Count [steps]; true when a pause is due (the caller then calls [pause]). Not a suspend
     * function itself: a suspend call per Myers step would allocate its continuation every time.
     */
    fun due(steps: Int = 1): Boolean {
        n += steps
        if (n < EVERY) return false
        n = 0
        return true
    }

    companion object { const val EVERY = 8_192 }
}

/** Run [block], which must not really suspend (every pause returns at once), on this thread. */
internal fun <T> runNow(block: suspend () -> T): T {
    var out: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
    return (out ?: error("the diff suspended in a synchronous call")).getOrThrow()
}

/** Patience over the whole range, Myers in the gaps; the hunks in [out] as [a0, a1, b0, b1]. */
private class LineDiffer(private val a: IntArray, private val b: IntArray, ids: Int, private val maxCost: Int, private val t: Ticker) {
    val out = ArrayList<IntArray>()
    var coarse = false

    // Per-id counts for the unique-line search, reset lazily by a generation stamp.
    private val stamp = IntArray(ids) { -1 }
    private val countA = IntArray(ids)
    private val countB = IntArray(ids)
    private val posB = IntArray(ids)
    private var gen = 0

    private fun emit(a0: Int, a1: Int, b0: Int, b1: Int) {
        if (a0 == a1 && b0 == b1) return
        val last = out.lastOrNull()
        if (last != null && last[1] == a0 && last[3] == b0) { last[1] = a1; last[3] = b1 } else out += intArrayOf(a0, a1, b0, b1)
    }

    suspend fun run(a0In: Int, a1In: Int, b0In: Int, b1In: Int, depth: Int) {
        var a0 = a0In; var a1 = a1In; var b0 = b0In; var b1 = b1In
        while (a0 < a1 && b0 < b1 && a[a0] == b[b0]) { a0++; b0++ }
        while (a1 > a0 && b1 > b0 && a[a1 - 1] == b[b1 - 1]) { a1--; b1-- }
        if (t.due(a0 - a0In + a1In - a1)) t.pause()
        if (a0 == a1 || b0 == b1) { emit(a0, a1, b0, b1); return }
        if (depth < MAX_DEPTH && (a1 - a0) + (b1 - b0) > PATIENCE_MIN) {
            val anchors = anchors(a0, a1, b0, b1)
            if (anchors === NOTHING_COMMON) { emit(a0, a1, b0, b1); return }
            if (anchors != null) {
                var pa = a0; var pb = b0
                var k = 0
                while (k < anchors.size) {
                    val i = anchors[k]; val j = anchors[k + 1]
                    run(pa, i, pb, j, depth + 1)
                    pa = i + 1; pb = j + 1
                    k += 2
                }
                run(pa, a1, pb, b1, depth + 1)
                return
            }
        }
        val ok = Myers.diff(a, a0, a1, b, b0, b1, maxCost, t) { x0, x1, y0, y1 -> emit(x0, x1, y0, y1) }
        if (!ok) { coarse = true; emit(a0, a1, b0, b1) }
    }

    /** The lines unique on both sides of the region, paired by their longest increasing run: [i0, j0, i1, j1, ...]; null for none. */
    private suspend fun anchors(a0: Int, a1: Int, b0: Int, b1: Int): IntArray? {
        val g = gen++
        for (i in a0 until a1) { val id = a[i]; if (stamp[id] != g) { stamp[id] = g; countA[id] = 0; countB[id] = 0 }; countA[id]++ }
        for (j in b0 until b1) { val id = b[j]; if (stamp[id] != g) { stamp[id] = g; countA[id] = 0; countB[id] = 0 }; countB[id]++; posB[id] = j }
        if (t.due((a1 - a0) + (b1 - b0))) t.pause()
        // Candidates in A's order, their B positions: the longest increasing subsequence (patience sort).
        val candA = ArrayList<Int>()
        var common = false
        for (i in a0 until a1) { val id = a[i]; if (countB[id] > 0) { common = true; if (countA[id] == 1 && countB[id] == 1) candA += i } }
        if (!common) return NOTHING_COMMON
        if (candA.isEmpty()) return null
        val n = candA.size
        val js = IntArray(n) { posB[a[candA[it]]] }
        val tails = IntArray(n)
        val prev = IntArray(n)
        var len = 0
        for (x in 0 until n) {
            var lo = 0; var hi = len
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (js[tails[mid]] < js[x]) lo = mid + 1 else hi = mid }
            prev[x] = if (lo > 0) tails[lo - 1] else -1
            tails[lo] = x
            if (lo == len) len++
        }
        if (t.due(n)) t.pause()
        val out = IntArray(len * 2)
        var x = tails[len - 1]
        for (k in len - 1 downTo 0) { out[2 * k] = candA[x]; out[2 * k + 1] = js[x]; x = prev[x] }
        return out
    }

    companion object {
        /** No line of the region's A side is on its B side: it is one hunk, exactly. */
        val NOTHING_COMMON = IntArray(0)
        const val MAX_DEPTH = 24
        /** Regions this small go straight to Myers (the shortest edit script). */
        const val PATIENCE_MIN = 48
    }
}

/** Myers's greedy O(ND) diff, forward, with the trace kept for the way back. */
internal object Myers {
    /**
     * [x] [x0, x1) against [y] [y0, y1): every changed run to [emit] ([x0', x1', y0', y1'], in order);
     * false (nothing emitted) when the edit distance is beyond what [maxCost] allows for this size.
     */
    suspend fun diff(x: IntArray, x0: Int, x1: Int, y: IntArray, y0: Int, y1: Int, maxCost: Int, t: Ticker, emit: (Int, Int, Int, Int) -> Unit): Boolean {
        val n = x1 - x0
        val m = y1 - y0
        if (n == 0 && m == 0) return true
        if (n == 0 || m == 0) { emit(x0, x1, y0, y1); return true }
        val max = n + m
        val dMax = if (maxCost == Int.MAX_VALUE) max else minOf(max, maxOf(32, maxCost / max))
        val off = max + 1
        val v = IntArray(2 * max + 3)
        // trace[d] = V over k in [-d+1, d-1] before step d (after step d - 1).
        val trace = ArrayList<IntArray>()
        var found = -1
        loop@ for (d in 0..dMax) {
            trace += if (d == 0) IntArray(0) else v.copyOfRange(off - d + 1, off + d)
            var k = -d
            while (k <= d) {
                var xx = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
                var yy = xx - k
                val sx = xx
                while (xx < n && yy < m && x[x0 + xx] == y[y0 + yy]) { xx++; yy++ }
                v[off + k] = xx
                if (xx >= n && yy >= m) { found = d; break@loop }
                k += 2
                if (t.due(1 + xx - sx)) t.pause()
            }
        }
        if (found < 0) return false
        // Walk back: diagonal steps are matched pairs; everything else is changed.
        val match = IntArray(n) { -1 }
        var xx = n
        var yy = m
        for (d in found downTo 1) {
            val tr = trace[d]
            fun at(k: Int) = tr[k + d - 1]
            val k = xx - yy
            val prevK = if (k == -d || (k != d && at(k - 1) < at(k + 1))) k + 1 else k - 1
            val prevX = at(prevK)
            val prevY = prevX - prevK
            while (xx > prevX && yy > prevY) { xx--; yy--; match[xx] = yy }
            xx = prevX
            yy = prevY
        }
        while (xx > 0 && yy > 0) { xx--; yy--; match[xx] = yy }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            if (i < n && match[i] == j) { i++; j++; continue }
            var i2 = i
            while (i2 < n && match[i2] < 0) i2++
            val j2 = if (i2 < n) match[i2] else m
            emit(x0 + i, x0 + i2, y0 + j, y0 + j2)
            i = i2
            j = j2
        }
        return true
    }
}
