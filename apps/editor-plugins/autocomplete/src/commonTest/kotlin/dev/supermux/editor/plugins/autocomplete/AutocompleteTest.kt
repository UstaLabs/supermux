package dev.supermux.editor.plugins.autocomplete

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.runKey
import dev.supermux.editor.plugins.history.History
import dev.supermux.editor.plugins.history.history
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AutocompleteTest {
    private val words = listOf("println", "print", "printf", "private", "protected", "process")

    /** A word source: the identifier before the cursor, [words], valid while typing word characters. */
    private class WordSource(val words: List<String>, val validFor: Regex? = Regex("\\w*")) : CompletionSource {
        var calls = 0
        val contexts = ArrayList<CompletionContext>()
        var gate: CompletableDeferred<Unit>? = null
        var cancelled = 0
        override suspend fun complete(context: CompletionContext): CompletionResult? {
            calls++
            contexts += context
            try { gate?.await() } catch (e: kotlin.coroutines.cancellation.CancellationException) { cancelled++; throw e }
            val from = context.wordStart()
            if (from == context.pos && !context.explicit && context.triggerCharacter == null) return null
            return CompletionResult(from, words.map { Completion(it, type = "function") }, validFor = validFor)
        }
    }

    private fun view(text: String, sel: EditorSelection, src: CompletionSource, cfg: AutocompleteConfig = AutocompleteConfig(interactionDelay = 0), vararg ext: dev.supermux.editor.core.Extension) =
        EditorView(EditorState.create(text, sel, extensionOf(autocompletion(cfg, override = listOf(src)), *ext)))

    private fun key(v: EditorView, spec: String) = runKey(v, KeyChord.parse(spec, isApplePlatform), isApplePlatform)

    private fun TestScope.started(v: EditorView) { val stop = v.startPlugins(backgroundScope); stops += stop }
    private val stops = ArrayList<() -> Unit>()

    /** Background work (the plugins' runner) is not "idle" work for the test scheduler: advance the clock past every delay. */
    private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }

    private val EditorView.labels get() = Autocomplete.state(state).options.map { it.completion.label }
    private val EditorView.text get() = state.doc.toString()

    @Test fun typingOpensAfterTheDelayAndFiltersWithoutAskingAgain() = runTest {
        val src = WordSource(words)
        val v = view("", EditorSelection.cursor(0), src)
        started(v)
        v.typeText("p"); v.typeText("r")
        advanceTimeBy(50); runCurrent()
        assertFalse(Autocomplete.isOpen(v.state), "open before the typing delay")
        advanceTimeBy(100); runCurrent()
        assertTrue(Autocomplete.isOpen(v.state))
        assertEquals(1, src.calls, "one ask after the burst, not one per key")
        assertEquals(6, v.labels.size)
        v.typeText("i")
        settle()
        assertEquals(listOf("print", "printf", "println", "private"), v.labels, "filtered (protected and process do not match) and sorted: equal scores by label")
        assertEquals(1, src.calls, "still valid: filtered, not asked again")
        v.typeText(" ")
        settle()
        assertFalse(Autocomplete.isOpen(v.state), "a space leaves the word")
    }

    @Test fun navigateAcceptAndEscapeThroughTheKeymap() = runTest {
        val src = WordSource(words)
        val v = view("", EditorSelection.cursor(0), src)
        started(v)
        v.typeText("pri"); settle()
        assertEquals(0, Autocomplete.state(v.state).selected)
        assertTrue(key(v, "ArrowDown")); assertTrue(key(v, "ArrowDown"))
        assertEquals("println", Autocomplete.state(v.state).selectedOption?.completion?.label)
        assertTrue(key(v, "ArrowUp"))
        assertTrue(key(v, "ArrowUp")); assertTrue(key(v, "ArrowUp"))
        assertEquals("private", Autocomplete.state(v.state).selectedOption?.completion?.label, "wraps to the last")
        assertTrue(key(v, "Escape"))
        assertFalse(Autocomplete.isOpen(v.state))
        assertFalse(key(v, "Escape"), "closed: Escape goes on")
        assertTrue(key(v, "Ctrl-Space")); settle()
        assertTrue(src.contexts.last().explicit, "Ctrl-Space asks explicitly")
        assertTrue(key(v, "Enter"))
        assertEquals("print", v.text)
        assertEquals(5, v.state.selection.main.head)
        assertFalse(Autocomplete.isOpen(v.state))
        assertFalse(key(v, "ArrowDown"), "closed: arrows are the editor's")
    }

    @Test fun tabAcceptsAndCanBeTurnedOff() = runTest {
        val v = view("", EditorSelection.cursor(0), WordSource(words))
        started(v)
        v.typeText("prot"); settle()
        assertTrue(key(v, "Tab"))
        assertEquals("protected", v.text)
        val w = view("", EditorSelection.cursor(0), WordSource(words), AutocompleteConfig(interactionDelay = 0, acceptOnTab = false))
        started(w)
        w.typeText("prot"); settle()
        assertFalse(key(w, "Tab"))
    }

    @Test fun theInteractionDelayKeepsAFastEnterANewline() = runTest {
        val v = view("", EditorSelection.cursor(0), WordSource(words), AutocompleteConfig(interactionDelay = 10_000))
        started(v)
        v.typeText("pri"); settle()
        assertTrue(Autocomplete.isOpen(v.state))
        assertFalse(key(v, "Enter"), "just opened: Enter is not the list's")
    }

    @Test fun triggerCharactersAskOutsideAWord() = runTest {
        val src = WordSource(listOf("length", "size"))
        val v = view("", EditorSelection.cursor(0), src, AutocompleteConfig(interactionDelay = 0, triggerCharacters = setOf('.')))
        started(v)
        v.typeText("list"); settle()
        v.dispatch(TransactionSpec(effects = listOf(Autocomplete.close.of(Unit))))
        val before = src.calls
        v.typeText("."); settle()
        assertEquals(before + 1, src.calls)
        assertEquals(".", src.contexts.last().triggerCharacter)
        assertEquals(listOf("length", "size"), v.labels, "all options after the dot")
        v.typeText("s"); settle()
        assertEquals(listOf("size"), v.labels)
    }

    @Test fun typingCancelsASourceCallInFlight() = runTest {
        val src = WordSource(words)
        src.gate = CompletableDeferred()
        val v = view("", EditorSelection.cursor(0), src, AutocompleteConfig(interactionDelay = 0, activateOnTypingDelay = 0))
        started(v)
        v.typeText("p"); runCurrent()
        assertEquals(1, src.calls)
        src.gate = CompletableDeferred()
        v.typeText("r"); runCurrent()
        assertEquals(1, src.cancelled, "the first call was cancelled by the keystroke")
        src.gate!!.complete(Unit); settle()
        assertTrue(Autocomplete.isOpen(v.state))
    }

    @Test fun anAnswerForAnOlderDocumentIsMappedOrDropped() = runTest {
        val src = WordSource(words)
        src.gate = CompletableDeferred()
        val v = view("x pr", EditorSelection.cursor(4), src)
        started(v)
        assertTrue(Autocomplete.startCompletion.run(v)); runCurrent()
        // A remote edit before the word while the source works: the answer is mapped.
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "abc ")), userEvent = "remote"))
        src.gate!!.complete(Unit); settle()
        val s = Autocomplete.state(v.state)
        assertTrue(s.open)
        assertEquals(6, s.from, "mapped past the inserted text")
        // Asked again, then the cursor goes before the word start: dropped.
        v.dispatch(TransactionSpec(effects = listOf(Autocomplete.close.of(Unit))))
        src.gate = CompletableDeferred()
        Autocomplete.startCompletion.run(v); runCurrent()
        v.dispatch(TransactionSpec(selection = EditorSelection.cursor(0)))
        src.gate!!.complete(Unit); settle()
        assertFalse(Autocomplete.isOpen(v.state))
    }

    @Test fun aSourceThatThrowsNeverBreaksTheEditor() = runTest {
        val v = view("", EditorSelection.cursor(0), CompletionSource { error("boom") })
        started(v)
        v.typeText("pr"); settle()
        assertFalse(Autocomplete.isOpen(v.state))
        assertEquals("pr", v.text)
    }

    @Test fun aSnippetWithTwoFieldsAndAFinalStop() = runTest {
        val snippet = Snippet.fromLsp("for (\${1:i} in \${2:list}) {\n\t$0\n}")
        val src = CompletionSource { c -> CompletionResult(c.wordStart(), listOf(Completion("for", apply = CompletionApply.Template(snippet)))) }
        val v = view("  ", EditorSelection.cursor(2), src)
        started(v)
        v.typeText("fo"); settle()
        assertTrue(key(v, "Enter"))
        assertEquals("  for (i in list) {\n      \n  }", v.text, "indented like its line, a tab is one indent unit")
        val sel = v.state.selection.main
        assertEquals("i", v.state.sliceDoc(sel.from, sel.to), "the first field is selected")
        v.typeText("item")
        assertTrue(key(v, "Tab"))
        val s2 = v.state.selection.main
        assertEquals("list", v.state.sliceDoc(s2.from, s2.to))
        assertTrue(key(v, "Shift-Tab"))
        assertEquals("item", v.state.sliceDoc(v.state.selection.main.from, v.state.selection.main.to), "back to the first, edited")
        assertTrue(key(v, "Tab")); assertTrue(key(v, "Tab"))
        assertEquals(v.text.indexOf("\n") + 7, v.state.selection.main.head, "the final stop inside the body")
        assertNull(Snippets.active(v.state), "the last stop ends the snippet")
        assertFalse(key(v, "Tab"), "Tab is the editor's again")
    }

    @Test fun movingOutOfAFieldEndsTheSnippet() = runTest {
        val src = CompletionSource { c -> CompletionResult(c.wordStart(), listOf(Completion("f", apply = CompletionApply.Template(Snippet.parse("f(\${a}, \${b})"))))) }
        val v = view("", EditorSelection.cursor(0), src)
        started(v)
        v.typeText("f"); settle()
        key(v, "Enter")
        assertEquals("f(a, b)", v.text)
        assertNotNull(Snippets.active(v.state))
        assertTrue(Snippets.active(v.state)!!.ranges.isNotEmpty())
        v.dispatch(TransactionSpec(selection = EditorSelection.cursor(0), userEvent = "select"))
        assertNull(Snippets.active(v.state))
    }

    @Test fun acceptAppliesAtEveryCursorWithTheSameText() = runTest {
        val src = WordSource(words)
        val v = view("pr\nxx pr\nzz", EditorSelection.create(listOf(SelectionRange(2), SelectionRange(8), SelectionRange(11)), 0), src)
        started(v)
        assertTrue(Autocomplete.startCompletion.run(v)); settle()
        assertTrue(Autocomplete.accept(v, Autocomplete.state(v.state).options.indexOfFirst { it.completion.label == "private" }))
        assertEquals("private\nxx private\nzz", v.text, "the third cursor's text differs: left alone")
        assertEquals(listOf(7, 18, 21), v.state.selection.ranges.map { it.head })
    }

    @Test fun anAcceptIsOneUndoStep() = runTest {
        val v = view("", EditorSelection.cursor(0), WordSource(words), AutocompleteConfig(interactionDelay = 0), history())
        started(v)
        v.typeText("p"); v.typeText("r")
        settle()
        key(v, "Enter")
        assertEquals("print", v.text)
        assertTrue(History.undo.run(v))
        assertEquals("pr", v.text, "the accept alone is undone")
        assertTrue(History.redo.run(v))
        assertEquals("print", v.text)
    }

    @Test fun additionalEditsAndTextEditsApplyInOneTransaction() = runTest {
        val src = CompletionSource { c ->
            CompletionResult(c.wordStart(), listOf(Completion("List", apply = CompletionApply.WithEdits("List", listOf(ChangeSpec(0, 0, "import List\n"))))))
        }
        val v = view("val x: Li", EditorSelection.cursor(9), src, AutocompleteConfig(interactionDelay = 0), history())
        started(v)
        Autocomplete.startCompletion.run(v); settle()
        key(v, "Enter")
        assertEquals("import List\nval x: List", v.text)
        assertEquals(v.text.length, v.state.selection.main.head)
        History.undo.run(v)
        assertEquals("val x: Li", v.text)
    }

    @Test fun selectingAnOptionResolvesItsInfo() = runTest {
        var resolved = 0
        val src = CompletionSource { c ->
            CompletionResult(c.wordStart(), listOf(
                Completion("alpha", resolveInfo = { resolved++; "docs of alpha" }),
                Completion("alpine", info = "static docs"),
            ))
        }
        val v = view("", EditorSelection.cursor(0), src)
        started(v)
        v.typeText("al"); settle()
        assertEquals("alpha", Autocomplete.state(v.state).selectedOption?.completion?.label)
        assertEquals("docs of alpha", Autocomplete.state(v.state).info?.second)
        assertEquals(1, resolved)
    }

    @Test fun overlappingExtraEditsNeverThrowTheFirstWins() = runTest {
        val src = CompletionSource { c ->
            CompletionResult(c.wordStart(), listOf(
                Completion("List", apply = CompletionApply.WithEdits("List", listOf(ChangeSpec(0, 0, "import A\n"), ChangeSpec(0, 3, "x"), ChangeSpec(1, 2, "y")))),
                Completion("Loop", apply = CompletionApply.WithEdits("for (\${1:i}) {}", listOf(ChangeSpec(0, 3, "a"), ChangeSpec(2, 5, "b")), snippet = true)),
            ))
        }
        val v = view("val Li", EditorSelection.cursor(6), src)
        started(v)
        Autocomplete.startCompletion.run(v); settle()
        assertTrue(Autocomplete.accept(v, Autocomplete.state(v.state).options.indexOfFirst { it.completion.label == "List" }))
        assertEquals("import A\nx List", v.text, "overlapping extras: the first of each overlap applied, no exception")
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, v.state.doc.length, "val Lo")), selection = EditorSelection.cursor(6)))
        Autocomplete.startCompletion.run(v); settle()
        assertTrue(Autocomplete.accept(v, Autocomplete.state(v.state).options.indexOfFirst { it.completion.label == "Loop" }), "a snippet's overlapping extras too")
    }

    @Test fun anExtraEditIsMappedThroughLaterEditsAndDroppedWhenItFallsOut() = runTest {
        // The source answers for "ab x"; its extra edit appends at the END of THAT text.
        val src = CompletionSource { c ->
            val end = c.state.doc.length
            CompletionResult(c.wordStart(), listOf(Completion("xyz", apply = CompletionApply.WithEdits("xyz", listOf(ChangeSpec(end, end, " // imported"))))), validFor = Regex("\\w*"))
        }
        val v = view("ab x", EditorSelection.cursor(4), src)
        started(v)
        Autocomplete.startCompletion.run(v); settle()
        // A Backspace before the tap: the document is one shorter than the source's.
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(3, 4)), selection = EditorSelection.cursor(3), userEvent = "delete.backward"))
        v.typeText("x")
        settle()
        assertTrue(Autocomplete.accept(v, 0), "no exception: the extra edit was mapped")
        assertEquals("ab xyz // imported", v.text)
    }

    @Test fun codeMirrorOrderingFixtures() {
        // Ties: localeCompare-like (case-insensitive, then lower first; punctuation, digits, letters).
        val labels = listOf("b", "A", "a", "B", "_x", "1a", "Ab", "ab")
        assertEquals(listOf("_x", "1a", "a", "A", "ab", "Ab", "b", "B"), labels.sortedWith { x, y -> Autocomplete.localeCompare(x, y) })
        // CM6's scores for the same pattern over labels in order (its matcher's buffers are sticky).
        // Fixtures from CM6 itself (its FuzzyMatcher run under node, one matcher per pattern as CM6 does).
        val m = FuzzyMatcher("gt")
        assertEquals(listOf(-307, null, -108, -703), listOf("getText", "gutter", "get_type", "xgt").map { m.match(it)?.score })
        val m2 = FuzzyMatcher("gt")
        assertEquals(listOf(-703, -307, null, -108), listOf("xgt", "getText", "gutter", "get_type").map { m2.match(it)?.score })
        val m3 = FuzzyMatcher("gtt")
        assertEquals(
            listOf(-1307 to listOf(0, 1, 2, 4), -1306 to listOf(0, 1, 2, 4), -1308 to listOf(0, 1, 2, 3, 4, 5), -1305 to listOf(0, 1, 2, 3, 4, 5), -105 to listOf(0, 1, 2, 3, 4, 5)),
            listOf("getText", "gutter", "get_type", "gxtxt", "g_t_t").map { w -> m3.match(w)!!.let { it.score to it.ranges.toList() } },
        )
    }

    @Test fun snippetChoicesVariablesAndALiteralHashBrace() {
        val st = EditorState.create("")
        val (a, ra) = Snippet.fromLsp("\${1|one,two|} \$TM_FILENAME \${TM_LINE_NUMBER:7} #{x}", mapOf("TM_FILENAME" to "main.kt")).instantiate(st, 0)
        assertEquals("one main.kt 7 #{x}", a)
        assertEquals(listOf(FieldRange(0, 0, 3)), ra.filter { it.field == 0 })
        assertEquals(1, ra.size, "#{x} is text, not a field")
        val (b, _) = Snippet.fromLsp("\${1|a\\,b,c|}\$UNKNOWN!").instantiate(st, 0)
        assertEquals("a,b!", b, "an escaped comma in a choice; an unknown variable is empty")
    }

    @Test fun deletingAcrossAFieldEndsTheSnippet() = runTest {
        val src = CompletionSource { c -> CompletionResult(c.wordStart(), listOf(Completion("f", apply = CompletionApply.Template(Snippet.parse("f(\${a}, \${b})"))))) }
        val v = view("", EditorSelection.cursor(0), src)
        started(v)
        v.typeText("f"); settle()
        key(v, "Enter")
        assertNotNull(Snippets.active(v.state))
        // Typing over the selected field keeps it.
        v.typeText("x")
        assertNotNull(Snippets.active(v.state))
        // A deletion across the field's start (the "(" and the field) ends it: CM6's TrackDel.
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(1, 3)), selection = EditorSelection.cursor(1), userEvent = "delete.backward"))
        assertNull(Snippets.active(v.state))
    }

    @Test fun theFuzzyMatcherFollowsCm6() {
        fun score(p: String, w: String) = FuzzyMatcher(p).match(w)?.score
        assertEquals(0, score("print", "print"))
        assertEquals(FuzzyMatcher.NOT_FULL, score("pr", "print"), "a prefix: not full")
        assertNotNull(FuzzyMatcher("gTB").match("getTextBounds"), "by word starts")
        assertEquals(listOf(0, 1, 3, 4, 7, 8), FuzzyMatcher("gTB").match("getTextBounds")!!.ranges.toList())
        assertNull(FuzzyMatcher("x").match("abx"), "one character: only at the start")
        assertNotNull(FuzzyMatcher("P").match("print"), "case folded")
        assertTrue(score("pr", "print")!! > score("pr", "PRINT")!!, "exact case wins")
        assertTrue(score("int", "print")!! < score("pri", "print")!!, "not at the start is worse")
        assertNull(FuzzyMatcher("zq").match("print"))
        assertNotNull(FuzzyMatcher("İst").match("İstanbul"), "Turkish capital I")
        assertNotNull(FuzzyMatcher("😀a").match("😀ab"), "astral characters")
    }
}
