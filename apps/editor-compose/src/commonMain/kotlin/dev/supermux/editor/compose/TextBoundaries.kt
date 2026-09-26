package dev.supermux.editor.compose

import dev.supermux.editor.core.Rope

/**
 * Grapheme and word boundaries over a [Rope], the same on every platform (no BreakIterator, no
 * ICU): a caret never lands inside a surrogate pair or a cluster, and a word is a run of letters,
 * digits and `_` in any script.
 *
 * Graphemes follow the parts of UAX #29 an editor meets: combining marks (Mn/Mc/Me), variation
 * selectors, emoji modifiers and tags extend the previous character; a ZWJ joins the next
 * pictograph (`👩‍💻`); regional indicators pair up into flags; `\n` is always alone.
 *
 * Only a window around the position is read (a line of any length costs the same).
 */
internal object TextBoundaries {
    private const val WINDOW = 256
    private const val WORD_WINDOW = 4096

    fun nextGrapheme(doc: Rope, pos: Int): Int {
        if (pos >= doc.length) return doc.length
        val end = minOf(doc.length, pos + WINDOW)
        val s = doc.slice(pos, end)
        return pos + nextIn(s, 0)
    }

    fun prevGrapheme(doc: Rope, pos: Int): Int {
        if (pos <= 0) return 0
        if (doc.charAt(pos - 1) == '\n') return pos - 1
        val lineStart = doc.lineStart(doc.lineIndexAt(pos))
        var start = maxOf(lineStart, pos - WINDOW)
        if (start > 0 && doc.charAt(start).isLowSurrogate()) start--
        val s = doc.slice(start, pos)
        return start + prevIn(s, s.length)
    }

    /** The grapheme boundary at or before [pos] (a pointer or a mapped position that fell inside a cluster). */
    fun snap(doc: Rope, pos: Int): Int {
        if (pos <= 0 || pos >= doc.length) return pos.coerceIn(0, doc.length)
        val prev = prevGrapheme(doc, pos)
        return if (nextGrapheme(doc, prev) == pos) pos else prev
    }

    fun wordRight(doc: Rope, pos: Int): Int {
        if (pos >= doc.length) return doc.length
        if (doc.charAt(pos) == '\n') return pos + 1
        val lineIndex = doc.lineIndexAt(pos)
        val lineEnd = if (lineIndex + 1 < doc.lineCount) doc.lineStart(lineIndex + 1) - 1 else doc.length
        val s = doc.slice(pos, minOf(lineEnd, pos + WORD_WINDOW))
        var i = 0
        while (i < s.length && classAt(s, i) == SPACE) i = nextIn(s, i)
        if (i < s.length) {
            val c = classAt(s, i)
            while (i < s.length && classAt(s, i) == c) i = nextIn(s, i)
        }
        return pos + i
    }

    fun wordLeft(doc: Rope, pos: Int): Int {
        if (pos <= 0) return 0
        if (doc.charAt(pos - 1) == '\n') return pos - 1
        val lineStart = doc.lineStart(doc.lineIndexAt(pos))
        var start = maxOf(lineStart, pos - WORD_WINDOW)
        if (start > 0 && doc.charAt(start).isLowSurrogate()) start--
        val s = doc.slice(start, pos)
        val b = boundaries(s)
        var k = b.size - 1
        while (k > 0 && classAt(s, b[k - 1]) == SPACE) k--
        if (k > 0) {
            val c = classAt(s, b[k - 1])
            while (k > 0 && classAt(s, b[k - 1]) == c) k--
        }
        return start + b[k]
    }

    /** The word (or run of punctuation, or of spaces) around [pos]: a double-click's selection. */
    fun wordAt(doc: Rope, pos: Int): IntRange {
        val lineIndex = doc.lineIndexAt(pos)
        val lineStart = doc.lineStart(lineIndex)
        val lineEnd = if (lineIndex + 1 < doc.lineCount) doc.lineStart(lineIndex + 1) - 1 else doc.length
        if (lineStart == lineEnd) return pos until pos
        val from = maxOf(lineStart, pos - WORD_WINDOW).let { if (it > 0 && doc.charAt(it).isLowSurrogate()) it - 1 else it }
        val s = doc.slice(from, minOf(lineEnd, pos + WORD_WINDOW))
        val b = boundaries(s)
        // The cluster the caret is before; at the end of a word (or the line), the one before it.
        var k = b.indexOfFirst { it >= pos - from }.let { if (it < 0) b.size - 1 else it }
        if (k == b.size - 1) k--
        else if (k > 0 && classAt(s, b[k]) != WORD && classAt(s, b[k - 1]) == WORD) k--
        val c = classAt(s, b[k])
        var a = k
        while (a > 0 && classAt(s, b[a - 1]) == c) a--
        var e = k + 1
        while (e < b.size - 1 && classAt(s, b[e]) == c) e++
        return (from + b[a]) until (from + b[e])
    }

    /** Every grapheme boundary of [s], 0 and s.length included. */
    private fun boundaries(s: CharSequence): IntArray {
        val out = ArrayList<Int>(s.length + 1)
        var i = 0
        out += 0
        while (i < s.length) { i = nextIn(s, i); out += i }
        return out.toIntArray()
    }

    // ------------------------------------------------------------------ in a string --

    /** The grapheme boundary after the one starting at [i]. */
    fun nextIn(s: CharSequence, i: Int): Int {
        if (i >= s.length) return s.length
        val first = codePointAt(s, i)
        var j = i + charCount(first)
        if (first == '\n'.code) return j
        var regional = if (isRegional(first)) 1 else 0
        var afterZwj = first == ZWJ
        while (j < s.length) {
            val c = codePointAt(s, j)
            val joins = isExtend(c) || (afterZwj && isPictographic(c)) || (regional == 1 && isRegional(c))
            if (!joins) break
            if (isRegional(c)) regional++
            afterZwj = c == ZWJ
            j += charCount(c)
        }
        return j
    }

    /** The grapheme boundary before [i]: walks forward from the start of [s], which must be a boundary. */
    fun prevIn(s: CharSequence, i: Int): Int {
        if (i <= 0) return 0
        var b = 0
        while (true) {
            val n = nextIn(s, b)
            if (n >= i) return b
            b = n
        }
    }

    private const val WORD = 0
    private const val SPACE = 1
    private const val OTHER = 2
    private const val NEWLINE = 3
    private const val ZWJ = 0x200D

    private fun classAt(s: CharSequence, i: Int): Int {
        val c = codePointAt(s, i)
        return when {
            c == '\n'.code -> NEWLINE
            c == ' '.code || c == '\t'.code -> SPACE
            c < 0x10000 -> {
                val ch = c.toChar()
                when {
                    ch.isWhitespace() -> SPACE
                    ch.isLetterOrDigit() || ch == '_' -> WORD
                    else -> OTHER
                }
            }
            isPictographic(c) -> OTHER
            else -> WORD // supplementary letters (CJK extension B, historic scripts)
        }
    }

    private fun isExtend(c: Int): Boolean {
        if (c < 0x300) return false
        if (c == ZWJ || c in 0xFE00..0xFE0F || c in 0x1F3FB..0x1F3FF || c in 0xE0020..0xE007F || c in 0xE0100..0xE01EF) return true
        if (c >= 0x10000) return false
        val cat = c.toChar().category
        return cat == CharCategory.NON_SPACING_MARK || cat == CharCategory.ENCLOSING_MARK || cat == CharCategory.COMBINING_SPACING_MARK
    }

    private fun isRegional(c: Int) = c in 0x1F1E6..0x1F1FF

    private fun isPictographic(c: Int) =
        c in 0x1F000..0x1FAFF || c in 0x2600..0x27BF || c in 0x2300..0x23FF || c in 0x2B00..0x2BFF || c == 0x00A9 || c == 0x00AE

    private fun codePointAt(s: CharSequence, i: Int): Int {
        val hi = s[i]
        if (hi.isHighSurrogate() && i + 1 < s.length) {
            val lo = s[i + 1]
            if (lo.isLowSurrogate()) return 0x10000 + ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00)
        }
        return hi.code
    }

    private fun charCount(c: Int) = if (c >= 0x10000) 2 else 1
}
