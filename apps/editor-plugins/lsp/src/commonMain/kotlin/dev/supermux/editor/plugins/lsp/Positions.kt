package dev.supermux.editor.plugins.lsp

import dev.supermux.editor.core.Rope

/** An LSP position: a 0-based [line] and a [character] in the negotiated [PositionEncoding]. */
data class LspPosition(val line: Int, val character: Int)

/** An LSP range, [start] to [end]. */
data class LspRange(val start: LspPosition, val end: LspPosition)

/**
 * The unit of [LspPosition.character] (LSP 3.17 `positionEncoding`). The client offers utf-16 first:
 * it is the editor's own unit, so a conversion is the line's start (O(log n) in the Rope) plus the
 * character; utf-8 and utf-32 (a server that insists) count along the line's text.
 */
enum class PositionEncoding(val wire: String) {
    UTF16("utf-16"), UTF8("utf-8"), UTF32("utf-32");

    companion object {
        fun of(wire: String?): PositionEncoding = entries.firstOrNull { it.wire == wire } ?: UTF16
    }
}

/** Offsets (UTF-16, the Rope's) to and from LSP positions in [encoding]. */
object Positions {
    fun toLsp(doc: Rope, offset: Int, encoding: PositionEncoding = PositionEncoding.UTF16): LspPosition {
        val at = offset.coerceIn(0, doc.length)
        val line = doc.lineIndexAt(at)
        val start = doc.lineStart(line)
        val ch = when (encoding) {
            PositionEncoding.UTF16 -> at - start
            PositionEncoding.UTF8 -> utf8Length(doc.slice(start, at))
            PositionEncoding.UTF32 -> codePoints(doc.slice(start, at))
        }
        return LspPosition(line, ch)
    }

    /**
     * The offset of [pos] in [doc], clamped: a line past the end is the document's end, a character
     * past its line's end is the line's end (never into the next line), and one inside a surrogate
     * pair or a UTF-8 sequence moves to the character's start.
     */
    fun fromLsp(doc: Rope, pos: LspPosition, encoding: PositionEncoding = PositionEncoding.UTF16): Int {
        if (pos.line < 0) return 0
        if (pos.line >= doc.lineCount) return doc.length
        val start = doc.lineStart(pos.line)
        val end = if (pos.line + 1 < doc.lineCount) doc.lineStart(pos.line + 1) - 1 else doc.length
        val want = pos.character.coerceAtLeast(0)
        return when (encoding) {
            PositionEncoding.UTF16 -> {
                val o = (start + want).coerceAtMost(end)
                if (o in (start + 1) until end && doc.charAt(o).isLowSurrogate() && doc.charAt(o - 1).isHighSurrogate()) o - 1 else o
            }
            else -> {
                val text = doc.slice(start, end)
                var units = 0
                var i = 0
                while (i < text.length) {
                    val c = text[i]
                    val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
                    val w = if (encoding == PositionEncoding.UTF32) 1 else when {
                        pair -> 4
                        c.code < 0x80 -> 1
                        c.code < 0x800 -> 2
                        else -> 3
                    }
                    if (units + w > want) break
                    units += w
                    i += if (pair) 2 else 1
                }
                start + i
            }
        }
    }

    fun rangeToLsp(doc: Rope, from: Int, to: Int, encoding: PositionEncoding = PositionEncoding.UTF16) =
        LspRange(toLsp(doc, from, encoding), toLsp(doc, to, encoding))

    internal fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) { n += 4; i += 2; continue }
            n += when { c.code < 0x80 -> 1; c.code < 0x800 -> 2; else -> 3 }
            i++
        }
        return n
    }

    internal fun codePoints(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }
}
