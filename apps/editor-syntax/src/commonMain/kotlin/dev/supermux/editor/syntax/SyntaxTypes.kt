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
    /** A host callback (text reader, regex matcher) failed; its own error is reported with it. */
    const val CALLBACK = -14
    /**
     * Web only: the wasm runtime trapped (tree-sitter aborts on out of memory) or an exception
     * escaped from it, and it refuses every call from then on. The worker turns syntax off; a new
     * runtime needs a page load.
     */
    const val RUNTIME_DEAD = -20
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

/**
 * The (row, UTF-16 column) of a document index, packed as `row shl 32 or column`. Included ranges
 * need the points of their boundaries; the host knows its lines (a Rope answers in O(log n)), so
 * no backend ever walks the text for them.
 */
fun interface PointSource {
    fun pointAt(index: Int): Long

    companion object {
        fun row(p: Long): Int = (p ushr 32).toInt()
        fun column(p: Long): Int = p.toInt()
        fun of(row: Int, column: Int): Long = row.toLong() shl 32 or (column.toLong() and 0xFFFFFFFFL)
    }
}

/** A [PointSource] for any [TextSource]: the line starts, found in ONE scan on first use. */
class LineTable(private val text: TextSource, private val length: Int) : PointSource {
    private val starts: IntArray by lazy {
        val out = ArrayList<Int>().apply { add(0) }
        var i = 0
        while (i < length) {
            val chunk = text.chunkAt(i)
            if (chunk.isEmpty()) break
            val n = minOf(chunk.length, length - i)
            for (k in 0 until n) if (chunk[k] == '\n') out += i + k + 1
            i += n
        }
        out.toIntArray()
    }

    override fun pointAt(index: Int): Long {
        val s = starts
        var lo = 0
        var hi = s.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (s[mid] <= index) lo = mid else hi = mid - 1 }
        return PointSource.of(lo, index - s[lo])
    }
}

/** A document's text and points from an editor-core [dev.supermux.editor.core.Rope] (immutable, any thread). */
class RopeText(val rope: dev.supermux.editor.core.Rope) : TextSource, PointSource {
    override fun chunkAt(index: Int): CharSequence = if (index >= rope.length) "" else rope.chunkAt(index)
    override fun pointAt(index: Int): Long {
        val i = minOf(index, rope.length)
        val row = rope.lineIndexAt(i)
        return PointSource.of(row, i - rope.lineStart(row))
    }
}
