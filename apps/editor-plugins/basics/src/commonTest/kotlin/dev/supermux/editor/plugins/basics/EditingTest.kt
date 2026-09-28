package dev.supermux.editor.plugins.basics

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommentTokens
import dev.supermux.editor.core.CommentTokensProvider
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.SelectParentService
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.commentTokensFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapFacet
import dev.supermux.editor.core.selectParentFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CM6 `defaultKeymap` parity (M5 B1): the line, comment, bracket and syntax commands, at every cursor, one transaction each. */
class EditingTest {
    private val slashes = commentTokensFacet.of(CommentTokensProvider { _, _ -> CommentTokens("//", CommentTokens.BlockComment("/*", "*/")) })
    private val blockOnly = commentTokensFacet.of(CommentTokensProvider { _, _ -> CommentTokens(null, CommentTokens.BlockComment("/*", "*/")) })

    /** `|` a cursor, `«`…`»` a selection (anchor at `«`). */
    private fun view(marked: String, vararg ext: Extension): EditorView {
        val text = StringBuilder()
        val ranges = ArrayList<SelectionRange>()
        var anchor = -1
        for (c in marked) when (c) {
            '|' -> ranges += SelectionRange(text.length)
            '«' -> anchor = text.length
            '»' -> { ranges += SelectionRange(anchor, text.length); anchor = -1 }
            else -> text.append(c)
        }
        val sel = if (ranges.isEmpty()) EditorSelection.cursor(0) else EditorSelection.create(ranges, 0)
        return EditorView(EditorState.create(text.toString(), sel, extensionOf(basics(), *ext)))
    }

    private fun EditorView.show(): String {
        val sb = StringBuilder(state.doc.toString())
        val marks = state.selection.ranges.flatMap { r -> if (r.empty) listOf(r.head to "|") else listOf(r.from to "«", r.to to "»") }
        for ((pos, m) in marks.sortedByDescending { it.first }) sb.insert(pos, m)
        return sb.toString()
    }

    /** Run [cmd]; the number of transactions that changed the document (one undo step each). */
    private fun EditorView.run(cmd: Command): Int {
        var edits = 0
        val remove = addListener { if (it.docChanged) edits++ }
        cmd.run(this)
        remove()
        return edits
    }

    @Test fun moveLineDownAndUp() {
        val v = view("a\nb|\nc")
        assertEquals(1, v.run(Editing.moveLineDown))
        assertEquals("a\nc\nb|", v.show())
        assertFalse(Editing.moveLineDown.run(v))             // the last line: nowhere to go
        v.run(Editing.moveLineUp); v.run(Editing.moveLineUp)
        assertEquals("b|\na\nc", v.show())
    }

    @Test fun moveLineMovesEveryCursorsLineInOneStep() {
        val v = view("a|\nb\nc|\nd")
        assertEquals(1, v.run(Editing.moveLineDown))
        assertEquals("b\na|\nd\nc|", v.show())
    }

    @Test fun aSelectionMovesItsWholeLinesAndStaysSelected() {
        val v = view("x\n«a\nb»\ny")
        v.run(Editing.moveLineUp)
        assertEquals("«a\nb»\nx\ny", v.show())
    }

    @Test fun copyLineDownPutsTheCursorOnTheLowerCopy() {
        val v = view("ab|\ncd")
        assertEquals(1, v.run(Editing.copyLineDown))
        assertEquals("ab\nab|\ncd", v.show())
        val u = view("ab|\ncd")
        u.run(Editing.copyLineUp)
        assertEquals("ab|\nab\ncd", u.show())
    }

    @Test fun deleteLineKeepsTheColumnOnTheNextLine() {
        val v = view("a\nb|b\nc")
        assertEquals(1, v.run(Editing.deleteLine))
        assertEquals("a\nc|", v.show())
        val last = view("a\nb|")
        last.run(Editing.deleteLine)
        assertEquals("a|", last.show())
        val two = view("x|\ny\nz|")
        assertEquals(1, two.run(Editing.deleteLine))
        assertEquals("y|", two.show())
    }

    @Test fun selectLineSelectsTheLineWithItsBreak() {
        val v = view("ab\nc|d\nef")
        v.run(Editing.selectLine)
        assertEquals("ab\n«cd\n»ef", v.show())
    }

    @Test fun insertBlankLineOpensAnIndentedLineBelow() {
        val v = view("  ab|cd\nx")
        assertEquals(1, v.run(Editing.insertBlankLine))
        assertEquals("  abcd\n  |\nx", v.show())
        val two = view("a|\nb|")
        two.run(Editing.insertBlankLine)
        assertEquals("a\n|\nb\n|", two.show())
    }

    @Test fun cursorMatchingBracketJumpsBothWays() {
        val v = view("|(a)")
        assertTrue(Editing.cursorMatchingBracket.run(v))
        assertEquals("(a)|", v.show())
        val back = view("(a|)")
        back.run(Editing.cursorMatchingBracket)
        assertEquals("(|a)", back.show())
        assertFalse(Editing.cursorMatchingBracket.run(view("a|b")))
    }

    @Test fun toggleCommentCommentsLinesAtTheirCommonIndentAndBack() {
        val v = view("«  a\n    b»", slashes)
        assertEquals(1, v.run(Editing.toggleComment))
        assertEquals("  // a\n  //   b", v.state.doc.toString())
        v.run(Editing.toggleComment)
        assertEquals("  a\n    b", v.state.doc.toString())
    }

    @Test fun toggleCommentSkipsBlankLinesInABlock() {
        val v = view("«a\n\nb»", slashes)
        v.run(Editing.toggleComment)
        assertEquals("// a\n\n// b", v.state.doc.toString())
    }

    @Test fun toggleCommentFallsBackToABlockCommentAroundTheLines() {
        val v = view("  a|", blockOnly)
        v.run(Editing.toggleComment)
        assertEquals("  /* a */", v.state.doc.toString())
        v.run(Editing.toggleComment)
        assertEquals("  a", v.state.doc.toString())
    }

    @Test fun withoutCommentTokensNothingHappens() {
        val v = view("a|")
        assertFalse(Editing.toggleComment.run(v))
        assertFalse(Editing.toggleBlockComment.run(v))
        assertEquals("a", v.state.doc.toString())
    }

    @Test fun toggleBlockCommentWrapsAndUnwrapsTheSelection() {
        val v = view("x «ab» y", slashes)
        assertEquals(1, v.run(Editing.toggleBlockComment))
        assertEquals("x /* ab */ y", v.state.doc.toString())
        val u = view("x /* «ab» */ y", slashes)
        u.run(Editing.toggleBlockComment)
        assertEquals("x ab y", u.state.doc.toString())
    }

    @Test fun selectParentSyntaxAsksTheLanguageLayer() {
        assertFalse(Editing.selectParentSyntax.run(view("a|")))       // no syntax tree: nothing
        var asked = 0
        val v = view("a|", selectParentFacet.of(SelectParentService { asked++; true }))
        assertTrue(Editing.selectParentSyntax.run(v))
        assertEquals(1, asked)
    }

    @Test fun theBindingsAreCm6s() {
        val keys = view("a").state.facet(keymapFacet)
        fun bound(cmd: Command, apple: Boolean) = keys.filter { it.command === cmd }.map { it.chord(apple) }
        assertTrue(KeyChord.parse("Alt-ArrowUp", false) in bound(Editing.moveLineUp, false))
        assertTrue(KeyChord.parse("Shift-Alt-ArrowDown", false) in bound(Editing.copyLineDown, false))
        assertTrue(KeyChord.parse("Shift-Mod-k", false) in bound(Editing.deleteLine, false))
        assertTrue(KeyChord.parse("Mod-/", true) in bound(Editing.toggleComment, true))
        assertTrue(KeyChord.parse("Shift-Alt-a", false) in bound(Editing.toggleBlockComment, false))
        assertTrue(KeyChord.parse("Alt-l", false) in bound(Editing.selectLine, false))
        assertTrue(KeyChord.parse("Ctrl-l", true) in bound(Editing.selectLine, true))
        assertTrue(KeyChord.parse("Mod-i", false) in bound(Editing.selectParentSyntax, false))
        assertTrue(KeyChord.parse("Shift-Mod-\\", false) in bound(Editing.cursorMatchingBracket, false))
        assertTrue(KeyChord.parse("Mod-Enter", false) in bound(Editing.insertBlankLine, false))
    }
}
