package dev.supermux.editor.plugins.search

import androidx.compose.ui.text.font.FontFamily
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.Panel
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.panelsFacet
import dev.supermux.editor.core.runKey
import dev.supermux.editor.plugins.fold.Fold
import dev.supermux.editor.plugins.fold.fold
import dev.supermux.editor.plugins.history.History
import dev.supermux.editor.plugins.history.history
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchPluginTest {
    private val text = "val foo = 1\nfoo(foo)\nfood = foo + bar\n"

    private fun view(t: String = text, sel: EditorSelection = EditorSelection.cursor(0), vararg ext: Extension) =
        EditorView(EditorState.create(t, sel, extensionOf(search(), *ext)))

    private val EditorView.doc get() = state.doc.toString()
    private val EditorView.ranges get() = state.selection.ranges.map { it.from to it.to }
    private fun EditorView.query(q: SearchQuery) = Search.setQuery(this, q)

    private fun EditorView.log(): MutableList<Transaction> = ArrayList<Transaction>().also { l -> addListener { l += it } }

    private fun marks(st: EditorState): List<Triple<Int, Int, Set<String>>> = st.facet(decorationsFacet).flatMap { set ->
        set.mapNotNull { r -> (r.value as? Decoration.Mark)?.takeIf { Search.MATCH_CLASS in it.classes }?.let { Triple(r.from, r.to, it.classes) } }
    }

    // ------------------------------------------------------------------ panel and state --

    @Test fun openingThePanelShowsItAndFillsTheFieldFromTheSelection() {
        val v = view(sel = EditorSelection.single(4, 7))
        assertFalse(Search.isOpen(v.state))
        assertEquals(emptyList(), v.state.facet(panelsFacet))
        assertTrue(Search.openSearchPanel.run(v))
        assertTrue(Search.isOpen(v.state))
        assertEquals(listOf(Panel(Search.PANEL, top = true)), v.state.facet(panelsFacet))
        assertEquals("foo", Search.query(v.state).search)
        assertEquals(1, Search.state(v.state).focusRequest, "the panel's field is asked to take the focus")
        // Again, while open: the field takes the focus again (a new request).
        assertTrue(Search.openSearchPanel.run(v))
        assertEquals(2, Search.state(v.state).focusRequest)
    }

    @Test fun anEmptySelectionFillsTheWordAtTheCursor() {
        val v = view(sel = EditorSelection.cursor(text.indexOf("bar") + 1))
        Search.openSearchPanel.run(v)
        assertEquals("bar", Search.query(v.state).search)
    }

    @Test fun aMultiLineSelectionKeepsTheLastQuery() {
        val v = view()
        v.query(SearchQuery("old", caseSensitive = true))
        v.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.single(0, 20)))
        Search.openSearchPanel.run(v)
        assertEquals(SearchQuery("old", caseSensitive = true), Search.query(v.state))
    }

    @Test fun theSelectionIsEscapedForTheQueryItFills() {
        val v = view("a.b(c) x\\ny", EditorSelection.single(0, 6))
        v.query(SearchQuery("", regexp = true))
        Search.openSearchPanel.run(v)
        assertEquals("a\\.b\\(c\\)", Search.query(v.state).search)
        assertEquals(listOf(0 to 6), Search.query(v.state).cursor(v.state.doc).asSequence().map { it.from to it.to }.toList())
        // A literal query escapes only the backslash (its \n would be a line break).
        val w = view("a.b(c) x\\ny", EditorSelection.single(7, 11))
        Search.openSearchPanel.run(w)
        assertEquals("x\\\\ny", Search.query(w.state).search)
        assertEquals(listOf(7 to 11), Search.query(w.state).cursor(w.state.doc).asSequence().map { it.from to it.to }.toList())
    }

    @Test fun closingIsFalseWhenClosed() {
        val v = view()
        assertFalse(Search.closeSearchPanel.run(v))
        Search.openSearchPanel.run(v)
        assertTrue(Search.closeSearchPanel.run(v))
        assertFalse(Search.isOpen(v.state))
        assertEquals(emptyList(), v.state.facet(panelsFacet))
    }

    // ---------------------------------------------------------------------- decorations --

    @Test fun everyMatchIsMarkedAndTheSelectedOneToo() {
        val v = view(sel = EditorSelection.single(4, 7))
        assertEquals(emptyList(), marks(v.state), "nothing marked while the panel is closed")
        Search.openSearchPanel.run(v)
        val m = marks(v.state)
        val at = Regex("foo").findAll(text).map { it.range.first }.toList()
        assertEquals(at, m.map { it.first })
        assertEquals(setOf(Search.MATCH_CLASS, Search.SELECTED_CLASS), m.first { it.first == 4 }.third)
        assertEquals(setOf(Search.MATCH_CLASS), m.first { it.first == 12 }.third)
        Search.closeSearchPanel.run(v)
        assertEquals(emptyList(), marks(v.state))
    }

    @Test fun onlyTheViewportIsMarked() {
        val big = (0 until 5000).joinToString("\n") { "line $it foo" }
        val v = view(big)
        Search.openSearchPanel.run(v)
        v.query(SearchQuery("foo"))
        val m = marks(v.state)
        assertTrue(m.isNotEmpty() && m.size < 400, "marked ${m.size}")
    }

    @Test fun theThemesStyleTheMarksInEveryMode() {
        val f = FontFamily.Monospace
        for (t in listOf(EditorTheme.light(f), EditorTheme.dark(f))) {
            assertNotNull(t.styleOf(Search.MATCH_CLASS)); assertNotNull(t.styleOf(Search.SELECTED_CLASS))
        }
        // A host theme without them, switched to a packaged palette (view settings' theme mode), gets them.
        val host = EditorTheme.dark(f).copy(classStyles = mapOf("diff-add" to androidx.compose.ui.text.SpanStyle()))
        val light = host.withPalette(EditorTheme.light(f))
        assertNotNull(light.styleOf(Search.MATCH_CLASS)); assertNotNull(light.styleOf("diff-add"))
    }

    // ------------------------------------------------------------------------- commands --

    @Test fun findNextAndPreviousMoveBetweenMatches() {
        val v = view()
        v.query(SearchQuery("foo"))
        val log = v.log()
        assertTrue(Search.findNext.run(v))
        assertEquals(listOf(4 to 7), v.ranges)
        assertTrue(log.last().isUserEvent("select.search"))
        assertTrue(log.last().scrollIntoView, "a match is scrolled into view (and revealed in a fold)")
        Search.findNext.run(v)
        assertEquals(listOf(12 to 15), v.ranges)
        Search.findPrevious.run(v)
        assertEquals(listOf(4 to 7), v.ranges)
        Search.findPrevious.run(v)
        assertEquals(listOf(28 to 31), v.ranges, "wraps to the last")
    }

    @Test fun findNextWithoutAQueryOpensThePanel() {
        val v = view()
        assertTrue(Search.findNext.run(v))
        assertTrue(Search.isOpen(v.state))
    }

    @Test fun theKeysAreCm6s() {
        for (apple in listOf(false, true)) {
            val v = view()
            fun key(k: String, shift: Boolean = false, alt: Boolean = false) =
                runKey(v, KeyChord(k, ctrl = !apple, meta = apple, shift = shift, alt = alt), apple)
            assertTrue(key("f")); assertTrue(Search.isOpen(v.state))
            v.query(SearchQuery("foo"))
            assertTrue(key("g")); assertEquals(listOf(4 to 7), v.ranges)
            assertTrue(runKey(v, KeyChord("F3"), apple)); assertEquals(listOf(12 to 15), v.ranges)
            assertTrue(runKey(v, KeyChord("F3", shift = true), apple)); assertEquals(listOf(4 to 7), v.ranges)
            assertTrue(key("g", shift = true)); assertEquals(listOf(28 to 31), v.ranges)
            assertTrue(runKey(v, KeyChord("Escape"), apple)); assertFalse(Search.isOpen(v.state))
            assertFalse(runKey(v, KeyChord("Escape"), apple), "Escape is free again once closed")
            // Select all matches: Alt-Enter, Apple Cmd-Alt-Enter.
            val all = if (apple) KeyChord("Enter", meta = true, alt = true) else KeyChord("Enter", alt = true)
            assertTrue(runKey(v, all, apple)); assertEquals(5, v.state.selection.ranges.size)
        }
    }

    @Test fun selectMatchesMakesACursorPerMatchAndTypingReachesAll() {
        val v = view(sel = EditorSelection.cursor(13))
        v.query(SearchQuery("foo", wholeWord = true))
        val log = v.log()
        assertTrue(Search.selectMatches.run(v))
        assertEquals(listOf(4 to 7, 12 to 15, 16 to 19, 28 to 31), v.ranges)
        assertEquals(1, v.state.selection.mainIndex, "the main range is the match at the cursor")
        assertTrue(log.last().isUserEvent("select.search.matches"))
        v.typeText("x")
        assertEquals("val x = 1\nx(x)\nfood = x + bar\n", v.doc)
    }

    @Test fun selectSelectionMatchesIsCm6sModShiftL() {
        val v = view(sel = EditorSelection.single(12, 15))
        assertTrue(runKey(v, KeyChord("l", ctrl = true, shift = true), false))
        assertEquals(listOf(4 to 7, 12 to 15, 16 to 19, 21 to 24, 28 to 31), v.ranges)
        assertEquals(1, v.state.selection.mainIndex)
    }

    @Test fun replaceNextSelectsThenReplacesAndMovesOn() {
        val v = view()
        v.query(SearchQuery("foo", replace = "baz"))
        val log = v.log()
        assertTrue(Search.replaceNext.run(v))
        assertEquals(text, v.doc, "the first press selects the match")
        assertEquals(listOf(4 to 7), v.ranges)
        assertTrue(Search.replaceNext.run(v))
        assertEquals(text.replaceFirst("foo", "baz"), v.doc)
        assertTrue(log.last().isUserEvent("input.replace"))
        assertEquals(listOf(12 to 15), v.ranges, "the next match is selected")
    }

    @Test fun replaceAllIsOneUndoStep() {
        val v = view(ext = arrayOf(history()))
        v.query(SearchQuery("foo", replace = "q"))
        val log = v.log()
        assertTrue(Search.replaceAll.run(v))
        assertEquals("val q = 1\nq(q)\nqd = q + bar\n", v.doc)
        assertTrue(log.last().isUserEvent("input.replace.all"))
        assertEquals(1, History.undoDepth(v.state))
        assertTrue(History.undo.run(v))
        assertEquals(text, v.doc)
        assertFalse(Search.replaceAll.run(view().also { it.query(SearchQuery("absent")) }))
    }

    @Test fun regexReplaceWithGroups() {
        val v = view("a=1, bb=22, c=3")
        v.query(SearchQuery("(\\w+)=(\\d+)", regexp = true, replace = "$2:$1"))
        Search.replaceAll.run(v)
        assertEquals("1:a, 22:bb, 3:c", v.doc)
        val w = view("x-1 y-2")
        w.query(SearchQuery("(\\w)-(\\d)", regexp = true, replace = "[$&|$$|$2$1]"))
        Search.replaceNext.run(w); Search.replaceNext.run(w)
        assertEquals("[x-1|$|1x] y-2", w.doc)
    }

    // -------------------------------------------------------------------------- Mod-d --

    @Test fun modDSelectsTheWordThenItsNextWholeWordOccurrences() {
        val v = view(sel = EditorSelection.cursor(5)) // in "foo" of "val foo"
        fun modD() = runKey(v, KeyChord("d", ctrl = true), false)
        assertTrue(modD())
        assertEquals(listOf(4 to 7), v.ranges, "an empty cursor selects its word")
        assertTrue(modD())
        assertEquals(listOf(4 to 7, 12 to 15), v.ranges)
        assertEquals(0, v.state.selection.mainIndex, "CM6 keeps the main range")
        assertTrue(modD()); assertTrue(modD())
        // "food" is not the whole word: skipped.
        assertEquals(listOf(4 to 7, 12 to 15, 16 to 19, 28 to 31), v.ranges)
        assertFalse(modD(), "every occurrence is selected")
    }

    @Test fun modDOnANonWordSelectionMatchesSubstringsAndWraps() {
        val v = view(sel = EditorSelection.single(28, 30)) // "fo" of the last "foo"
        fun modD() = runKey(v, KeyChord("d", ctrl = true), false)
        assertTrue(modD())
        assertEquals(listOf(4 to 6, 28 to 30), v.ranges, "wraps to the start")
        assertTrue(modD()); assertTrue(modD()); assertTrue(modD())
        assertEquals(listOf(4 to 6, 12 to 14, 16 to 18, 21 to 23, 28 to 30), v.ranges, "\"fo\" of food too")
        // Ranges with different text: nothing to add (CM6).
        val w = view(sel = EditorSelection.create(listOf(SelectionRange(0, 3), SelectionRange(4, 7))))
        assertFalse(runKey(w, KeyChord("d", ctrl = true), false))
    }

    @Test fun modDNeverGrowsARange() {
        // The wrap-around scan stops before the last range (CM6's `ranges[last].from - 1`): "aa" at 1
        // in "aaaa" has no other occurrence that does not overlap it.
        val v = view("aaaa", EditorSelection.single(1, 3))
        assertFalse(runKey(v, KeyChord("d", ctrl = true), false))
        assertEquals(listOf(1 to 3), v.ranges)
    }

    @Test fun modDScrollsTheNewRangeIntoView() {
        val v = view(sel = EditorSelection.single(4, 7))
        val log = v.log()
        assertTrue(runKey(v, KeyChord("d", ctrl = true), false))
        assertEquals(listOf(15), log.last().effects.mapNotNull { it.valueIf(dev.supermux.editor.compose.EditorEffects.scrollTo) })
    }

    @Test fun modDOnApple() {
        val v = view(sel = EditorSelection.cursor(5))
        assertTrue(runKey(v, KeyChord("d", meta = true), true))
        assertEquals(listOf(4 to 7), v.ranges)
    }

    // --------------------------------------------------------------------------- folds --

    @Test fun aMatchInsideAFoldRevealsIt() {
        val t = "fun a() {\n    hidden needle\n}\nneedle\n"
        val v = view(t, EditorSelection.cursor(0), fold())
        Fold.foldCode.run(v)
        assertEquals(1, Fold.folded(v.state).size)
        v.query(SearchQuery("needle"))
        assertTrue(Search.findNext.run(v))
        val at = t.indexOf("needle")
        assertEquals(listOf(at to at + 6), v.ranges, "the match is selected")
        assertEquals(0, Fold.folded(v.state).size, "its fold opened")
    }

    @Test fun replaceAllReachesIntoFolds() {
        val t = "fun a() {\n    x needle\n}\nneedle\n"
        val v = view(t, EditorSelection.cursor(0), fold(), history())
        Fold.foldCode.run(v)
        v.query(SearchQuery("needle", replace = "pin"))
        val fold = Fold.folded(v.state).single()
        assertTrue(Search.replaceAll.run(v))
        assertEquals("fun a() {\n    x pin\n}\npin\n", v.doc)
        assertTrue(Fold.folded(v.state).isEmpty(), "the fold holding a match opened")
        assertEquals(1, History.undoDepth(v.state), "opening the fold and replacing are ONE undo step")
        assertTrue(History.undo.run(v))
        assertEquals(t, v.doc)
        assertEquals(listOf(fold), Fold.folded(v.state), "undo folds it again")
        assertTrue(History.redo.run(v))
        assertEquals("fun a() {\n    x pin\n}\npin\n", v.doc)
    }

    @Test fun readOnlyReplacesNothingAndUnfoldsNothing() {
        val t = "fun a() {\n    x needle\n}\nneedle\n"
        val v = view(t, EditorSelection.cursor(0), fold())
        v.readOnly = true
        Fold.foldCode.run(v)
        v.query(SearchQuery("needle", replace = "pin"))
        v.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.single(t.lastIndexOf("needle"), t.lastIndexOf("needle") + 6)))
        assertFalse(Search.replaceNext.run(v))
        assertFalse(Search.replaceAll.run(v))
        assertEquals(t, v.doc)
        assertEquals(1, Fold.folded(v.state).size, "nothing unfolded")
    }

    @Test fun anEmptyMatchAtTheCursorIsReplaced() {
        // CM6: "^" with the cursor at a line start: replace prefixes that line, then the next.
        val v = view("a\nb\n")
        v.query(SearchQuery("^", regexp = true, replace = "> "))
        assertTrue(Search.replaceNext.run(v))
        assertEquals("> a\nb\n", v.doc)
        assertTrue(Search.replaceNext.run(v))
        assertEquals("> a\n> b\n", v.doc)
        // Replace all prefixes every line, the empty last one too (CM6).
        val w = view("a\nb\n")
        w.query(SearchQuery("^", regexp = true, replace = "> "))
        assertTrue(Search.replaceAll.run(w))
        assertEquals("> a\n> b\n> ", w.doc)
    }

    // ------------------------------------------------------------------------ go to line --

    @Test fun gotoLineTakesCm6sSyntax() {
        val st = EditorState.create((1..100).joinToString("\n") { "line $it" }, EditorSelection.cursor(0))
        val l10 = st.doc.line(10).from
        assertEquals(EditorSelection.cursor(l10), Search.gotoLineSelection(st, "10"))
        assertEquals(EditorSelection.cursor(st.doc.line(4).from), Search.gotoLineSelection(st, "+3"))
        assertEquals(EditorSelection.cursor(st.doc.line(50).from), Search.gotoLineSelection(st, "50%"))
        assertEquals(EditorSelection.cursor(l10 + 3), Search.gotoLineSelection(st, "10:3"))
        assertEquals(EditorSelection.cursor(st.doc.line(100).from), Search.gotoLineSelection(st, "999"))
        assertEquals(EditorSelection.cursor(l10 + 7), Search.gotoLineSelection(st, "10:99"), "the column is clamped to the line")
        assertNull(Search.gotoLineSelection(st, "ten"))
        // Numbers too big for an Int go to the last line (its end for a column), never an overflow.
        assertEquals(EditorSelection.cursor(st.doc.line(100).from), Search.gotoLineSelection(st, "99999999999"))
        assertEquals(EditorSelection.cursor(st.doc.line(100).from), Search.gotoLineSelection(st, "+99999999999999999999"))
        assertEquals(EditorSelection.cursor(0), Search.gotoLineSelection(st, "-99999999999"))
        assertEquals(EditorSelection.cursor(l10 + 7), Search.gotoLineSelection(st, "10:99999999999"))
    }

    @Test fun anInvalidLineKeepsTheGotoPanelOpen() {
        val v = view((1..30).joinToString("\n") { "l$it" })
        Search.gotoLine.run(v)
        assertFalse(Search.goToLine(v, "ten"))
        assertEquals(listOf(Panel(Search.GOTO_PANEL, top = true)), v.state.facet(panelsFacet))
        assertEquals(listOf(0 to 0), v.ranges)
    }

    @Test fun gotoLineOpensItsPanelAndGoes() {
        val v = view((1..30).joinToString("\n") { "l$it" })
        assertTrue(runKey(v, KeyChord("g", ctrl = true, alt = true), false))
        assertEquals(listOf(Panel(Search.GOTO_PANEL, top = true)), v.state.facet(panelsFacet))
        val log = v.log()
        assertTrue(Search.goToLine(v, "12"))
        assertEquals(listOf(v.state.doc.line(12).from).map { it to it }, v.ranges)
        assertTrue(log.first().scrollIntoView)
        assertEquals(emptyList(), v.state.facet(panelsFacet), "the dialog closed")
    }

    // ------------------------------------------------------------------------ the count --

    @Test fun matchInfoSaysWhichOfHowMany() {
        val v = view(sel = EditorSelection.single(12, 15))
        v.query(SearchQuery("foo"))
        assertEquals(MatchInfo(2, MatchCount(5, false)), Search.matchInfo(v.state))
        assertEquals("2 of 5", Search.matchInfo(v.state).label)
        v.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.cursor(0)))
        assertEquals("5 matches", Search.matchInfo(v.state).label)
        v.query(SearchQuery("absent"))
        assertEquals("No results", Search.matchInfo(v.state).label)
        v.query(SearchQuery("a(", regexp = true))
        assertTrue(Search.matchInfo(v.state).label.startsWith("Invalid"))
        assertEquals("1 match", Search.matchInfo(view("x").also { it.query(SearchQuery("x")) }.state).label)
    }
}
