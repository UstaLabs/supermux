package dev.supermux.editor.spike

import io.github.treesitter.ktreesitter.InputEdit
import io.github.treesitter.ktreesitter.InputEncoding
import io.github.treesitter.ktreesitter.Language
import io.github.treesitter.ktreesitter.Parser
import io.github.treesitter.ktreesitter.Point
import io.github.treesitter.ktreesitter.Query
import io.github.treesitter.ktreesitter.Tree

/**
 * M0 DEVIATION from the plan. The plan parsed with InputEncoding.UTF_16LE so byte offsets would be
 * 2 × the UTF-16 index. ktreesitter 0.25.1 cannot do that: it always converts the String it gets to
 * (modified) UTF-8 before handing it to tree-sitter, so a UTF_16LE parse reads UTF-8 bytes as UTF-16
 * code units and produces `(document (ERROR (UNEXPECTED 8827)))` (KtsProbeTest). So this backend
 * parses as UTF-8 and maps tree-sitter's byte offsets back to UTF-16 indexes through a prefix table
 * built with [ktsEncoding]'s byte widths. The golden test (the contract) is unchanged.
 */
class KtsHighlighter : SpikeHighlighter {
    private val language = Language(jsonLanguagePointer())
    private val parser = Parser(language)
    private var source = ""
    private var bytes = ByteMap("")
    private var tree: Tree? = null

    private fun reparse(old: Tree?) {
        val text = source
        val map = bytes
        tree = parser.parse(InputEncoding.UTF_8, old) { byte, _ ->
            val i = map.indexOf(byte.toInt())
            if (i >= text.length) "" else {
                var end = minOf(text.length, i + 1024)
                if (end < text.length && text[end - 1].isHighSurrogate()) end-- // never split a pair
                val chunk = text.substring(i, end)
                when (ktsEncoding) {
                    KtsEncoding.MODIFIED_UTF8 -> chunk
                    // Kotlin/Native reports chunk.length (UTF-16 units) as the byte count but copies
                    // chunk's UTF-8 bytes. Pad with ASCII so length == its UTF-8 size of the real
                    // chunk: tree-sitter then reads exactly the real bytes and never the padding.
                    KtsEncoding.UTF8 -> chunk.padEnd(map.at(end) - map.at(i), ' ')
                }
            }
        }
    }

    override fun parse(source: String) { this.source = source; bytes = ByteMap(source); reparse(null) }

    override fun highlights(query: String): List<Span> {
        val q = Query(language, query)
        val root = tree!!.rootNode
        return q(root).captures().map { (i, match) ->
            val c = match.captures[i.toInt()]
            Span(bytes.indexOf(c.node.startByte.toInt()), bytes.indexOf(c.node.endByte.toInt()), c.name)
        }.toList().sortedForGolden()
    }

    override fun edit(from: Int, to: Int, insert: String): String {
        val old = source
        val oldMap = bytes
        source = old.replaceRange(from, to, insert)
        bytes = ByteMap(source)
        val t = tree!!
        t.edit(
            InputEdit(
                startByte = oldMap.at(from).toUInt(),
                oldEndByte = oldMap.at(to).toUInt(),
                newEndByte = bytes.at(from + insert.length).toUInt(),
                startPoint = pointAt(old, oldMap, from),
                oldEndPoint = pointAt(old, oldMap, to),
                newEndPoint = pointAt(source, bytes, from + insert.length),
            ),
        )
        reparse(t)
        return source
    }

    override fun close() { tree = null }

    /** tree-sitter's Point column is in BYTES of the encoding. */
    private fun pointAt(text: String, map: ByteMap, index: Int): Point {
        val lineStart = text.lastIndexOf('\n', index - 1) + 1
        val row = text.subSequence(0, index).count { it == '\n' }
        return Point(row.toUInt(), (map.at(index) - map.at(lineStart)).toUInt())
    }
}

/** UTF-16 index <-> the byte offset tree-sitter sees, for [ktsEncoding]. */
internal class ByteMap(text: String) {
    private val prefix = IntArray(text.length + 1)

    init {
        for (i in text.indices) {
            val c = text[i]
            val w = when {
                ktsEncoding == KtsEncoding.MODIFIED_UTF8 && c == '\u0000' -> 2
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                ktsEncoding == KtsEncoding.MODIFIED_UTF8 -> 3 // each surrogate on its own
                // UTF-8: the pair's 4 bytes all go on the LOW surrogate, so a byte offset at a pair's
                // start maps back to the high surrogate (the first index with that offset).
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> 0
                c.isLowSurrogate() && i > 0 && text[i - 1].isHighSurrogate() -> 4
                else -> 3
            }
            prefix[i + 1] = prefix[i] + w
        }
    }

    fun at(index: Int): Int = prefix[index]

    /** The first UTF-16 index whose byte offset is >= [byte]. */
    fun indexOf(byte: Int): Int {
        var lo = 0; var hi = prefix.size - 1
        if (byte > prefix[hi]) return prefix.size - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (prefix[m] < byte) lo = m + 1 else hi = m }
        return lo
    }
}

actual suspend fun openJsonHighlighter(): SpikeHighlighter = KtsHighlighter()
