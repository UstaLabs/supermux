package dev.supermux.editor.compose

import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A screen reader reads a fold's row as it is shown: its head, "N lines folded", its tail; never the hidden text. */
class AccessibleFoldTest {
    private val text = "fun f() {\n  hidden 1\n  hidden 2\n}\nafter"
    private val from = text.indexOf('{') + 1
    private val to = text.indexOf("}\nafter")

    private fun build(): AccessibleText {
        val st = EditorState.create(text, extensions = decorationsFacet.of(RangeSet.of(listOf(Ranged(from, to, Decoration.Replace(WidgetKey("fold", "f"), fold = true) as Decoration)))))
        val folds = Folds.of(st)
        return AccessibleText.build(st.doc, 0 until st.doc.lineCount, 0, { folds.isHidden(it) }, folds)
    }

    @Test fun theFoldsRowIsItsHeadALabelAndItsTail() {
        val t = build()
        assertEquals("fun f() {" + EditorSemantics.folded(3) + "}\nafter", t.text)
        assertTrue("hidden" !in t.text)
        // The tail maps to the document.
        val tail = t.text.indexOf('}')
        assertEquals(to, t.toDoc(tail))
        assertEquals(tail, t.toText(to))
        assertEquals(from, t.toDoc(t.text.indexOf('{') + 1))
    }

    @Test fun anEditAcrossTheLabelIsRefused() {
        val t = build()
        val l = t.text.indexOf("lines folded")
        assertNull(t.mapRange(l, l + 3), "an edit of the label would delete hidden text")
        val tail = t.text.indexOf('}')
        assertEquals(to to to + 1, t.mapRange(tail, tail + 1))
    }
}
