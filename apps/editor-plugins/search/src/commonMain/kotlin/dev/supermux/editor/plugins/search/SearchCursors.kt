package dev.supermux.editor.plugins.search

import dev.supermux.editor.core.Rope

/**
 * The cursors over a [Rope]: they read the document in windows, never the whole text at once, so a
 * 10 MB file (or a 10 MB line) costs a scan of what is asked for, not a copy of everything.
 */
internal object SearchCursors {
    /** A literal search's window, and a per-line regex's. */
    const val WINDOW = 32_768

    /** A multi-line regex's first window. */
    const val MULTILINE_WINDOW = 65_536

    /** A window grows (doubling) up to this when a match reaches its cut end: a longer match is not found whole. */
    const val MAX_WINDOW = 2_097_152

    /**
     * Text a regex sees before the position it searches from (look-behinds, `\b`, and `^` only ever
     * at a real line start) and after the last position a match may end at (look-aheads, `$`).
     */
    const val PREFIX = 1024
    const val LOOKAHEAD = 1024

    /** After a window cut mid-line with no match, the next starts this far before its end. */
    const val OVERLAP = 4096
    const val MULTILINE_OVERLAP = 16_384

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

/** Test support: how many document units the cursors read. */
internal object SearchStats {
    var unitsRead: Long = 0

    fun measure(block: () -> Unit): Long {
        val before = unitsRead
        block()
        return unitsRead - before
    }
}

/**
 * A cursor that can stop part-way: [step] returns the next match, null at the end, or [PAUSED] once
 * it has read `budget` units without finding one, so a caller can give the thread back and go on.
 */
internal abstract class MatchScanner : Iterator<SearchMatch> {
    private var pending: SearchMatch? = null
    private var done = false
    private var stopAt = Long.MAX_VALUE

    /** Units read so far. */
    protected var read: Long = 0L
        private set

    protected fun reading(n: Int) { read += n; SearchStats.unitsRead += n }

    protected fun shouldPause(): Boolean = read >= stopAt

    /** The next match, or null when there is none; with no pause. */
    protected abstract fun advance(): SearchMatch?

    fun step(budget: Int): SearchMatch? {
        pending?.let { pending = null; return it }
        if (done) return null
        stopAt = read + budget
        val m = try { advance() } finally { stopAt = Long.MAX_VALUE }
        if (m == null) done = true
        return m
    }

    override fun hasNext(): Boolean {
        if (pending == null && !done) pending = advance().also { if (it == null) done = true }
        return pending != null
    }

    override fun next(): SearchMatch {
        if (!hasNext()) throw NoSuchElementException()
        return pending!!.also { pending = null }
    }

    companion object {
        /** [step]'s "read the budget, no match yet": not a match. */
        val PAUSED = SearchMatch(-1, -1)
    }
}

/** Literal matches of [needle] (already folded when [fold]) in [from, to). */
internal class LiteralCursor(
    private val doc: Rope,
    private val needle: String,
    private val fold: Boolean,
    private val wholeWord: Boolean,
    from: Int,
    private val to: Int,
) : MatchScanner() {
    private var pos = from
    private var winStart = -1
    private var winEnd = -1
    private var text = ""

    private fun load(start: Int) {
        winStart = start
        winEnd = minOf(to, start + maxOf(SearchCursors.WINDOW, 2 * needle.length))
        val raw = doc.slice(start, winEnd)
        reading(winEnd - start)
        text = if (fold) SearchQuery.foldRange(raw) else raw
    }

    override fun advance(): SearchMatch? {
        val n = needle.length
        if (n == 0) return null
        while (pos + n <= to) {
            if (winStart < 0 || pos < winStart || pos + n > winEnd) {
                if (shouldPause()) return PAUSED
                load(pos)
            }
            val i = text.indexOf(needle, pos - winStart)
            if (i < 0 || winStart + i + n > winEnd) {
                if (winEnd >= to) return null
                // The next window starts where a match may still begin: the last n - 1 units again.
                pos = maxOf(pos, winEnd - n + 1)
                winStart = -1
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
 * Regex matches in [from, to). The document is read in windows that never reach further than
 * [to] + [SearchCursors.LOOKAHEAD] and always start [SearchCursors.PREFIX] before the position
 * searched from (or at its line's start, line by line), so a look-behind and `^` see the text as it is:
 * - **per line** (the pattern cannot match a line break): windows of whole lines, or pieces of a
 *   line longer than a window;
 * - **multi-line**: windows cut anywhere.
 *
 * A window's end is EXACT when it is a line end (per line) or the document's end; at any other
 * (cut) end a match must end [SearchCursors.LOOKAHEAD] before it, or the window grows from the
 * match's start (doubling, up to [SearchCursors.MAX_WINDOW]), so `$`, `\b` and look-aheads never
 * answer at a cut. The empty line after a final line break is a line too (CM6: `^` prefixes it).
 */
internal class RegexCursor(
    private val doc: Rope,
    private val regex: Regex,
    private val emptyLine: () -> Regex?,
    private val multiline: Boolean,
    private val wholeWord: Boolean,
    from: Int,
    private val to: Int,
) : MatchScanner() {
    private var pos = from
    private val end = minOf(doc.length, to + SearchCursors.LOOKAHEAD)
    private var size = if (multiline) SearchCursors.MULTILINE_WINDOW else SearchCursors.WINDOW
    private var ws = -1
    private var we = -1
    private var exact = false
    private var text = ""
    private var lastA = -1
    private var lastB = -1
    private var tailDone = false

    private fun load(p: Int) {
        ws = if (multiline) maxOf(0, p - SearchCursors.PREFIX) else maxOf(SearchCursors.lineStartAt(doc, p), p - SearchCursors.PREFIX)
        val want = minOf(end, p + size)
        we = if (multiline) want else {
            val le = SearchCursors.lineEnd(doc, want)
            val ls = SearchCursors.lineStartAt(doc, want)
            when {
                le - want <= SearchCursors.OVERLAP -> minOf(le, end) // close to a line end: to it
                ls - 1 > p -> ls - 1                                // else to the previous line's end
                else -> want                                        // a long line: cut
            }
        }
        exact = we == doc.length || (!multiline && doc.charAt(we) == '\n')
        text = doc.slice(ws, we)
        reading(we - ws)
    }

    override fun advance(): SearchMatch? {
        while (pos <= to) {
            if (ws < 0 || pos < ws || pos > we) {
                if (shouldPause()) return PAUSED
                load(pos)
            }
            val m = try { regex.find(text, pos - ws) } catch (e: Throwable) { null } // a runaway pattern: no match
            if (m == null) {
                when {
                    we >= end -> return tail()
                    exact -> { pos = we + 1; ws = -1 }
                    else -> {
                        pos = maxOf(pos + 1, we - if (multiline) SearchCursors.MULTILINE_OVERLAP else SearchCursors.OVERLAP)
                        ws = -1
                    }
                }
                continue
            }
            val a = ws + m.range.first
            val b = ws + m.range.last + 1
            if (a > to) return tail()
            if (b > to) { pos = SearchCursors.stepOver(doc, a); continue }
            if (!exact && b + SearchCursors.LOOKAHEAD > we && size < SearchCursors.MAX_WINDOW) {
                // It may be cut short (or a `$` / look-ahead answering at the cut): again, bigger, from it.
                size *= 2
                pos = a
                ws = -1
                continue
            }
            pos = if (b == a) SearchCursors.stepOver(doc, a) else b
            if (wholeWord && !SearchCursors.isWholeWord(doc, a, b)) {
                if (b != a) pos = SearchCursors.stepOver(doc, a)
                continue
            }
            lastA = a; lastB = b
            return SearchMatch(a, b, m.groupValues)
        }
        return tail()
    }

    /**
     * The empty line after a final line break: java.util.regex's `^` never matches at the input's
     * end, CM6 (line by line) does, so "prefix every line" reaches it.
     */
    private fun tail(): SearchMatch? {
        if (tailDone) return null
        tailDone = true
        val len = doc.length
        if (to != len || (len > 0 && doc.charAt(len - 1) != '\n') || (lastA == len && lastB == len)) return null
        val m = try { emptyLine()?.find("") } catch (e: Throwable) { null } ?: return null
        return SearchMatch(len, len, m.groupValues)
    }
}
