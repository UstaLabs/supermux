package dev.supermux.editor.plugins.basics

import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.decorationsFacet

/**
 * The active line (CM6's `highlightActiveLine`): a `LineStyle` [CLASS] on the line of every cursor
 * whose range is empty (one per line). The theme paints it in `EditorTheme.currentLine`; the surface
 * has no built-in current-line highlight of its own any more.
 */
object ActiveLine {
    const val CLASS = "active-line"

    private val style: Decoration = Decoration.LineStyle(setOf(CLASS))

    val extension: Extension = decorationsFacet.compute(FacetDep.Doc, FacetDep.Selection) { st ->
        val doc = st.doc
        val out = ArrayList<Ranged<Decoration>>(st.selection.ranges.size)
        var last = -1
        for (r in st.selection.ranges) {
            if (!r.empty) continue
            val start = doc.lineStart(doc.lineIndexAt(r.head))
            if (start != last) { out += Ranged(start, start, style); last = start }
        }
        RangeSet.of(out)
    }
}
