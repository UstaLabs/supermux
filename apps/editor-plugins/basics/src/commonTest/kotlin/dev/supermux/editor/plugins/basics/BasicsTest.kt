package dev.supermux.editor.plugins.basics

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.indentUnitFacet
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.extensionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Typed text goes through `EditorView.typeText`, the entry point every input path uses (the hidden
 * field of soft and hardware keyboards, the web's key path); a soft Backspace reaches the input
 * handler as the deletion of one unit before the cursor, exactly as the field reports it.
 */
class BasicsTest {
    private fun view(text: String, sel: EditorSelection, vararg ext: dev.supermux.editor.core.Extension) =
        EditorView(EditorState.create(text, sel, extensionOf(basics(), *ext)))

    /** The document with `|` at every cursor and `[`…`]` around every selection. */
    private fun EditorView.show(): String {
        val sb = StringBuilder(state.doc.toString())
        val marks = state.selection.ranges.flatMap { r -> if (r.empty) listOf(r.head to "|") else listOf(r.from to "[", r.to to "]") }
        for ((pos, m) in marks.sortedByDescending { it.first }) sb.insert(pos, m)
        return sb.toString()
    }

    private fun softBackspace(v: EditorView) {
        val h = v.state.selection.main.head
        if (!CloseBrackets.inputHandler.handle(v, h - 1, h, "")) v.typeText("") // not ours: never reached in these tests
    }

    @Test fun bracketsPairWithTheCursorBetween() {
        for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
            val v = view("", EditorSelection.cursor(0))
            v.typeText(open.toString())
            assertEquals("$open|$close", v.show())
        }
    }

    @Test fun aBracketPairsOnlyBeforeSpaceTheEndOrAClosingCharacter() {
        val v = view("f x", EditorSelection.cursor(1))
        v.typeText("(")
        assertEquals("f(|) x", v.show())
        val w = view("fx", EditorSelection.cursor(1))
        w.typeText("(")
        assertEquals("f(|x", w.show(), "a bracket right before a word does not pair")
        val y = view("a)", EditorSelection.cursor(1))
        y.typeText("[")
        assertEquals("a[|])", y.show())
    }

    @Test fun quotesPairOnlyAwayFromWords() {
        val v = view("", EditorSelection.cursor(0))
        v.typeText("\"")
        assertEquals("\"|\"", v.show())
        val w = view("don", EditorSelection.cursor(3))
        w.typeText("'")
        assertEquals("don'|", w.show(), "a quote after a word character does not pair")
        val x = view("ab", EditorSelection.cursor(1))
        x.typeText("`")
        assertEquals("a`|b", x.show())
        val y = view("x = ", EditorSelection.cursor(4))
        y.typeText("'")
        assertEquals("x = '|'", y.show())
    }

    @Test fun typingTheClosingCharacterStepsOverIt() {
        val v = view("", EditorSelection.cursor(0))
        v.typeText("(")
        v.typeText("a")
        v.typeText(")")
        assertEquals("(a)|", v.show())
        v.typeText("\"")
        v.typeText("\"")
        assertEquals("(a)\"\"|", v.show())
        val w = view("x", EditorSelection.cursor(1))
        w.typeText(")")
        assertEquals("x)|", w.show(), "a closer with nothing after it is just typed")
    }

    @Test fun backspaceBetweenAnEmptyPairDeletesBoth() {
        val v = view("a", EditorSelection.cursor(1))
        v.typeText("{")
        assertEquals("a{|}", v.show())
        CloseBrackets.deleteBracketPair.run(v)
        assertEquals("a|", v.show(), "hardware Backspace")
        v.typeText("[")
        softBackspace(v)
        assertEquals("a|", v.show(), "a soft keyboard's Backspace")
        val w = view("(x)", EditorSelection.cursor(2))
        assertFalse(CloseBrackets.deleteBracketPair.run(w), "not between an empty pair: the default Backspace runs")
    }

    @Test fun anOpeningCharacterWrapsTheSelection() {
        val v = view("say hello now", EditorSelection.single(4, 9))
        v.typeText("(")
        assertEquals("say ([hello]) now", v.show())
        v.typeText("\"")
        assertEquals("say (\"[hello]\") now", v.show())
    }

    @Test fun everyCursorDecidesForItself() {
        val v = view("a \nb\nc x", EditorSelection.create(listOf(SelectionRange(2), SelectionRange(4), SelectionRange(7))))
        v.typeText("(")
        // Line 1 and 2: before a line break, paired; line 3: before "x", only the bracket.
        assertEquals("a (|)\nb(|)\nc (|x", v.show())
        v.typeText(")")
        assertEquals("a ()|\nb()|\nc ()|x", v.show())
        val w = view("{}{}", EditorSelection.create(listOf(SelectionRange(1), SelectionRange(3))))
        CloseBrackets.deleteBracketPair.run(w)
        assertEquals("|", w.show(), "both pairs deleted; the two cursors meet and merge")
    }

    @Test fun emojiAroundTheCursor() {
        val v = view("😀", EditorSelection.cursor(2))
        v.typeText("\"")
        assertEquals("😀\"|\"", v.show(), "an emoji is not a word character: the quote pairs")
        v.typeText("x")
        v.typeText("\"")
        assertEquals("😀\"x\"|", v.show())
        val w = view("😀", EditorSelection.cursor(0))
        w.typeText("(")
        assertEquals("(|😀", w.show(), "a bracket before an emoji (not a space or closer) does not pair")
        val y = view("👍🏽", EditorSelection.cursor(0))
        y.typeText("'")
        assertEquals("'|'👍🏽", y.show())
        softBackspace(y)
        assertEquals("|👍🏽", y.show())
    }

    @Test fun enterBetweenBracesOpensAnIndentedBlock() {
        val v = view("  fun f() {}", EditorSelection.cursor(11))
        BlockIndent.insertNewlineAndIndent.run(v)
        assertEquals("  fun f() {\n      |\n  }", v.show(), "one indent unit (4 spaces) deeper than the line's two")
        val tabs = view("\tif {}", EditorSelection.cursor(5), indentUnitFacet.of("\t"))
        BlockIndent.insertNewlineAndIndent.run(tabs)
        assertEquals("\tif {\n\t\t|\n\t}", tabs.show())
        val multi = view("{}\n[]", EditorSelection.create(listOf(SelectionRange(1), SelectionRange(4))))
        BlockIndent.insertNewlineAndIndent.run(multi)
        assertEquals("{\n    |\n}\n[\n    |\n]", multi.show())
    }

    @Test fun aPlainEnterKeepsTheIndentation() {
        val v = view("    val x = 1", EditorSelection.cursor(13))
        assertFalse(BlockIndent.insertNewlineAndIndent.run(v), "not between braces: the default newline")
        dev.supermux.editor.compose.DefaultCommands.insertNewline.run(v)
        assertEquals("    val x = 1\n    |", v.show())
    }

    @Test fun otherLanguagesCanChangeThePairs() {
        val v = view("", EditorSelection.cursor(0), CloseBrackets.extension(CloseBracketsConfig(brackets = listOf('(', '<'))))
        v.typeText("<")
        assertEquals("<|>", v.show())
    }

    @Test fun readOnlyTypesNothing() {
        val v = view("", EditorSelection.cursor(0))
        v.readOnly = true
        v.typeText("(")
        assertEquals("|", v.show())
    }
}
