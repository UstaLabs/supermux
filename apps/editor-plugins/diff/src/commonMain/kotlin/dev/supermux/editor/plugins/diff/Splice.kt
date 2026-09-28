package dev.supermux.editor.plugins.diff

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.Rope

/**
 * The incremental re-diff: after an edit of B, only the region it touches is diffed again. The
 * region is the edited lines, grown to every hunk they touch; its edges are equal lines that pair
 * on both sides, so the rest of the diff stands (the hunks before it unchanged, the hunks after it
 * moved by the edit's line delta; their character changes are relative to their run, so they move
 * for free). A region over [limitLines] is not diffed here (that is the background job's): it is
 * one coarse hunk until then ([Result.exact] false), which keeps the line mapping correct.
 */
internal object Splice {
    class Result(val hunks: List<DiffHunk>, val exact: Boolean)

    /** Lines a keystroke may re-diff on the UI thread (both sides together). */
    const val LIMIT_LINES = 4_000

    fun apply(
        base: List<String>,
        hunks: List<DiffHunk>,
        old: Rope,
        new: Rope,
        changes: ChangeSet,
        options: DiffOptions,
        limitLines: Int = LIMIT_LINES,
    ): Result {
        if (changes.isEmpty) return Result(hunks, true)
        var l0 = Int.MAX_VALUE
        var l1 = -1
        for (c in changes.iterChanges()) {
            l0 = minOf(l0, old.lineIndexAt(c.fromA))
            l1 = maxOf(l1, old.lineIndexAt(c.toA))
        }
        val delta = new.lineCount - old.lineCount
        // The hunks the edited lines [l0, l1] touch (a hunk ending right above or starting right below counts).
        var first = lowerBound(hunks) { it.bTo >= l0 }
        var last = first
        while (last < hunks.size && hunks[last].bFrom <= l1 + 1) last++
        var rb0 = l0
        var rb1 = l1 + 1
        if (last > first) {
            rb0 = minOf(rb0, hunks[first].bFrom)
            rb1 = maxOf(rb1, hunks[last - 1].bTo)
        } else first = last
        val ra0 = if (last > first && hunks[first].bFrom == rb0) hunks[first].aFrom else aLineOf(hunks, rb0)
        val ra1 = if (last > first && hunks[last - 1].bTo == rb1) hunks[last - 1].aTo else aLineOf(hunks, rb1)
        val nb1 = rb1 + delta
        val before = hunks.subList(0, first)
        val after = hunks.subList(last, hunks.size).map { it.copy(bFrom = it.bFrom + delta, bTo = it.bTo + delta) }
        val size = (ra1 - ra0) + (nb1 - rb0)
        if (size > limitLines) {
            val coarse = if (ra0 == ra1 && rb0 == nb1) emptyList() else listOf(DiffHunk(ra0, ra1, rb0, nb1))
            return Result(before + coarse + after, false)
        }
        val bLines = ArrayList<String>(nb1 - rb0)
        for (i in rb0 until nb1) bLines += lineText(new, i)
        val region = runNow { LineDiff.computeRange(base, ra0, ra1, bLines, 0, bLines.size, options, Ticker {}) }.first
            .map { it.copy(bFrom = it.bFrom + rb0, bTo = it.bTo + rb0) }
        return Result(before + region + after, true)
    }

    /** The A line paired with B's line [b] (an equal line, or a line count at the end). */
    fun aLineOf(hunks: List<DiffHunk>, b: Int): Int {
        val i = lowerBound(hunks) { it.bTo > b } - 1
        if (i < 0) return b
        val h = hunks[i]
        return b - h.bTo + h.aTo
    }

    /** The B line paired with A's line [a]. */
    fun bLineOf(hunks: List<DiffHunk>, a: Int): Int {
        val i = lowerBound(hunks) { it.aTo > a } - 1
        if (i < 0) return a
        val h = hunks[i]
        return a - h.aTo + h.bTo
    }

    /** The first index whose hunk satisfies [pred] (monotone: false... then true...). */
    inline fun lowerBound(hunks: List<DiffHunk>, pred: (DiffHunk) -> Boolean): Int {
        var lo = 0
        var hi = hunks.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (pred(hunks[mid])) hi = mid else lo = mid + 1 }
        return lo
    }

    fun lineText(doc: Rope, i: Int): String {
        val from = doc.lineStart(i)
        val to = if (i + 1 < doc.lineCount) doc.lineStart(i + 1) - 1 else doc.length
        return doc.slice(from, to)
    }
}

/**
 * Unified patches (`git diff` output): the host may hand the plugin the working text and a patch
 * instead of the base text.
 */
object UnifiedPatch {
    /**
     * The base text: [working] with [patch] applied in reverse, for a patch of ONE file whose new
     * side is [working]. Each hunk's body is exactly what its `@@ -a,b +c,d @@` header counts (so a
     * removed `-- comment` line, `--- comment` in the patch, or an added `++ x` is body, never a
     * file header); a `\ No newline at end of file` marker applies to the side of the line it
     * follows. Lines outside the hunks are the working copy's. Throws [IllegalArgumentException]
     * when a hunk does not match the working copy (its context or added lines), is shorter than its
     * header, or the patch holds a second file.
     */
    fun base(working: String, patch: String): String {
        // Lines as git counts them: text + whether a newline ends it.
        val w = gitLines(working)
        val out = ArrayList<Pair<String, Boolean>>(w.size)
        var at = 0
        val lines = patch.split('\n').let { if (patch.endsWith("\n")) it.dropLast(1) else it }
        var i = 0
        var files = 0
        var hunks = 0
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.startsWith("diff --git ")) { files++; require(files <= 1 || hunks == 0) { "the patch holds more than one file" }; i++; continue }
            if (!raw.startsWith("@@")) { i++; continue }
            val m = HEADER.find(raw) ?: throw IllegalArgumentException("bad hunk header: $raw")
            val oldCount = m.groupValues[2].ifEmpty { "1" }.toInt()
            val newStart = m.groupValues[3].toInt()
            val newCount = m.groupValues[4].ifEmpty { "1" }.toInt()
            // "+0,0": nothing on the new side, the hunk sits before line 1.
            val from = if (newCount == 0) newStart else newStart - 1
            require(from >= at && from <= w.size) { "hunk at line $newStart is out of order or beyond the text" }
            while (at < from) out += w[at++]
            hunks++
            i++
            var oldLeft = oldCount
            var newLeft = newCount
            var last = ' '
            while (i < lines.size && (oldLeft > 0 || newLeft > 0 || lines[i].startsWith("\\"))) {
                val l = lines[i]
                val tag = l.firstOrNull() ?: ' '
                val text = if (l.isEmpty()) "" else l.substring(1)
                when (tag) {
                    ' ' -> {
                        require(oldLeft > 0 && newLeft > 0) { "hunk at line $newStart is longer than its header" }
                        require(at < w.size && w[at].first == text) { "context line ${at + 1} does not match: '$text'" }
                        out += text to w[at].second
                        at++; oldLeft--; newLeft--
                    }
                    '-' -> { require(oldLeft > 0) { "hunk at line $newStart removes more than its header" }; out += text to true; oldLeft-- }
                    '+' -> {
                        require(newLeft > 0) { "hunk at line $newStart adds more than its header" }
                        require(at < w.size && w[at].first == text) { "added line ${at + 1} does not match: '$text'" }
                        at++; newLeft--
                    }
                    '\\' -> {
                        // "\ No newline at end of file": the line before it has none, on its side(s).
                        if (last == '-' || last == ' ') out[out.size - 1] = out.last().first to false
                        if (last == '+' || last == ' ') require(at > 0 && !w[at - 1].second) { "the working copy's last line ends with a newline, the patch says it does not" }
                    }
                    else -> throw IllegalArgumentException("unexpected line in a hunk: '$l'")
                }
                if (tag != '\\') last = tag
                i++
            }
            require(oldLeft == 0 && newLeft == 0) { "hunk at line $newStart is shorter than its header" }
        }
        while (at < w.size) out += w[at++]
        return buildString { for ((t, nl) in out) { append(t); if (nl) append('\n') } }
    }

    private fun gitLines(text: String): List<Pair<String, Boolean>> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n')
        return if (text.endsWith("\n")) parts.dropLast(1).map { it to true }
        else parts.dropLast(1).map { it to true } + (parts.last() to false)
    }

    private val HEADER = Regex("""^@@ -(\d+)(?:,(\d*))? \+(\d+)(?:,(\d*))? @@""")
}
