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
