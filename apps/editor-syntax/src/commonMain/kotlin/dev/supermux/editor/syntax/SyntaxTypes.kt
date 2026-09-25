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
    const val UNKNOWN_LANGUAGE = -8
    const val NO_TABLES = -9
    const val BAD_TABLES = -10
    const val QUERY = -11
    const val INCOMPATIBLE_LANGUAGE = -12
    const val TIMEOUT = -13
}
