package dev.supermux.editor.compose

import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoldsTest {
    private val text = (0 until 12).joinToString("\n") { "line $it" } // "line N" = 6 units, 7 per line
    private fun st(vararg r: Ranged<Decoration>) = EditorState.create(text, extensions = decorationsFacet.of(RangeSet.of(r.toList())))
    private fun fold(from: Int, to: Int, id: String = "f") = Ranged(from, to, Decoration.Replace(WidgetKey("fold", id)) as Decoration)
    private fun inline(at: Int, side: Int, id: String = "i") = Ranged(at, at, Decoration.InlineWidget(WidgetKey("chip", id), side) as Decoration)
    private fun EditorState.lineEnd(l: Int) = doc.lineStart(l + 1) - 1

    @Test fun aFoldHidesTheLinesAfterItsFirstUpToItsLast() {
        val s = EditorState.create(text)
        val state = st(fold(s.lineEnd(2), s.lineEnd(5)))
        val f = Folds.of(state)
        assertEquals(listOf(3..5), f.hidden)
        assertTrue(f.isHidden(3) && f.isHidden(5))
        assertFalse(f.isHidden(2) || f.isHidden(6))
        assertEquals(2, f.visualLine(4))
        assertEquals(5, f.lastJoined(2))
        assertEquals(listOf(0, 1, 2, 6, 7), f.shownLines(0..7))
    }

    @Test fun aFoldsRowIsItsFirstLineThenThePlaceholderThenTheLastLinesTail() {
        val s = EditorState.create(text)
        // From inside line 2 to inside line 4: "line 2" -> "li" + [fold] + "e 4"
        val a = s.doc.lineStart(2) + 2
        val b = s.doc.lineStart(4) + 3
        val f = Folds.of(st(fold(a, b)))
        assertEquals(listOf(3..4), f.hidden)
        val parts = f.parts(s.doc.lineStart(2), s.lineEnd(4))!!
        assertEquals(listOf(true, false, true), parts.map { it.isText })
        val map = LineMap(parts)
        assertEquals(2 + 1 + 3, map.layoutLength)
        assertEquals(2, map.toLayout(a), "the fold's start: before the placeholder")
        assertEquals(2, map.toLayout(a + 3), "inside: at the start")
        assertEquals(3, map.toLayout(b), "the fold's end: after the placeholder")
        assertEquals(6, map.toLayout(s.lineEnd(4)))
        assertEquals(a, map.toDoc(2))
        assertEquals(b, map.toDoc(3))
        assertEquals(s.doc.lineStart(2), map.toDoc(0))
        assertEquals(s.lineEnd(4), map.toDoc(6))
        assertNull(f.parts(s.doc.lineStart(7), s.lineEnd(7)), "a line without widgets is plain")
    }

    @Test fun anInlineWidgetsSideDecidesWhereTheCaretAtItsPointIs() {
        val s = EditorState.create(text)
        val at = s.doc.lineStart(1) + 3
        for ((side, expected) in listOf(1 to 3, -1 to 4)) {
            val f = Folds.of(st(inline(at, side)))
            val map = LineMap(f.parts(s.doc.lineStart(1), s.lineEnd(1))!!)
            assertEquals(7, map.layoutLength)
            assertEquals(expected, map.toLayout(at), "side $side")
            assertEquals(at - 1, map.toDoc(2))
            assertEquals(at, map.toDoc(3), "the widget's character")
            assertEquals(at, map.toDoc(4), "right after the widget")
            assertEquals(at + 1, map.toDoc(5))
            assertEquals(5, map.toLayout(at + 1))
        }
    }

    @Test fun overlappingReplacesMergeAndSwallowTheirInlineWidgets() {
        val s = EditorState.create(text)
        val f = Folds.of(st(fold(s.lineEnd(1), s.lineEnd(4), "a"), fold(s.lineEnd(3), s.lineEnd(6), "b"), inline(s.doc.lineStart(3), 1)))
        assertEquals(1, f.replaces.size)
        assertEquals(s.lineEnd(6), f.replaces[0].to)
        assertEquals("a", f.replaces[0].widget?.id)
        assertEquals(listOf(2..6), f.hidden)
        assertTrue(f.inline.isEmpty(), "an inline widget inside a fold is hidden")
        assertEquals(f.replaces[0], f.replaceInside(s.doc.lineStart(4)))
        assertNull(f.replaceInside(s.lineEnd(1)), "the start is outside")
        assertNull(f.replaceInside(s.lineEnd(6)), "the end is outside")
    }

    @Test fun twoFoldsInARowJoinIntoOneHiddenRun() {
        val s = EditorState.create(text)
        val f = Folds.of(st(fold(s.lineEnd(1), s.lineEnd(3), "a"), fold(s.lineEnd(3), s.lineEnd(5), "b")))
        assertEquals(listOf(2..5), f.hidden)
        assertEquals(1, f.visualLine(5))
        val parts = f.parts(s.doc.lineStart(1), s.lineEnd(5))!!
        assertEquals(listOf("text", "a", "b"), parts.map { if (it.isText) "text" else it.widget!!.id })
    }
}
