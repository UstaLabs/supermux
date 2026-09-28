package dev.supermux.editor.plugins.search

import dev.supermux.editor.core.Rope

/**
 * The cursors over a [Rope]: they read the document in windows ([WINDOW] units, a line-aligned
 * window for a regex), never the whole text at once, so a 10 MB file costs a scan, not a copy.
 */
internal object SearchCursors {
    /** A literal search's window; consecutive windows overlap by the needle's length minus one. */
    const val WINDOW = 32_768

    /** A multi-line regex's first window, and how near its end a match makes it grow. */
    const val MULTILINE_WINDOW = 262_144
    const val MULTILINE_MARGIN = 65_536

    /** A multi-line regex's window grows (doubling) up to this: a longer match is not found whole. */
    const val MULTILINE_MAX = 2_097_152

    fun lineEnd(doc: Rope, pos: Int): Int {
        val i = doc.lineIndexAt(pos)
        return if (i + 1 < doc.lineCount) doc.lineStart(i + 1) - 1 else doc.length
    }

    fun lineStartAt(doc: Rope, pos: Int): Int = doc.lineStart(doc.lineIndexAt(pos))

    /** CM6's whole-word test for [a, b) (see [SearchQuery.wholeWord]). */
    fun isWholeWord(doc: Rope, a: Int, b: Int): Boolean {
        if (a == b) return true
        val before = a > 0 && isWordChar(doc.charAt(a - 1))
        val first = isWordChar(doc.charAt(a))
        val last = isWordChar(doc.charAt(b - 1))
        val after = b < doc.length && isWordChar(doc.charAt(b))
        return (!before || !first) && (!after || !last)
    }

    /** The position after an empty match at [pos]: one character on, a surrogate pair whole. */
    fun stepOver(doc: Rope, pos: Int): Int =
        if (pos < doc.length - 1 && doc.charAt(pos).isHighSurrogate() && doc.charAt(pos + 1).isLowSurrogate()) pos + 2 else pos + 1
}

/** Literal matches of [needle] (already folded when [fold]) in [from, to). */
internal class LiteralCursor(
    private val doc: Rope,
    private val needle: String,
    private val fold: Boolean,
    private val wholeWord: Boolean,
    from: Int,
    private val to: Int,
) : Iterator<SearchMatch> {
    private var pos = from
    private var winStart = -1
    private var winEnd = -1
    private var text = ""
    private var pending: SearchMatch? = null
    private var done = false

    override fun hasNext(): Boolean {
        if (pending == null && !done) pending = advance().also { if (it == null) done = true }
        return pending != null
    }

    override fun next(): SearchMatch {
        if (!hasNext()) throw NoSuchElementException()
        return pending!!.also { pending = null }
    }

    private fun load(start: Int) {
        winStart = start
        winEnd = minOf(to, start + maxOf(SearchCursors.WINDOW, 2 * needle.length))
        val raw = doc.slice(start, winEnd)
        text = if (fold) SearchQuery.foldRange(raw) else raw
    }

    private fun advance(): SearchMatch? {
        val n = needle.length
        if (n == 0) return null
        while (pos + n <= to) {
            if (winStart < 0 || pos < winStart || pos + n > winEnd) load(pos)
            val i = text.indexOf(needle, pos - winStart)
            if (i < 0 || winStart + i + n > winEnd) {
                if (winEnd >= to) return null
                // The next window starts where a match may still begin: the last n - 1 units again.
                pos = maxOf(pos, winEnd - n + 1)
                load(pos)
                continue
            }
            val a = winStart + i
            val b = a + n
            if (wholeWord && !SearchCursors.isWholeWord(doc, a, b)) { pos = a + 1; continue }
            pos = b
            return SearchMatch(a, b)
        }
        return null
    }
}

/**
 * Regex matches in [from, to): per line (windows of whole lines, so `^`, `$` and look-behinds see
 * the lines as they are) or, [multiline], across lines in a window that grows when a match comes
 * near its end, up to [SearchCursors.MULTILINE_MAX].
 */
internal class RegexCursor(
    private val doc: Rope,
    private val regex: Regex,
    private val multiline: Boolean,
    private val wholeWord: Boolean,
    from: Int,
    private val to: Int,
) : Iterator<SearchMatch> {
    private var pos = from
    private var winStart = -1
    private var winEnd = -1
    private var size = if (multiline) SearchCursors.MULTILINE_WINDOW else SearchCursors.WINDOW
    private var text = ""
    private var pending: SearchMatch? = null
    private var done = false

    // The end of the text worth reading: the end of the line holding [to] (`$` there needs it).
    private val limit = SearchCursors.lineEnd(doc, to)

    override fun hasNext(): Boolean {
        if (pending == null && !done) pending = advance().also { if (it == null) done = true }
        return pending != null
    }

    override fun next(): SearchMatch {
        if (!hasNext()) throw NoSuchElementException()
        return pending!!.also { pending = null }
    }

    private fun load(start: Int) {
        winStart = start
        winEnd = SearchCursors.lineEnd(doc, minOf(limit, start + size))
        text = doc.slice(winStart, winEnd)
    }

    private fun advance(): SearchMatch? {
        while (pos <= to) {
            if (winStart < 0 || pos < winStart || pos > winEnd) load(SearchCursors.lineStartAt(doc, pos))
            val m = try { regex.find(text, pos - winStart) } catch (e: Throwable) { null } // a runaway pattern (StackOverflow): no match
            if (m == null) {
                if (winEnd >= limit) return null
                if (multiline) {
                    // A match may begin near the end and need the text after it: start there again.
                    val next = maxOf(pos, winEnd - SearchCursors.MULTILINE_MARGIN)
                    val ls = SearchCursors.lineStartAt(doc, next)
                    pos = next
                    load(if (ls > winStart) ls else next)
                } else {
                    pos = winEnd + 1
                    if (pos > to) return null
                    load(pos)
                }
                continue
            }
            val a = winStart + m.range.first
            val b = winStart + m.range.last + 1
            if (multiline && winEnd < limit && b > winEnd - SearchCursors.MULTILINE_MARGIN && size < SearchCursors.MULTILINE_MAX) {
                size *= 2
                load(winStart)
                continue
            }
            if (a > to) return null
            if (b > to) { pos = SearchCursors.stepOver(doc, a); continue }
            pos = if (b == a) SearchCursors.stepOver(doc, a) else b
            if (wholeWord && !SearchCursors.isWholeWord(doc, a, b)) {
                if (b != a) pos = SearchCursors.stepOver(doc, a)
                continue
            }
            return SearchMatch(a, b, m.groupValues)
        }
        return null
    }
}
