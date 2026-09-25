package dev.supermux.editor.syntax

/** One highlight capture, in UTF-16 code units. */
data class Span(val start: Int, val end: Int, val capture: String) {
    override fun toString() = "$start-$end $capture"
}

/** A String read in [size]-unit chunks (size 3 splits surrogate pairs on purpose). */
class ChunkedSource(private val text: String, private val size: Int = Int.MAX_VALUE) : TextSource {
    override fun chunkAt(index: Int): CharSequence =
        if (index >= text.length) "" else text.substring(index, minOf(text.length.toLong(), index.toLong() + size).toInt())
}

/**
 * The M0 SpikeHighlighter contract on the ses_* binding: parse, highlight, one edit + incremental
 * reparse. NO offset conversion of any kind: tree-sitter's UTF-16 results are used as they come.
 */
class SesHighlighter(language: String, private val chunk: Int = Int.MAX_VALUE) : AutoCloseable {
    private val parser = SyntaxParser(language)
    private val lang = language
    var source = ""; private set
    var tree: SyntaxTree? = null; private set
    var lastChangedRanges = IntArray(0); private set

    fun parse(source: String) {
        tree?.close()
        this.source = source
        tree = parser.parse(ChunkedSource(source, chunk))
    }

    fun highlights(query: String, from: Int = 0, to: Int = source.length): List<Span> =
        SyntaxQuery(lang, query).use { q ->
            val a = q.captures(tree!!, from, to, ChunkedSource(source, chunk))
            List(a.size / 3) { Span(a[3 * it], a[3 * it + 1], q.captureNames[a[3 * it + 2]]) }
                .sortedWith(compareBy<Span>({ it.start }, { -it.end }, { it.capture }))
        }

    fun edit(from: Int, to: Int, insert: String): String {
        val old = source
        val next = old.replaceRange(from, to, insert)
        val (sr, sc) = point(old, from)
        val (oer, oec) = point(old, to)
        val (ner, nec) = point(next, from + insert.length)
        val t = tree!!
        t.edit(TextEdit(from, to, from + insert.length, sr, sc, oer, oec, ner, nec))
        val fresh = parser.parse(ChunkedSource(next, chunk), t)
        lastChangedRanges = t.changedRanges(fresh)
        t.close()
        tree = fresh
        source = next
        return next
    }

    /** (row, column) of a UTF-16 index; column in UTF-16 units from the line start. */
    private fun point(text: String, index: Int): Pair<Int, Int> {
        val lineStart = text.lastIndexOf('\n', index - 1) + 1
        return text.subSequence(0, index).count { it == '\n' } to (index - lineStart)
    }

    override fun close() { tree?.close(); tree = null; parser.close() }
}
