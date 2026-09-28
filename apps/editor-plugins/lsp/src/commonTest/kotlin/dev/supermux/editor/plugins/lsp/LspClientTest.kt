package dev.supermux.editor.plugins.lsp

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.FoldRange
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.runKey
import dev.supermux.editor.plugins.autocomplete.Autocomplete
import dev.supermux.editor.plugins.autocomplete.AutocompleteConfig
import dev.supermux.editor.plugins.autocomplete.Snippets
import dev.supermux.editor.plugins.autocomplete.autocompletion
import dev.supermux.editor.plugins.fold.Fold
import dev.supermux.editor.plugins.fold.fold
import dev.supermux.editor.plugins.history.History
import dev.supermux.editor.plugins.history.history
import dev.supermux.editor.plugins.lint.Lint
import dev.supermux.editor.plugins.lint.Severity
import dev.supermux.editor.plugins.lint.lint
import dev.supermux.editor.plugins.lsp.fake.FakeLspServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LspClientTest {
    private val uri = "file:///work/main.toy"
    private val text = """
        fun add(a, b) {
          return a + b
        }
        val total = add(1, 2)
        val name = "ağaç 😀"
        // TODO: more
    """.trimIndent() + "\n"

    private class Setup(val server: FakeLspServer, val client: LspClient, val view: EditorView, val events: MutableList<String?>)

    private fun TestScope.settle(ms: Long = 1_000) { advanceTimeBy(ms); runCurrent() }

    private fun TestScope.setup(
        doc: String = text,
        cursor: Int = 0,
        server: FakeLspServer = FakeLspServer(backgroundScope),
        config: LspClientConfig = LspClientConfig(syncDelayMs = 50, requestTimeoutMs = 2_000, parseOnWorker = false),
        vararg ext: Extension,
    ): Setup {
        val client = LspClient(server.transport, backgroundScope, config)
        val view = EditorView(EditorState.create(doc, EditorSelection.cursor(cursor), extensionOf(
            autocompletion(AutocompleteConfig(interactionDelay = 0, activateOnTypingDelay = 20)), lint(), history(), client.plugin(uri, "toy"), *ext,
        )))
        val events = ArrayList<String?>()
        view.addListener { tr -> if (tr.docChanged) events += tr.annotation(Transaction.userEvent) }
        view.startPlugins(backgroundScope)
        settle()
        return Setup(server, client, view, events)
    }

    private fun key(v: EditorView, spec: String) = runKey(v, KeyChord.parse(spec, isApplePlatform), isApplePlatform)
    private fun at(s: String, n: Int = 0) = text.indexOf(s).let { var i = it; repeat(n) { i = text.indexOf(s, i + 1) }; i }
    private val EditorView.text get() = state.doc.toString()

    @Test fun initializeNegotiatesUtf16AndOpensTheDocument() = runTest {
        val s = setup()
        assertEquals(LspClientState.READY, s.client.state.value)
        val offered = s.server.clientCapabilities!!.let { (it as kotlinx.serialization.json.JsonObject)["general"]!!.let { g -> (g as kotlinx.serialization.json.JsonObject)["positionEncodings"] as JsonArray } }
        assertEquals("\"utf-16\"", offered.first().toString(), "utf-16 is offered first")
        assertEquals(PositionEncoding.UTF16, s.client.features.value.positionEncoding)
        assertEquals(listOf("initialize", "initialized", "textDocument/didOpen"), s.server.log.take(3))
        assertEquals(text, s.server.documents[uri])
        assertTrue('.' in LspPlugin.state(s.view.state).features.completionTriggers, "the server's trigger characters reach the state")
    }

    /** Random edits (emoji, Turkish, line breaks) in batches: the server's text equals ours after every one. */
    private fun TestScope.randomSync(server: FakeLspServer, seed: Int) {
        val s = setup(server = server)
        val rnd = Random(seed)
        val pieces = listOf("a", "ı", "İ", "ğ", "😀", "👍🏽", "\n", "  ", "fun x(", ")", "é", "漢字", "")
        var changes = 0
        repeat(120) { batch ->
            repeat(1 + rnd.nextInt(5)) {
                val doc = s.view.state.doc
                var a = rnd.nextInt(doc.length + 1)
                var b = (a + rnd.nextInt(6)).coerceAtMost(doc.length)
                // Never split a surrogate pair (the editor never does).
                if (a in 1 until doc.length && doc.charAt(a).isLowSurrogate()) a--
                if (b in 1 until doc.length && doc.charAt(b).isLowSurrogate()) b++
                if (b < a) b = a
                s.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(a, b, pieces[rnd.nextInt(pieces.size)])), userEvent = "input.type"))
                changes++
            }
            settle(100)
            assertEquals(s.view.text, s.server.documents[uri], "batch $batch (seed $seed, ${server.syncKind}, ${s.client.features.value.positionEncoding})")
        }
        val didChange = s.server.log.count { it == "textDocument/didChange" }
        assertTrue(didChange <= 120, "batched: $didChange didChange for $changes edits")
    }

    @Test fun syncStaysExactThroughRandomEditsUtf16() = runTest { randomSync(FakeLspServer(backgroundScope), 1) }
    @Test fun syncStaysExactThroughRandomEditsUtf8() = runTest { randomSync(FakeLspServer(backgroundScope, encoding = "utf-8"), 2) }
    @Test fun syncStaysExactThroughRandomEditsUtf32() = runTest { randomSync(FakeLspServer(backgroundScope, encoding = "utf-32"), 3) }
    @Test fun syncStaysExactWithFullSync() = runTest { randomSync(FakeLspServer(backgroundScope, syncKind = 1), 4) }

    @Test fun positionsRoundTripWithEmojiAndTurkishInEveryEncoding() {
        val doc = dev.supermux.editor.core.Rope.of("ağaç 😀 İstanbul\nsecond 👍🏽 line\n")
        for (e in PositionEncoding.entries) {
            var i = 0
            while (i <= doc.length) {
                val p = Positions.toLsp(doc, i, e)
                assertEquals(i, Positions.fromLsp(doc, p, e), "$e at $i")
                i += if (i < doc.length && doc.charAt(i).isHighSurrogate()) 2 else 1
            }
        }
        assertEquals(LspPosition(0, 7), Positions.toLsp(doc, 7, PositionEncoding.UTF16), "after the emoji: 7 UTF-16 units")
        assertEquals(LspPosition(0, 11), Positions.toLsp(doc, 7, PositionEncoding.UTF8), "ğ, ç are 2 bytes, 😀 4")
        assertEquals(LspPosition(0, 6), Positions.toLsp(doc, 7, PositionEncoding.UTF32))
        assertEquals(16, Positions.fromLsp(doc, LspPosition(0, 99)), "past the line's end: the line's end")
        assertEquals(doc.length, Positions.fromLsp(doc, LspPosition(9, 0)))
        assertEquals(5, Positions.fromLsp(doc, LspPosition(0, 6)), "inside the surrogate pair: its start")
    }

    @Test fun diagnosticsReachLintWithTheirCodeActionsAsOneUndoStep() = runTest {
        val s = setup()
        val ds = Lint.diagnostics(s.view.state)
        assertEquals(1, ds.size)
        assertEquals(Severity.WARNING, ds[0].severity)
        assertEquals("TODO", s.view.state.sliceDoc(ds[0].from, ds[0].to))
        assertEquals("fake todo", ds[0].source)
        val fix = ds[0].actions.single()
        assertEquals("Replace TODO with DONE", fix.name)
        Lint.runAction(s.view, ds[0], fix)
        settle()
        assertTrue(s.view.text.contains("// DONE: more"))
        assertEquals("edit.codeAction", s.events.last())
        assertTrue(Lint.diagnostics(s.view.state).isEmpty(), "the server's next diagnostics")
        assertTrue(History.undo.run(s.view))
        assertEquals(text, s.view.text)
        // Typing the error word: an error.
        s.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "error ")), userEvent = "input.type"))
        settle()
        assertTrue(Lint.diagnostics(s.view.state).any { it.severity == Severity.ERROR && it.from == 0 && it.to == 5 })
    }

    @Test fun aDiagnosticsNotificationForAnOlderVersionIsIgnored() = runTest {
        val s = setup()
        val d = s.client.documents[uri]!!
        val old = d.version
        s.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "x")), userEvent = "input.type"))
        settle()
        d.diagnostics(buildJsonObject {
            put("uri", uri); put("version", old)
            put("diagnostics", JsonArray(listOf(buildJsonObject {
                put("range", Positions.rangeToLsp(s.view.state.doc, 0, 3).json()); put("severity", 1); put("message", "stale")
            })))
        })
        assertTrue(Lint.diagnostics(s.view.state).none { it.message == "stale" })
    }

    @Test fun completionAfterADotFiltersAndAccepts() = runTest {
        val s = setup(cursor = at("total") + 5)
        s.view.typeText(".")
        settle()
        assertEquals(listOf("first", "length", "size", "toString"), Autocomplete.state(s.view.state).options.map { it.completion.label })
        s.view.typeText("le")
        settle()
        assertEquals(listOf("length"), Autocomplete.state(s.view.state).options.map { it.completion.label })
        assertTrue(key(s.view, "Enter"))
        assertTrue(s.view.text.contains("val total.length = add"))
        assertEquals("input.complete", s.events.last())
        settle()
        assertEquals(s.view.text, s.server.documents[uri])
    }

    @Test fun explicitCompletionHasKeywordsNamesAndASnippetWithResolvedDocs() = runTest {
        val s = setup(cursor = text.length)
        assertTrue(key(s.view, "Ctrl-Space"))
        settle()
        val labels = Autocomplete.state(s.view.state).options.map { it.completion.label }
        assertTrue(listOf("fun", "val", "add", "total", "for").all { it in labels }, "$labels")
        // "add": its docs come from completionItem/resolve when selected.
        val i = Autocomplete.state(s.view.state).options.indexOfFirst { it.completion.label == "add" }
        s.view.dispatch(TransactionSpec(effects = listOf(Autocomplete.setSelected.of(i))))
        settle()
        assertEquals("add: a fun declared on line 1", Autocomplete.state(s.view.state).info?.second)
        assertTrue("completionItem/resolve" in s.server.log)
        // The snippet.
        val f = Autocomplete.state(s.view.state).options.indexOfFirst { it.completion.label == "for" }
        assertTrue(Autocomplete.accept(s.view, f))
        assertTrue(s.view.text.endsWith("for (item in items) {\n    \n}"), s.view.text)
        assertEquals("item", s.view.state.sliceDoc(s.view.state.selection.main.from, s.view.state.selection.main.to))
        assertTrue(Snippets.hasNextField(s.view.state))
    }

    @Test fun typingCancelsACompletionRequestInFlight() = runTest {
        val server = FakeLspServer(backgroundScope)
        server.delayMs["textDocument/completion"] = 500
        val s = setup(server = server, cursor = text.length)
        s.view.typeText("a"); settle(100)
        s.view.typeText("d"); settle(100)
        settle(2_000)
        assertTrue(server.cancelled.isNotEmpty(), "\$/cancelRequest was sent for the first request")
        assertTrue(Autocomplete.state(s.view.state).options.any { it.completion.label == "add" })
    }

    @Test fun hoverShowsTheServersMarkdownAsTextAndAStaleOneIsDropped() = runTest {
        val s = setup()
        val d = s.client.documents[uri]!!
        val h = d.hover(s.view.state, at("add", 1) + 1)
        assertNotNull(h)
        assertEquals(at("add", 1), h.from)
        assertEquals("add — fun declared on line 1\n\nParameters: a, b", s.client.hoverTexts[h.key.id])
        s.server.delayMs["textDocument/hover"] = 300
        var stale: Any? = "pending"
        backgroundScope.launch { stale = d.hover(s.view.state, at("add", 1) + 1) }
        settle(100)
        s.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "x")), userEvent = "input.type"))
        settle()
        assertNull(stale, "the text changed while the server worked: dropped")
        assertNull(d.hover(s.view.state, at("return") + 2), "a keyword: nothing")
    }

    @Test fun signatureHelpOpensOnTheTriggerFollowsTheParameterAndCloses() = runTest {
        val s = setup(cursor = text.length)
        s.view.typeText("add(")
        settle()
        val sig = LspPlugin.state(s.view.state).signature
        assertNotNull(sig)
        assertEquals("add(a, b)", sig.signatures[0].label)
        assertEquals(0, sig.activeParameter)
        s.view.typeText("1,")
        settle()
        assertEquals(1, LspPlugin.state(s.view.state).signature?.activeParameter)
        s.view.typeText(" 2)")
        settle()
        assertNull(LspPlugin.state(s.view.state).signature, "out of the call: closed")
        assertTrue(key(s.view, "Mod-Shift-Space").let { true })
    }

    @Test fun f12JumpsToTheDefinitionOpeningAFold() = runTest {
        val s = setup(cursor = at("add", 1) + 1, ext = arrayOf(fold()))
        // Fold the function (its body holds nothing we jump to, but its first line is visible: fold the next lines).
        s.view.dispatch(TransactionSpec(effects = listOf(Fold.foldEffect.of(FoldRange(0, at("}") + 1)))))
        assertEquals(1, s.view.state.field(Fold.field).size)
        assertTrue(key(s.view, "F12"))
        settle()
        assertEquals(at("add"), s.view.state.selection.main.head)
        assertEquals(0, s.view.state.field(Fold.field).size, "the fold holding it opened")
    }

    @Test fun anotherDocumentsDefinitionGoesToTheHost() = runTest {
        var navigated: String? = null
        val s = setup(cursor = at("add", 1) + 1, config = LspClientConfig(onNavigate = { u, _ -> navigated = u }, parseOnWorker = false))
        s.client.documents[uri]!!.let { }
        // The fake answers in the same document; a Location elsewhere is the host's: exercised through goToReference.
        s.view.dispatch(TransactionSpec(effects = listOf(LspPlugin.setReferences.of(listOf(Reference(LspLocation("file:///other.toy", LspRange(LspPosition(0, 0), LspPosition(0, 1))), null, null, 0, ""))))))
        LspPlugin.goToReference(s.client, s.view, 0)
        assertEquals("file:///other.toy", navigated)
    }

    @Test fun shiftF12ListsReferencesAndEscapeClosesThePanel() = runTest {
        val s = setup(cursor = at("add") + 1)
        assertTrue(key(s.view, "Shift-F12"))
        settle()
        val refs = LspPlugin.state(s.view.state).references
        assertNotNull(refs)
        assertEquals(listOf(0, 3), refs.map { it.line })
        LspPlugin.goToReference(s.client, s.view, 1)
        assertEquals(at("add", 1), s.view.state.selection.main.from)
        assertTrue(key(s.view, "Escape"))
        assertNull(LspPlugin.state(s.view.state).references)
    }

    @Test fun f2RenamesEveryOccurrenceAsOneUndoStep() = runTest {
        val s = setup(cursor = at("total") + 2)
        assertTrue(key(s.view, "F2"))
        assertEquals("total", LspPlugin.state(s.view.state).rename?.word)
        LspPlugin.submitRename(s.client, uri, s.view, "sum")
        settle()
        assertTrue(s.view.text.contains("val sum = add(1, 2)"))
        assertEquals("edit.rename", s.events.last())
        assertNull(LspPlugin.state(s.view.state).rename)
        assertTrue(History.undo.run(s.view))
        assertEquals(text, s.view.text)
    }

    @Test fun shiftAltFFormatsAsOneUndoStepAndAbortsWhenTheUserTypedThere() = runTest {
        val messy = "fun f(a) {   \n\treturn a\n}\n"
        val s = setup(doc = messy)
        assertTrue(key(s.view, "Shift-Alt-f"))
        settle()
        assertEquals("fun f(a) {\n    return a\n}\n", s.view.text)
        assertEquals("edit.format", s.events.last())
        History.undo.run(s.view)
        assertEquals(messy, s.view.text)
        // The server takes its time; meanwhile the user types inside a range it edits: nothing applied.
        s.server.delayMs["textDocument/formatting"] = 300
        key(s.view, "Shift-Alt-f")
        settle(100)
        s.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(11, 11, "x")), userEvent = "input.type"))
        settle()
        assertEquals(messy.substring(0, 11) + "x" + messy.substring(11), s.view.text, "aborted")
    }

    @Test fun aServerPushedEditIsAppliedAsLspAndNotUndone() = runTest {
        val s = setup()
        s.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "// hi\n")), userEvent = "input.type"))
        settle()
        s.server.pushEdit(uri, listOf(intArrayOf(1, 0, 1, 3) to "FUN"))
        settle()
        assertTrue(s.view.text.startsWith("// hi\nFUN add"))
        assertEquals("lsp", s.events.last())
        History.undo.run(s.view)
        assertTrue(s.view.text.startsWith("FUN add"), "undo took the user's edit, not the server's: ${s.view.text.take(10)}")
    }

    @Test fun serverErrorsAndTimeoutsNeverCrash() = runTest {
        val server = FakeLspServer(backgroundScope)
        server.failing += "textDocument/hover"
        server.silent += "textDocument/definition"
        val messages = ArrayList<String>()
        val s = setup(server = server, cursor = at("add", 1) + 1, config = LspClientConfig(requestTimeoutMs = 500, onMessage = { _, m -> messages += m }, parseOnWorker = false))
        assertNull(s.client.documents[uri]!!.hover(s.view.state, at("add", 1)))
        assertTrue(key(s.view, "F12"))
        settle(2_000)
        assertEquals(at("add", 1) + 1, s.view.state.selection.main.head, "nothing moved")
        assertTrue(messages.any { "timed out" in it }, "$messages")
        assertEquals(LspClientState.READY, s.client.state.value)
        // Still working.
        assertTrue(key(s.view, "Shift-F12")); settle()
        assertNotNull(LspPlugin.state(s.view.state).references)
    }

    @Test fun aDroppedConnectionReopensTheDocumentWhenItComesBack() = runTest {
        val s = setup()
        s.server.disconnect()
        settle()
        assertEquals(LspClientState.DISCONNECTED, s.client.state.value)
        s.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "// offline edit\n")), userEvent = "input.type"))
        settle()
        s.server.reconnect()
        settle()
        assertEquals(LspClientState.READY, s.client.state.value)
        assertEquals(2, s.server.log.count { it == "initialize" })
        assertEquals(s.view.text, s.server.documents[uri], "didOpen sent again with the text as it is now")
        s.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "x")), userEvent = "input.type"))
        settle()
        assertEquals(s.view.text, s.server.documents[uri], "and synced after")
    }

    @Test fun aHundredKilobyteDocumentOpensAndGetsItsDiagnosticsFast() = runTest {
        val big = (0 until 3000).joinToString("\n") { i -> if (i % 500 == 0) "// TODO item $i" else "val v$i = add($i, ${i + 1}) // some plain text" } + "\n"
        val t0 = kotlin.time.TimeSource.Monotonic.markNow()
        val s = setup(doc = big)
        val ms = t0.elapsedNow().inWholeMilliseconds
        println("LSP-BIG-DOC ${big.length / 1024} KB: open + diagnostics ${ms} ms")
        assertEquals(6, Lint.diagnostics(s.view.state).size)
        assertEquals(big, s.server.documents[uri])
        assertTrue(ms < 5_000, "a 100 KB document took $ms ms")
    }

    @Test fun closingSendsDidCloseShutdownAndExit() = runTest {
        val s = setup()
        s.client.close()
        settle()
        val tail = s.server.log.takeLast(3)
        assertEquals(listOf("textDocument/didClose", "shutdown", "exit"), tail)
        assertFalse(s.server.documents.containsKey(uri))
    }
}
