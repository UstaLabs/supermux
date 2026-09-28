package dev.supermux.editor.plugins.basics

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.EditorViewport
import dev.supermux.editor.compose.lineNumbersFacet
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.TokenContext
import dev.supermux.editor.core.TokenContextProvider
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.tokenContextFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** basics+: the active line, bracket matching, indent on input, selection matches, line numbers. */
class BasicsPlusTest {
    /** A view of [marked]: `|` is a cursor, `«`...`»` a selection (anchor at `«`). */
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

    /** Every decoration with class [cls]: (from, to) pairs. */
    private fun EditorState.marked(cls: String): List<Pair<Int, Int>> = facet(decorationsFacet).flatMap { set ->
        set.filter { r ->
            when (val v = r.value) {
                is Decoration.Mark -> cls in v.classes
                is Decoration.LineStyle -> cls in v.classes
                else -> false
            }
        }.map { it.from to it.to }
    }.sortedBy { it.first }

    // ------------------------------------------------------------------------ active line --

    @Test fun theActiveLineIsEveryEmptyCursorsLine() {
        val v = view("one\ntw|o\nthree\nfo|ur")
        assertEquals(listOf(4 to 4, 14 to 14), v.state.marked("active-line"))
        // Two cursors on one line mark it once; a non-empty range marks nothing.
        val w = view("o|n|e\n«two»\nthree")
        assertEquals(listOf(0 to 0), w.state.marked("active-line"))
    }

    @Test fun theActiveLineFollowsTheCursor() {
        val v = view("a\nb|\nc")
        v.dispatch(TransactionSpec(selection = EditorSelection.cursor(5), userEvent = "select"))
        assertEquals(listOf(4 to 4), v.state.marked("active-line"))
    }

    // -------------------------------------------------------------------- bracket matching --

    @Test fun aBracketBeforeOrAfterTheCursorMatchesItsPartner() {
        assertEquals(listOf(1 to 2, 6 to 7), view("f(a, b)|").state.marked("matching-bracket"))
        assertEquals(listOf(1 to 2, 6 to 7), view("f|(a, b)").state.marked("matching-bracket"))
        assertEquals(listOf(1 to 2, 6 to 7), view("f(|a, b)").state.marked("matching-bracket"))
        assertEquals(listOf(0 to 1, 6 to 7), view("{ [x] |}").state.marked("matching-bracket"))
        // Nested: the partner at the same depth.
        assertEquals(listOf(0 to 1, 8 to 9), view("(a (b) c)|").state.marked("matching-bracket"))
        assertTrue(view("ab|cd").state.marked("matching-bracket").isEmpty())
    }

    @Test fun aWrongOrMissingPartnerIsNonmatching() {
        assertEquals(listOf(0 to 1, 3 to 4), view("(ab]|").state.marked("nonmatching-bracket"))
        // No partner before the document's end: the bracket alone.
        assertEquals(listOf(0 to 1), view("|(abc").state.marked("nonmatching-bracket"))
    }

    @Test fun everyCursorGetsItsMatch() {
        val v = view("(a)| [b]|")
        assertEquals(listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7), v.state.marked("matching-bracket"))
    }

    @Test fun emojiAndSurrogatesAreNotBrackets() {
        assertEquals(listOf(0 to 1, 5 to 6), view("(😀👍)|").state.marked("matching-bracket"))
    }

    @Test fun bracketsInsideAStringAreIgnoredWithTheSyntaxHook() {
        val text = "f(\"(\", x)"
        val quoted = text.indexOf("\"(\"")..(text.indexOf("\"(\"") + 2)
        val strings = tokenContextFacet.of(TokenContextProvider { _, pos -> if (pos in quoted) TokenContext.STRING else TokenContext.CODE })
        val v = view("$text|", strings)
        assertEquals(listOf(1 to 2, text.length - 1 to text.length), v.state.marked("matching-bracket"))
        // Without the hook the string's "(" is taken for the partner.
        assertTrue(view("$text|").state.marked("matching-bracket").none { it.first == 1 })
    }

    @Test fun theScanStopsAfter10kCharacters() {
        val far = "(" + "x".repeat(20_000) + ")"
        assertTrue(view("$far|").state.let { it.marked("matching-bracket") + it.marked("nonmatching-bracket") }.isEmpty())
        val near = "(" + "x".repeat(9_000) + ")"
        assertEquals(2, view("$near|").state.marked("matching-bracket").size)
    }

    // ---------------------------------------------------------------------- indent on input --

    @Test fun aClosingBraceAsTheLinesFirstCharacterTakesItsOpenersIndentation() {
        val v = view("fun f() {\n    x\n        |")
        v.typeText("}")
        assertEquals("fun f() {\n    x\n}|", v.show())
        val w = view("    if (a) {\n        b\n    |")
        w.typeText("}")
        assertEquals("    if (a) {\n        b\n    }|", w.show())
        val t = view("\tlist = [\n\t\t1,\n|")
        t.typeText("]")
        assertEquals("\tlist = [\n\t\t1,\n\t]|", t.show())
        val p = view("call(\n    a,\n  |")
        p.typeText(")")
        assertEquals("call(\n    a,\n)|", p.show())
    }

    @Test fun aClosingBraceAfterTextOrWithoutAnOpenerIsJustTyped() {
        val v = view("{\n    a |")
        v.typeText("}")
        assertEquals("{\n    a }|", v.show())
        val w = view("    x\n        |")
        w.typeText("}")
        assertEquals("    x\n        }|", w.show())
    }

    @Test fun indentOnInputAtEveryCursor() {
        val v = view("a {\n        |\n}\nb {\n  |")
        v.typeText("}")
        assertEquals("a {\n}|\n}\nb {\n}|", v.show())
    }

    @Test fun twoCursorsInOneLinesIndentationReindentItOnce() {
        // Both cursors in the same line's leading whitespace: the line is re-indented once (by the
        // first cursor), the other types the closer as it is. This threw "overlapping changes".
        val v = view("a {\n  |  |")
        v.typeText("}")
        assertEquals("a {\n}|  }|", v.show())
    }

    @Test fun multiCursorClosersNeverThrow() {
        val rnd = kotlin.random.Random(7)
        repeat(400) {
            val lines = (0 until rnd.nextInt(1, 6)).map { " ".repeat(rnd.nextInt(0, 6)) + listOf("", "{", "(", "[", "x", "}").random(rnd) + " ".repeat(rnd.nextInt(0, 3)) }
            val text = lines.joinToString("\n")
            val cursors = (0 until rnd.nextInt(1, 5)).map { SelectionRange(rnd.nextInt(0, text.length + 1)) }
            val v = EditorView(EditorState.create(text, EditorSelection.create(cursors, 0), extensionOf(basics())))
            val typed = listOf("}", ")", "]").shuffled(rnd).take(rnd.nextInt(1, 4))
            for (c in typed) v.typeText(c)
            fun closers(s: String) = s.count { it in "})]" }
            assertTrue(closers(v.state.doc.toString()) >= closers(text) + typed.size, "the closers were not typed into '$text' at $cursors")
        }
    }

    @Test fun indentOnInputIsOneUndoStepWithTheBrace() {
        val v = view("{\n    x\n    |")
        v.typeText("}")
        assertEquals("{\n    x\n}", v.state.doc.toString())
        // One transaction: the brace and the re-indent together (history undoes both at once).
        var n = 0
        val w = view("{\n    x\n    |")
        w.addListener { if (it.docChanged) n++ }
        w.typeText("}")
        assertEquals(1, n)
    }

    // -------------------------------------------------------------------- selection matches --

    @Test fun aSelectedWordMarksItsOtherOccurrences() {
        val v = view("val «foo» = foo + foobar\nfoo()")
        assertEquals(listOf(10 to 13, 16 to 19, 23 to 26), v.state.marked("selection-match"))
    }

    @Test fun tooShortBlankMultilineOrSeveralRangesMarkNothing() {
        assertTrue(view("«a» a a").state.marked("selection-match").isEmpty(), "one character")
        assertTrue(view("x«  »x  x  ").state.marked("selection-match").isEmpty(), "blank")
        assertTrue(view("«ab\nab» ab\nab").state.marked("selection-match").isEmpty(), "multi-line")
        assertTrue(view("«ab» «ab» ab").state.marked("selection-match").isEmpty(), "two ranges")
    }

    @Test fun theWordUnderTheCursorMarksWholeWordOccurrences() {
        val v = view("val fo|o = foo + foobar + xfoo")
        assertEquals(listOf(10 to 13), v.state.marked("selection-match"))
    }

    @Test fun selectionMatchesStayInTheViewport() {
        val line = "foo bar\n"
        val text = line.repeat(1000)
        val v = EditorView(EditorState.create(text, EditorSelection.single(0, 3), extensionOf(basics())))
        // The surface says lines 10..20 are on screen.
        v.dispatch(TransactionSpec(effects = listOf(EditorViewport.set.of(line.length * 10 until line.length * 20))))
        val marks = v.state.marked("selection-match")
        assertEquals(10, marks.size)
        assertTrue(marks.all { it.first >= line.length * 10 && it.second <= line.length * 20 })
    }

    @Test fun moreThan100MatchesMarkNothing() {
        // CM6's maxMatches: a selection that is everywhere is not worth painting.
        val v = view("«ab» " + "ab ".repeat(150))
        assertTrue(v.state.marked("selection-match").isEmpty())
    }

    // ------------------------------------------------------------------------ line numbers --

    @Test fun lineNumbersIsAFacetTheHostCanToggle() {
        assertEquals(null, view("x").state.facet(lineNumbersFacet))
        assertEquals(false, view("x", lineNumbers(false)).state.facet(lineNumbersFacet))
        assertEquals(true, view("x", lineNumbers(true)).state.facet(lineNumbersFacet))
    }

    // --------------------------------------------------------------------------- big files --

    @Test fun aMegabyteLineOfOneWordNeverStallsSelectionMatches() {
        // "x" a million times, the cursor in the middle, the whole line on screen: every position
        // is a candidate, and the "word" under the cursor is enormous.
        val text = "x".repeat(1_000_000)
        val v = EditorView(EditorState.create(text, EditorSelection.cursor(500_000), extensionOf(basics())))
        v.dispatch(TransactionSpec(effects = listOf(EditorViewport.set.of(0 until text.length))))
        val t0 = TimeSource.Monotonic.markNow()
        repeat(20) { v.typeText("x") }
        val ms = t0.elapsedNow().inWholeMilliseconds
        assertTrue(ms < 400, "20 keystrokes on a 1 MB one-word line took $ms ms")
        // A selection there is bounded too.
        val t1 = TimeSource.Monotonic.markNow()
        v.dispatch(TransactionSpec(selection = EditorSelection.single(10, 60), userEvent = "select"))
        assertTrue(t1.elapsedNow().inWholeMilliseconds < 100)
    }

    @Test fun aLargeFileNeverStalls() {
        val text = "fun f(x: Int) { return (x + 1) * [2, 3].size }\n".repeat(40_000) // ~1.9 MB
        val t0 = TimeSource.Monotonic.markNow()
        val v = EditorView(EditorState.create(text, EditorSelection.cursor(text.length / 2), extensionOf(basics())))
        repeat(50) { v.typeText("(") ; v.typeText("x") }
        v.dispatch(TransactionSpec(selection = EditorSelection.single(4, 5), userEvent = "select"))
        val ms = t0.elapsedNow().inWholeMilliseconds
        assertTrue(ms < 3000, "basics took $ms ms for 100 keystrokes in a 1.9 MB file")
    }
}
