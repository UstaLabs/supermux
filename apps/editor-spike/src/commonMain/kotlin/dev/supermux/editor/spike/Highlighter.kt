package dev.supermux.editor.spike

/** One highlight capture, in UTF-16 code units: the ONLY unit the real editor will use. */
data class Span(val start: Int, val end: Int, val capture: String) {
    override fun toString() = "$start-$end $capture"
}

/** What M0 needs from a backend: parse, highlight, apply one edit, reparse incrementally. */
interface SpikeHighlighter {
    /** Parse [source] from scratch. */
    fun parse(source: String)
    /** Every capture of [query] over the current tree, sorted by (start, -end, capture). */
    fun highlights(query: String): List<Span>
    /**
     * Replace UTF-16 range [from, to) with [insert] (both the text and the tree), then reparse
     * incrementally. Returns the new source.
     */
    fun edit(from: Int, to: Int, insert: String): String
    fun close()
}

/** Suspend because web-tree-sitter's init and Language.load are async. */
expect suspend fun openJsonHighlighter(): SpikeHighlighter

internal fun List<Span>.sortedForGolden() =
    sortedWith(compareBy<Span>({ it.start }, { -it.end }, { it.capture }))
