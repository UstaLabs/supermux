package dev.supermux.editor.plugins.basics

import dev.supermux.editor.compose.EditorViewport
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Facet
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf

/**
 * [SelectionMatches]' options (CM6's `highlightSelectionMatches`): [minSelectionLength] characters
 * at least, more than [maxMatches] matches mark nothing, and [highlightWordAroundCursor] marks the
 * word under an empty cursor's other whole-word occurrences.
 */
data class SelectionMatchesConfig(
    val minSelectionLength: Int = 2,
    val maxMatches: Int = 100,
    val highlightWordAroundCursor: Boolean = true,
)

val selectionMatchesConfig: Facet<SelectionMatchesConfig, SelectionMatchesConfig> = Facet.first("selectionMatches", SelectionMatchesConfig())

/**
 * Selection matches (CM6's `highlightSelectionMatches`): with ONE non-empty single-line selection of
 * at least [SelectionMatchesConfig.minSelectionLength] characters that is not all whitespace (and at
 * most 200), every other occurrence of its text gets a [CLASS] mark; with one empty cursor inside a
 * word, the word's other WHOLE-WORD occurrences do. Only in the viewport (editor-compose's
 * [EditorViewport]; before the first paint, 100 lines around the cursor), at most 20,000 characters
 * around the cursor, so a big file (or a megabyte line) costs what the screen shows. More than
 * [SelectionMatchesConfig.maxMatches] matches mark nothing; a "word" over 100 characters is none.
 */
object SelectionMatches {
    const val CLASS = "selection-match"

    private val mark: Decoration = Decoration.Mark(setOf(CLASS))

    val extension: Extension = extensionOf(
        EditorViewport.extension,
        decorationsFacet.compute(FacetDep.Doc, FacetDep.Selection, FacetDep.field(EditorViewport.field), FacetDep.facet(selectionMatchesConfig)) { st -> matches(st) },
    )

    fun extension(config: SelectionMatchesConfig): Extension = extensionOf(extension, selectionMatchesConfig.of(config))

    private val NONE: RangeSet<Decoration> = RangeSet.empty()

    private fun matches(st: EditorState): RangeSet<Decoration> {
        val config = st.facet(selectionMatchesConfig)
        val sel = st.selection
        if (sel.ranges.size > 1) return NONE
        val main = sel.main
        val doc = st.doc
        val query: String
        val wholeWord: Boolean
        if (main.empty) {
            if (!config.highlightWordAroundCursor) return NONE
            val (a, b) = wordAt(st, main.head) ?: return NONE
            query = doc.slice(a, b)
            wholeWord = true
        } else {
            val len = main.to - main.from
            if (len < config.minSelectionLength || len > 200) return NONE
            query = doc.slice(main.from, main.to)
            if (query.indexOf('\n') >= 0 || query.isBlank()) return NONE
            wholeWord = false
        }
        val range = EditorViewport.rangeOf(st)
        var from = maxOf(0, range.first)
        var to = minOf(doc.length, range.last + 1)
        // A viewport can be one huge line (a minified file): search a window around the cursor only.
        if (to - from > MAX_SCAN) {
            from = (main.head - MAX_SCAN / 2).coerceIn(from, to)
            to = minOf(to, from + MAX_SCAN)
        }
        if (to - from < query.length) return NONE
        val text = doc.slice(from, to)
        val out = ArrayList<Ranged<Decoration>>()
        var i = text.indexOf(query)
        while (i >= 0) {
            val a = from + i
            val b = a + query.length
            val ok = !wholeWord || ((a == 0 || !isWordChar(doc.charAt(a - 1))) && (b == doc.length || !isWordChar(doc.charAt(b))))
            // The selection (or the word under the cursor) itself is not marked.
            if (ok && (b <= main.from || a >= main.to) && !(main.empty && a <= main.head && b >= main.head)) {
                out += Ranged(a, b, mark)
                if (out.size > config.maxMatches) return NONE
            }
            i = text.indexOf(query, i + 1)
        }
        return RangeSet.of(out)
    }

    /** Characters searched at most (a viewport on a minified file's one line can be megabytes). */
    private const val MAX_SCAN = 20_000

    /** A "word" longer than this is not one (a line of one repeated letter, a base64 blob). */
    private const val MAX_WORD = 100

    /** The word (letters, digits, `_`) around [pos], touching it on either side, or null. */
    private fun wordAt(st: EditorState, pos: Int): Pair<Int, Int>? {
        val doc = st.doc
        val line = doc.lineIndexAt(pos)
        val lineStart = doc.lineStart(line)
        val lineEnd = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) - 1 else doc.length
        var a = pos
        var b = pos
        while (a > lineStart && pos - a <= MAX_WORD && isWordChar(doc.charAt(a - 1))) a--
        while (b < lineEnd && b - pos <= MAX_WORD && isWordChar(doc.charAt(b))) b++
        return if (b > a && b - a <= MAX_WORD) a to b else null
    }
}
