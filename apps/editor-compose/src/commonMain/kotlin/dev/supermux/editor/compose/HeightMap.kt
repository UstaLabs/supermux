package dev.supermux.editor.compose

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.Rope

/**
 * Every line's height, in pixels, for the whole document: measured for lines the surface has laid
 * out, [estimatedLineHeight] for the rest. Lines are 0-based.
 *
 * A line's height is its TEXT height (one visual line per wrap) plus the block widgets above and
 * below it ([setBlockHeight], M3c). [top] is where the line's box starts, block above included.
 *
 * Structure: lines are kept in chunks of about [chunkTarget] heights, with two Fenwick trees over
 * the chunks (line counts and pixel sums), so [top] and [lineAt] are O(log chunks + chunk) and an
 * edit costs O(changed lines + log chunks); only a chunk split or merge rebuilds the trees (O(chunks)).
 * Sums are doubles: a million lines is ~20 million pixels, past float's exact range.
 *
 * Not thread-safe: the surface owns it and touches it on the UI thread only.
 */
class HeightMap(
    lineCount: Int,
    val estimatedLineHeight: Float,
    private val chunkTarget: Int = 512,
) {
    init {
        require(lineCount >= 0) { "negative line count" }
        require(chunkTarget >= 2) { "chunk target too small" }
    }

    private class Chunk(capacity: Int) {
        var n = 0
        var h = FloatArray(capacity)
        var above: FloatArray? = null
        var below: FloatArray? = null
        var sum = 0.0

        fun total(i: Int): Float {
            var t = h[i]
            above?.let { t += it[i] }
            below?.let { t += it[i] }
            return t
        }
    }

    private val chunkMax = chunkTarget * 2
    private var chunks = ArrayList<Chunk>()
    private var countTree = IntArray(1)
    private var sumTree = DoubleArray(1)

    var lineCount: Int = 0
        private set

    private var total = 0.0

    /** The document's height in pixels. */
    val totalHeight: Float get() = total.toFloat()

    init {
        chunks = buildChunks(lineCount) { estimatedLineHeight }
        rebuild()
    }

    /** Top of 0-based [line], in pixels from the document top (its block-above included). */
    fun top(line: Int): Float = topD(line).toFloat()

    /** [top] in double precision. */
    fun topD(line: Int): Double {
        if (lineCount == 0) return 0.0
        require(line in 0..lineCount) { "line $line out of 0..$lineCount" }
        if (line == lineCount) return total
        val (c, local) = locate(line)
        var y = prefixSum(c)
        val ch = chunks[c]
        for (i in 0 until local) y += ch.total(i)
        return y
    }

    /** The whole height of [line]: text plus its blocks. */
    fun height(line: Int): Float {
        val (c, local) = locate(line)
        return chunks[c].total(local)
    }

    /** The block height above [line]: its text starts that far below [top]. */
    fun blockAbove(line: Int): Float {
        val (c, local) = locate(line)
        return chunks[c].above?.get(local) ?: 0f
    }

    /** The block height below [line]'s text. */
    fun blockBelow(line: Int): Float {
        val (c, local) = locate(line)
        return chunks[c].below?.get(local) ?: 0f
    }

    /** [line]'s TEXT height (its box without the blocks). */
    fun textHeight(line: Int): Float {
        val (c, local) = locate(line)
        return chunks[c].h[local]
    }

    /** The line whose box contains [y], clamped to the document. */
    fun lineAt(y: Float): Int {
        if (lineCount == 0) return 0
        if (y <= 0f) return 0
        if (y >= total) return lineCount - 1
        // Descend the sum tree: the chunk holding y and the height above it.
        var pos = 0
        var rem = y.toDouble()
        var lines = 0
        var step = highestBit(chunks.size)
        while (step > 0) {
            val next = pos + step
            if (next <= chunks.size && sumTree[next] <= rem) {
                pos = next
                rem -= sumTree[next]
                lines += countTree[next]
            }
            step = step shr 1
        }
        if (pos >= chunks.size) return lineCount - 1
        val ch = chunks[pos]
        var acc = 0.0
        for (i in 0 until ch.n) {
            acc += ch.total(i)
            if (rem < acc) return lines + i
        }
        return minOf(lineCount - 1, lines + ch.n - 1)
    }

    /** Replace [line]'s TEXT height (its blocks stay). */
    fun setMeasured(line: Int, height: Float) {
        val (c, local) = locate(line)
        val ch = chunks[c]
        val delta = (height - ch.h[local]).toDouble()
        if (delta == 0.0) return
        ch.h[local] = height
        adjust(c, delta)
    }

    /** Block widgets above and below [line] (M3c); 0 by default. */
    fun setBlockHeight(line: Int, above: Float, below: Float) {
        val (c, local) = locate(line)
        val ch = chunks[c]
        val before = ch.total(local)
        if (above != 0f || ch.above != null) (ch.above ?: FloatArray(ch.h.size).also { ch.above = it })[local] = above
        if (below != 0f || ch.below != null) (ch.below ?: FloatArray(ch.h.size).also { ch.below = it })[local] = below
        adjust(c, (ch.total(local) - before).toDouble())
    }

    /**
     * Follow a document change: every line a change touches is replaced by estimates for the lines
     * that span its new text. [before] is the document [changes] applies to, [after] its result.
     */
    fun applyChanges(changes: ChangeSet, before: Rope, after: Rope) {
        if (changes.isEmpty) return
        // Back to front: an earlier change's line numbers in [before] are still valid when it runs.
        for (c in changes.iterChanges().asReversed()) {
            val start = before.lineIndexAt(c.fromA)
            val end = before.lineIndexAt(c.toA)
            val newLines = after.lineIndexAt(c.toB) - after.lineIndexAt(c.fromB) + 1
            replaceLines(start, end - start + 1, newLines)
        }
        check(lineCount == after.lineCount) { "height map has $lineCount lines, document ${after.lineCount}" }
    }

    /** Every line back to its estimate and no blocks (the font or the wrap width changed). */
    fun reset(lineCount: Int = this.lineCount) {
        chunks = buildChunks(lineCount) { estimatedLineHeight }
        rebuild()
    }

    // ------------------------------------------------------------------ internals --

    /** Remove [count] lines at [start] and insert [newCount] estimated lines there. */
    internal fun replaceLines(start: Int, count: Int, newCount: Int) {
        require(start >= 0 && count >= 0 && start + count <= lineCount) { "replace $start+$count of $lineCount" }
        if (count == 0 && newCount == 0) return
        if (chunks.isEmpty()) {
            chunks = buildChunks(newCount) { estimatedLineHeight }
            rebuild()
            return
        }
        // The common case, typing: one chunk holds the whole range and still fits afterwards.
        val (c, local) = locate(minOf(start, lineCount - 1)).let { (c, l) -> if (start == lineCount) c to chunks[c].n else c to l }
        val ch = chunks[c]
        if (local + count <= ch.n && ch.n - count + newCount in 1..chunkMax) {
            var removed = 0.0
            for (i in local until local + count) removed += ch.total(i)
            val tail = ch.n - local - count
            val newN = ch.n - count + newCount
            if (newN > ch.h.size) growChunk(ch, newN)
            ch.h.copyInto(ch.h, local + newCount, local + count, local + count + tail)
            ch.above?.let { it.copyInto(it, local + newCount, local + count, local + count + tail) }
            ch.below?.let { it.copyInto(it, local + newCount, local + count, local + count + tail) }
            for (i in local until local + newCount) {
                ch.h[i] = estimatedLineHeight
                ch.above?.set(i, 0f)
                ch.below?.set(i, 0f)
            }
            ch.n = newN
            lineCount += newCount - count
            val delta = newCount * estimatedLineHeight.toDouble() - removed
            ch.sum += delta
            total += delta
            fenwickAdd(countTree, c, newCount - count)
            fenwickAdd(sumTree, c, delta)
            return
        }
        // Across chunks (or a chunk overflowing / emptying): rebuild the chunks the range spans.
        val lastLine = start + count - 1
        val cEnd = if (count == 0) c else locate(lastLine).first
        val firstLineOfC = start - local
        val text = ArrayList<Float>(); val above = ArrayList<Float>(); val below = ArrayList<Float>()
        var line = firstLineOfC
        for (k in c..cEnd) {
            val chunk = chunks[k]
            for (i in 0 until chunk.n) {
                if (line == start) repeat(newCount) { text += estimatedLineHeight; above += 0f; below += 0f }
                if (line < start || line > lastLine) {
                    text += chunk.h[i]; above += chunk.above?.get(i) ?: 0f; below += chunk.below?.get(i) ?: 0f
                }
                line++
            }
        }
        if (line == start) repeat(newCount) { text += estimatedLineHeight; above += 0f; below += 0f }
        val rebuilt = buildChunks(text.size) { text[it] }
        var at = 0
        for (r in rebuilt) {
            for (i in 0 until r.n) {
                val a = above[at + i]; val b = below[at + i]
                if (a != 0f) (r.above ?: FloatArray(r.h.size).also { r.above = it })[i] = a
                if (b != 0f) (r.below ?: FloatArray(r.h.size).also { r.below = it })[i] = b
            }
            r.sum = (0 until r.n).sumOf { r.total(it).toDouble() }
            at += r.n
        }
        val next = ArrayList<Chunk>(chunks.size - (cEnd - c + 1) + rebuilt.size)
        for (k in 0 until c) next += chunks[k]
        next += rebuilt
        for (k in cEnd + 1 until chunks.size) next += chunks[k]
        chunks = next
        rebuild()
    }

    private fun growChunk(ch: Chunk, atLeast: Int) {
        val size = maxOf(atLeast, chunkMax)
        ch.h = ch.h.copyOf(size)
        ch.above = ch.above?.copyOf(size)
        ch.below = ch.below?.copyOf(size)
    }

    private fun buildChunks(n: Int, height: (Int) -> Float): ArrayList<Chunk> {
        val out = ArrayList<Chunk>(n / chunkTarget + 1)
        var i = 0
        while (i < n) {
            val len = minOf(chunkTarget, n - i)
            val ch = Chunk(chunkMax)
            var s = 0.0
            for (k in 0 until len) { val v = height(i + k); ch.h[k] = v; s += v }
            ch.n = len
            ch.sum = s
            out += ch
            i += len
        }
        return out
    }

    /** Both Fenwick trees and the totals from the chunks: O(chunks). */
    private fun rebuild() {
        val n = chunks.size
        countTree = IntArray(n + 1)
        sumTree = DoubleArray(n + 1)
        var lines = 0
        var sum = 0.0
        for (k in 0 until n) {
            countTree[k + 1] = chunks[k].n
            sumTree[k + 1] = chunks[k].sum
            lines += chunks[k].n
            sum += chunks[k].sum
        }
        // In-place O(n) Fenwick construction.
        for (i in 1..n) {
            val parent = i + (i and -i)
            if (parent <= n) {
                countTree[parent] += countTree[i]
                sumTree[parent] += sumTree[i]
            }
        }
        lineCount = lines
        total = sum
    }

    private fun adjust(c: Int, delta: Double) {
        chunks[c].sum += delta
        total += delta
        fenwickAdd(sumTree, c, delta)
    }

    /** Chunk index and index inside it of [line]. */
    private fun locate(line: Int): Pair<Int, Int> {
        require(line in 0 until lineCount) { "line $line out of 0 until $lineCount" }
        var pos = 0
        var rem = line
        var step = highestBit(chunks.size)
        while (step > 0) {
            val next = pos + step
            if (next <= chunks.size && countTree[next] <= rem) {
                pos = next
                rem -= countTree[next]
            }
            step = step shr 1
        }
        return pos to rem
    }

    /** Sum of the chunks before chunk [c]. */
    private fun prefixSum(c: Int): Double {
        var i = c
        var s = 0.0
        while (i > 0) { s += sumTree[i]; i -= i and -i }
        return s
    }

    private fun fenwickAdd(tree: IntArray, c: Int, delta: Int) {
        var i = c + 1
        while (i < tree.size) { tree[i] += delta; i += i and -i }
    }

    private fun fenwickAdd(tree: DoubleArray, c: Int, delta: Double) {
        var i = c + 1
        while (i < tree.size) { tree[i] += delta; i += i and -i }
    }

    private fun highestBit(n: Int): Int = if (n == 0) 0 else Int.MIN_VALUE ushr n.countLeadingZeroBits()
}
