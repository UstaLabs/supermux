package dev.supermux.editor.plugins.autocomplete

import dev.supermux.editor.compose.indentUnitFacet
import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.EditorState

/** One tab stop's range in the document: [field] is its order (0 first; `$0` is the last). */
data class FieldRange(val field: Int, val from: Int, val to: Int) {
    /** Through [changes]; null when its text went (CM6 drops the snippet then). */
    fun map(changes: ChangeSet): FieldRange? {
        val a = changes.mapPos(from, -1)
        val b = changes.mapPos(to, 1)
        return if (b < a) null else FieldRange(field, a, b)
    }
}

/**
 * A snippet template (CM6's `snippet()` syntax): `${}` or `${name}` a field (its name the default
 * text), `${1}` / `${1:name}` a numbered one (a custom order; the same number twice is ONE field,
 * edited in both places), `${0}` the last stop, `#{…}` the same as `${…}`, `\{` / `\}` literal
 * braces. [fromLsp] converts an LSP snippet (`$1`, `${1:foo}`, `$0`, `\$`) to it. Newlines are
 * indented like the line the snippet starts on, plus one indent unit per leading tab.
 */
class Snippet private constructor(val lines: List<String>, private val positions: List<Pos>) {
    private class Pos(var field: Int, val line: Int, var from: Int, var to: Int)

    /** The text to insert at [pos] and its field ranges (document positions after the insert). */
    fun instantiate(state: EditorState, pos: Int): Pair<String, List<FieldRange>> {
        val text = ArrayList<String>()
        val lineStart = arrayListOf(pos)
        val lineText = state.doc.lineAt(pos).text
        val baseIndent = lineText.takeWhile { it == ' ' || it == '\t' }
        var at = pos
        for (l in lines) {
            var line = l
            if (text.isNotEmpty()) {
                val tabs = line.takeWhile { it == '\t' }.length
                val indent = baseIndent + state.facet(indentUnitFacet).repeat(tabs)
                lineStart += at + indent.length - tabs
                line = indent + line.substring(tabs)
            }
            text += line
            at += line.length + 1
        }
        val ranges = positions.map { FieldRange(it.field, lineStart[it.line] + it.from, lineStart[it.line] + it.to) }
        return text.joinToString("\n") to ranges
    }

    /** How many fields (tab stops), `$0` included. */
    val fieldCount: Int get() = (positions.maxOfOrNull { it.field } ?: -1) + 1

    companion object {
        private val FIELD = Regex("""[#$]\{(?:(\d+)(?::([^{}]*))?|((?:\\[{}]|[^{}])*))\}""")
        private val LSP = Regex("""\\([$}\\])|\$(\d+)""")

        /** CM6's `lspToSnippet`: `$1` becomes `${1}`, the backslash before `$`, `}` and `\` goes. */
        fun fromLsp(text: String): Snippet = parse(LSP.replace(text) { m -> m.groupValues[1].ifEmpty { "\${" + m.groupValues[2] + "}" } })

        fun parse(template: String): Snippet {
            class F(val seq: Long?, val name: String)
            val fields = ArrayList<F>()
            val lines = ArrayList<String>()
            val positions = ArrayList<Pos>()
            for (raw in template.split(Regex("\r\n?|\n"))) {
                var line = raw
                while (true) {
                    val m = FIELD.find(line) ?: break
                    var seq: Long? = m.groups[1]?.value?.toLong()
                    val rawName = m.groups[2]?.value ?: m.groups[3]?.value ?: ""
                    if (seq == 0L) seq = 1_000_000_000L
                    val name = rawName.replace(Regex("""\\[{}]""")) { it.value.substring(1) }
                    var found = -1
                    for (i in fields.indices) {
                        if (if (seq != null) fields[i].seq == seq else if (name.isNotEmpty()) fields[i].name == name else false) found = i
                    }
                    if (found < 0) {
                        var i = 0
                        while (i < fields.size && (seq == null || (fields[i].seq != null && fields[i].seq!! < seq))) i++
                        fields.add(i, F(seq, name))
                        found = i
                        for (p in positions) if (p.field >= found) p.field++
                    }
                    for (p in positions) if (p.line == lines.size && p.from > m.range.first) {
                        val snip = if (m.groups[2] != null) 3 + (m.groups[1]?.value ?: "").length else 2
                        p.from -= snip
                        p.to -= snip
                    }
                    positions += Pos(found, lines.size, m.range.first, m.range.first + name.length)
                    line = line.substring(0, m.range.first) + rawName + line.substring(m.range.last + 1)
                }
                val sb = StringBuilder()
                var i = 0
                while (i < line.length) {
                    if (line[i] == '\\' && i + 1 < line.length && (line[i + 1] == '{' || line[i + 1] == '}')) {
                        val idx = sb.length
                        for (p in positions) if (p.line == lines.size && p.from > idx) { p.from--; p.to-- }
                        sb.append(line[i + 1])
                        i += 2
                    } else { sb.append(line[i]); i++ }
                }
                lines += sb.toString()
            }
            return Snippet(lines, positions)
        }
    }
}
