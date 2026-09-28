package dev.supermux.editor.plugins.autocomplete

/**
 * CM6's `FuzzyMatcher` (@codemirror/autocomplete), ported rule for rule: matches a typed [pattern]
 * against a completion's label and scores it (0 is a perfect full match; more negative is worse),
 * with the matched ranges for highlighting.
 *
 * The penalties: a case-folded match -200, not the whole word -100, matched by word starts -100,
 * not at the start -700, a gap -1100, and the label's length; one character matches only at the
 * label's start; the label is looked at up to 200 units. Reused per keystroke: one matcher per
 * pattern, [match] per option (no allocation per option but the result's ranges).
 */
class FuzzyMatcher(val pattern: String) {
    private val chars: IntArray
    private val folded: IntArray
    private val astral: Boolean
    private val any: IntArray
    private val precise: IntArray
    private val byWord: IntArray

    /**
     * CM6 keeps its `byWord` buffer across `match` calls and tests `byWord.length` (the most by-word
     * positions ANY earlier call on this matcher recorded): once a label has had a by-word match, a
     * later label's non-adjacent word start marks it not word-adjacent. Kept here, the same way.
     */
    private var byWordSeen = 0

    init {
        val cs = ArrayList<Int>()
        val fs = ArrayList<Int>()
        var p = 0
        while (p < pattern.length) {
            val c = codePointAt(pattern, p)
            val size = codePointSize(c)
            cs += c
            val part = pattern.substring(p, p + size)
            val upper = part.uppercase()
            fs += codePointAt(if (upper == part) part.lowercase() else upper, 0)
            p += size
        }
        chars = cs.toIntArray()
        folded = fs.toIntArray()
        astral = pattern.length != chars.size
        any = IntArray(chars.size)
        precise = IntArray(chars.size)
        byWord = IntArray(chars.size)
    }

    /** A match: its [score] and the matched [ranges] of the label, as `from, to` pairs. */
    class Match(val score: Int, val ranges: IntArray)

    /** Match [word] (a completion's label); null when it does not match. */
    fun match(word: String): Match? {
        if (pattern.isEmpty()) return Match(NOT_FULL, IntArray(0))
        if (word.length < pattern.length) return null
        val len = chars.size
        if (len == 1) {
            val first = codePointAt(word, 0)
            val firstSize = codePointSize(first)
            var score = if (firstSize == word.length) 0 else NOT_FULL
            if (first == chars[0]) Unit
            else if (first == folded[0]) score += CASE_FOLD
            else return null
            return Match(score, intArrayOf(0, firstSize))
        }
        val direct = word.indexOf(pattern)
        if (direct == 0) return Match(if (word.length == pattern.length) 0 else NOT_FULL, intArrayOf(0, pattern.length))
        var anyTo = 0
        if (direct < 0) {
            var i = 0
            val e = minOf(word.length, 200)
            while (i < e && anyTo < len) {
                val next = codePointAt(word, i)
                if (next == chars[anyTo] || next == folded[anyTo]) any[anyTo++] = i
                i += codePointSize(next)
            }
            if (anyTo < len) return null
        }
        var preciseTo = 0
        var byWordTo = 0
        var byWordFolded = false
        var adjacentTo = 0
        var adjacentStart = -1
        var adjacentEnd = -1
        val hasLower = word.any { it in 'a'..'z' }
        var wordAdjacent = true
        run {
            var i = 0
            val e = minOf(word.length, 200)
            var prevType = NON_WORD
            while (i < e && byWordTo < len) {
                val next = codePointAt(word, i)
                if (direct < 0) {
                    if (preciseTo < len && next == chars[preciseTo]) precise[preciseTo++] = i
                    if (adjacentTo < len) {
                        if (next == chars[adjacentTo] || next == folded[adjacentTo]) {
                            if (adjacentTo == 0) adjacentStart = i
                            adjacentEnd = i + 1
                            adjacentTo++
                        } else {
                            adjacentTo = 0
                        }
                    }
                }
                val type = charType(next)
                if (i == 0 || type == UPPER && hasLower || prevType == NON_WORD && type != NON_WORD) {
                    if (chars[byWordTo] == next || (folded[byWordTo] == next && run { byWordFolded = true; true })) {
                        byWord[byWordTo++] = i
                        if (byWordTo > byWordSeen) byWordSeen = byWordTo
                    } else if (byWordSeen > 0) {
                        wordAdjacent = false
                    }
                }
                prevType = type
                i += codePointSize(next)
            }
        }
        if (byWordTo == len && byWord[0] == 0 && wordAdjacent) return result(BY_WORD + (if (byWordFolded) CASE_FOLD else 0), byWord, word)
        if (adjacentTo == len && adjacentStart == 0) return Match(CASE_FOLD - word.length + (if (adjacentEnd == word.length) 0 else NOT_FULL), intArrayOf(0, adjacentEnd))
        if (direct > -1) return Match(NOT_START - word.length, intArrayOf(direct, direct + pattern.length))
        if (adjacentTo == len) return Match(CASE_FOLD + NOT_START - word.length, intArrayOf(adjacentStart, adjacentEnd))
        if (byWordTo == len) return result(BY_WORD + (if (byWordFolded) CASE_FOLD else 0) + NOT_START + (if (wordAdjacent) 0 else GAP), byWord, word)
        return if (len == 2) null else result((if (any[0] != 0) NOT_START else 0) + CASE_FOLD + GAP, any, word)
    }

    private fun result(score: Int, positions: IntArray, word: String): Match {
        val out = IntArray(chars.size * 2)
        var i = 0
        for (k in 0 until chars.size) {
            val pos = positions[k]
            val to = pos + if (astral) codePointSize(codePointAt(word, pos)) else 1
            if (i > 0 && out[i - 1] == pos) out[i - 1] = to
            else { out[i++] = pos; out[i++] = to }
        }
        return Match(score - word.length, out.copyOf(i))
    }

    companion object {
        const val GAP = -1100
        const val NOT_START = -700
        const val CASE_FOLD = -200
        const val BY_WORD = -100
        const val NOT_FULL = -100

        private const val NON_WORD = 0
        private const val UPPER = 1
        private const val LOWER = 2

        private fun charType(c: Int): Int = if (c < 0xff) {
            when {
                c in 48..57 || c in 97..122 -> LOWER
                c in 65..90 -> UPPER
                else -> NON_WORD
            }
        } else {
            val s = fromCodePoint(c)
            when {
                s != s.lowercase() -> UPPER
                s != s.uppercase() -> LOWER
                else -> NON_WORD
            }
        }

        internal fun codePointAt(s: String, i: Int): Int {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                return ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
            }
            return c.code
        }

        internal fun codePointSize(c: Int): Int = if (c >= 0x10000) 2 else 1

        private fun fromCodePoint(c: Int): String = if (c < 0x10000) c.toChar().toString() else {
            val v = c - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }
}
