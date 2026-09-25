package dev.supermux.editor.syntax

/** The parser pulls text through this; `Rope.chunkAt` fits as-is ("" at the end). */
fun interface TextSource {
    /** The document's text from [index] to the end of some chunk, or "" at/after the end. */
    fun chunkAt(index: Int): CharSequence
}

/** One edit: UTF-16 [start, oldEnd) became [start, newEnd). Rows 0-based, columns UTF-16 from line start. */
data class TextEdit(
    val start: Int, val oldEnd: Int, val newEnd: Int,
    val startRow: Int, val startColumn: Int,
    val oldEndRow: Int, val oldEndColumn: Int,
    val newEndRow: Int, val newEndColumn: Int,
)

class SyntaxException(message: String, val status: Int) : RuntimeException("$message (ses status $status)")

object SyntaxStatus {
    const val OK = 0
    const val INVALID_ARGUMENT = -3
    const val OUT_OF_MEMORY = -4
    const val UNKNOWN_LANGUAGE = -8
    const val NO_TABLES = -9
    const val BAD_TABLES = -10
    const val QUERY = -11
    const val INCOMPATIBLE_LANGUAGE = -12
    const val TIMEOUT = -13
}

/**
 * One directive of a query pattern: `#set! [@capture] key [value]`, `#is? [@capture] property
 * [value]` or `#is-not? ...`, in source order. [captureId] indexes the query's capture names.
 */
data class PatternSetting(val kind: Kind, val captureId: Int?, val key: String, val value: String?) {
    enum class Kind { SET, IS, IS_NOT }
}

/**
 * A query's captures: packed [start, end, captureIndex, patternIndex]* in UTF-16 units, in
 * tree-sitter's capture order. [exceededMatchLimit]: the cursor dropped matches in progress, so
 * some captures may be missing.
 */
class Captures(val ints: IntArray, val exceededMatchLimit: Boolean) {
    val size: Int get() = ints.size / 4
    fun start(i: Int) = ints[4 * i]
    fun end(i: Int) = ints[4 * i + 1]
    fun capture(i: Int) = ints[4 * i + 2]
    fun pattern(i: Int) = ints[4 * i + 3]
}

/**
 * A query's matches, in tree-sitter's match order, predicates applied: for injections, where a
 * match's captures (its @injection.language and @injection.content) belong together. Packed ints
 * (see ses_query_matches): per match `pattern, n`, then per capture `start, end, captureIndex, k`
 * and k `(childStart, childEnd, childIsNamed)` triples for the capture whose children were asked for.
 */
class Matches(val ints: IntArray, val exceededMatchLimit: Boolean) {
    class Capture(val start: Int, val end: Int, val index: Int, val children: IntArray) {
        /** Child [i] as (start, end, isNamed). */
        val childCount: Int get() = children.size / 3
        fun childStart(i: Int) = children[3 * i]
        fun childEnd(i: Int) = children[3 * i + 1]
        fun childIsNamed(i: Int) = children[3 * i + 2] != 0
    }

    class Match(val pattern: Int, val captures: List<Capture>)

    fun toList(): List<Match> {
        val out = ArrayList<Match>()
        var i = 0
        while (i < ints.size) {
            val pattern = ints[i]
            val n = ints[i + 1]
            i += 2
            val caps = ArrayList<Capture>(n)
            repeat(n) {
                val k = ints[i + 3]
                caps += Capture(ints[i], ints[i + 1], ints[i + 2], ints.copyOfRange(i + 4, i + 4 + 3 * k))
                i += 4 + 3 * k
            }
            out += Match(pattern, caps)
        }
        return out
    }
}
