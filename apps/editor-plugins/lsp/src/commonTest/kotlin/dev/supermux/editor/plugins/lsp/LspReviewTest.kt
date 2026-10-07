package dev.supermux.editor.plugins.lsp

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Compartment
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.runKey
import dev.supermux.editor.plugins.autocomplete.Autocomplete
import dev.supermux.editor.plugins.autocomplete.AutocompleteConfig
import dev.supermux.editor.plugins.autocomplete.autocompletion
import dev.supermux.editor.plugins.history.History
import dev.supermux.editor.plugins.history.history
import dev.supermux.editor.plugins.lint.Lint
import dev.supermux.editor.plugins.lint.lint
import dev.supermux.editor.plugins.lsp.fake.FakeLspServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The M4c review's findings, each a regression test, on the fake server's TRANSPORT hooks: a send
 * that takes its time, a send that fails, a reconnect inside one tick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LspReviewTest {
    private val uri = "file:///work/a.toy"
    private fun TestScope.settle(ms: Long = 1_000) { advanceTimeBy(ms); runCurrent() }
    private fun key(v: EditorView, spec: String) = runKey(v, KeyChord.parse(spec, isApplePlatform), isApplePlatform)

    private fun TestScope.client(server: FakeLspServer, config: LspClientConfig = LspClientConfig(syncDelayMs = 50, requestTimeoutMs = 2_000, parseOnWorker = false)) =
        LspClient(server.transport, backgroundScope, config)

    private fun TestScope.view(client: LspClient, text: String, uri: String = this@LspReviewTest.uri, cursor: Int = 0, vararg ext: Extension): EditorView {
        val v = EditorView(EditorState.create(text, EditorSelection.cursor(cursor), extensionOf(
            autocompletion(AutocompleteConfig(interactionDelay = 0, activateOnTypingDelay = 20)), lint(), history(), client.plugin(uri, "toy"), *ext,
        )))
        v.startPlugins(backgroundScope)
        return v
    }

    private fun EditorView.type(at: Int, s: String) = dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, s)), selection = EditorSelection.cursor(at + s.length), userEvent = "input.type"))
    private val EditorView.text get() = state.doc.toString()

    // ------------------------------------------------------------------ 1: applyEdit versions --

    @Test fun aServerEditForAnOlderVersionIsMappedNotAppliedAtTheWrongPlace() = runTest {
        val server = FakeLspServer(backgroundScope)
        val c = client(server)
        val v = view(c, "abc")
        settle()
        val v1 = c.workspace.version(uri)!!
        v.type(0, "X")
        settle()
        assertEquals(v1 + 1, c.workspace.version(uri))
        // The server computed "insert Y after abc" on version 1 ("abc"), and says so.
        val id = server.pushEdit(uri, listOf(intArrayOf(0, 3, 0, 3) to "Y"), version = v1)
        settle()
        assertEquals("XabcY", v.text, "mapped from version $v1 to now (was XabYc)")
        assertEquals("true", ((server.applyResults[id] as JsonObject)["applied"] as JsonPrimitive).content)
    }

    @Test fun aServerEditForAVersionNoLongerKeptIsNotAppliedAndSaysSo() = runTest {
        val server = FakeLspServer(backgroundScope)
        val c = client(server)
        val v = view(c, "abc")
        settle()
        repeat(40) { v.type(0, "x"); settle(100) } // 40 versions later: version 1 is gone
        val before = v.text
        val id = server.pushEdit(uri, listOf(intArrayOf(0, 0, 0, 0) to "Y"), version = 1)
        settle()
        assertEquals(before, v.text)
        assertEquals("false", ((server.applyResults[id] as JsonObject)["applied"] as JsonPrimitive).content)
    }

    @Test fun aPartlyOutOfDateServerEditReportsAppliedFalse() = runTest {
        val server = FakeLspServer(backgroundScope)
        val c = client(server)
        val v = view(c, "one two")
        settle()
        val v1 = c.workspace.version(uri)!!
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(4, 7, "TWO")), userEvent = "input.type"))
        settle()
        val id = server.pushEdit(uri, listOf(intArrayOf(0, 0, 0, 3) to "1", intArrayOf(0, 4, 0, 7) to "2"), version = v1)
        settle()
        assertEquals("1 TWO", v.text, "the untouched edit applied, the other dropped")
        assertEquals("false", ((server.applyResults[id] as JsonObject)["applied"] as JsonPrimitive).content, "partial is not 'applied'")
    }

    @Test fun aResolvedCodeActionAppliesAtTheVersionItWasResolvedFor() = runTest {
        val server = FakeLspServer(backgroundScope, resolveCodeActions = true)
        val c = client(server)
        val v = view(c, "val a = 1 // TODO\n")
        settle()
        val d = Lint.diagnostics(v.state).single()
        val fix = d.actions.single()
        // The user types before the action: the resolve happens against THIS text.
        v.type(0, "// head\n")
        Lint.runAction(v, Lint.diagnostics(v.state).single(), fix)
        settle()
        assertEquals("// head\nval a = 1 // DONE\n", v.text)
        assertEquals(server.documents[uri], v.text)
    }

    // ------------------------------------------------------------------ 2: nothing escapes --

    @Test fun aSendThatFailsPartwayFailsTheConnectionNotTheUi() = runTest {
        val server = FakeLspServer(backgroundScope)
        val messages = ArrayList<String>()
        val c = client(server, LspClientConfig(syncDelayMs = 50, requestTimeoutMs = 2_000, parseOnWorker = false, onMessage = { _, m -> messages += m }))
        val v = view(c, "fun add(a, b) {\n}\nval x = add(1, 2)\n", cursor = 30)
        settle()
        server.failAfterSends = 0
        // One send breaks the pipe: the client sends nothing more on this connection (so the fake's
        // later sends would not fail; the next connection's must work).
        server.failSends = 1
        // Every command while the pipe is broken: none throws, pending requests fail.
        assertTrue(key(v, "F12")); assertTrue(key(v, "Shift-F12")); assertTrue(key(v, "Shift-Alt-f"))
        v.type(0, "x")
        settle()
        assertEquals(LspClientState.FAILED, c.state.value)
        assertTrue(messages.any { "connection" in it }, "$messages")
        // The next connection works again.
        server.blip()
        settle()
        assertEquals(LspClientState.READY, c.state.value)
        assertEquals(v.text, server.documents[uri])
    }

    // ------------------------------------------------------------------ 4: one document, many views --

    @Test fun twoViewsOfOneUriShareOneServerDocumentAndStayInStep() = runTest {
        val server = FakeLspServer(backgroundScope)
        val c = client(server)
        val a = view(c, "fun f() {}\n")
        val b = view(c, "fun f() {}\n")
        settle()
        assertEquals(1, server.log.count { it == "textDocument/didOpen" }, "didOpen once")
        assertEquals(2, c.workspace.viewCount(uri))
        a.type(0, "// from a\n")
        b.type(0, "// from b\n")
        settle()
        assertEquals(a.text, b.text, "both views show the same text")
        assertEquals(a.text, server.documents[uri], "the server's copy is theirs")
        // History: each view undoes its OWN edit only (the other's arrived as remote).
        assertTrue(History.undo.run(b))
        settle()
        assertEquals("// from a\nfun f() {}\n", a.text)
        assertEquals(a.text, server.documents[uri])
        // Detaching one view keeps the document; the last one closes it.
        b.setState(EditorState.create("other"))
        settle()
        assertEquals(0, server.log.count { it == "textDocument/didClose" })
        a.setState(EditorState.create("other"))
        settle()
        assertEquals(1, server.log.count { it == "textDocument/didClose" })
        assertTrue(c.workspace.openDocuments.isEmpty())
    }

    @Test fun commandsAndCompletionRunForTheirOwnView() = runTest {
        val server = FakeLspServer(backgroundScope)
        val c = client(server)
        val text = "fun add(a, b) {\n}\nval x = add(1, 2)\n"
        val a = view(c, text, cursor = text.indexOf("add(1") + 1)
        val b = view(c, text, cursor = text.length)
        settle()
        assertTrue(key(b, "F12"))
        settle()
        assertEquals(text.length, b.state.selection.main.head, "F12 in b with its cursor on nothing: b's cursor stays")
        assertTrue(key(a, "F12"))
        settle()
        assertEquals(text.indexOf("add"), a.state.selection.main.head, "a jumped")
        assertEquals(text.length, b.state.selection.main.head, "b did not")
        assertTrue(Autocomplete.startCompletion.run(b)); settle()
        assertTrue(Autocomplete.isOpen(b.state))
        assertFalse(Autocomplete.isOpen(a.state), "the list is b's only")
        assertEquals(Lint.diagnostics(a.state).size, Lint.diagnostics(b.state).size, "diagnostics reach every view")
    }

    @Test fun oneClientThreeFilesFromSeveralViewsAndRenameAcrossThem() = runTest {
        val server = FakeLspServer(backgroundScope)
        var elsewhere: Pair<String, Int>? = null
        val c = client(server, LspClientConfig(syncDelayMs = 50, parseOnWorker = false, onWorkspaceEdit = { u, e -> elsewhere = u to e.size; true }))
        val a1 = view(c, "val total = 1\n", uri = "file:///w/a.toy", cursor = 5)
        val a2 = view(c, "val total = 1\n", uri = "file:///w/a.toy")
        val b = view(c, "val y = total + total\n", uri = "file:///w/b.toy")
        val cc = view(c, "// total\n", uri = "file:///w/c.toy")
        settle()
        assertEquals(listOf("file:///w/a.toy", "file:///w/b.toy", "file:///w/c.toy"), c.workspace.openDocuments)
        server.extraRenameEdits["file:///w/closed.toy"] = listOf(intArrayOf(0, 0, 0, 5) to "sum")
        assertTrue(key(a1, "F2"))
        LspPlugin.submitRename(c, a1, "sum")
        settle()
        assertEquals("val sum = 1\n", a1.text)
        assertEquals(a1.text, a2.text, "the other view of a")
        assertEquals("val y = sum + sum\n", b.text, "an open file: through its own document")
        assertEquals("// sum\n", cc.text)
        assertEquals("file:///w/closed.toy" to 1, elsewhere, "a file that is not open: to the host")
        for ((u, v) in listOf("file:///w/a.toy" to a1, "file:///w/b.toy" to b, "file:///w/c.toy" to cc)) assertEquals(v.text, server.documents[u])
        assertNull(LspPlugin.state(a1.state).rename, "done: the prompt closed")
    }

    @Test fun anEditToAClosedFileWithNoHostCallbackFailsLoudly() = runTest {
        val server = FakeLspServer(backgroundScope)
        val messages = ArrayList<String>()
        val c = client(server, LspClientConfig(syncDelayMs = 50, parseOnWorker = false, onMessage = { _, m -> messages += m }))
        view(c, "abc")
        settle()
        val id = server.pushEdit("file:///nowhere.toy", listOf(intArrayOf(0, 0, 0, 0) to "x"))
        settle()
        assertEquals("false", ((server.applyResults[id] as JsonObject)["applied"] as JsonPrimitive).content)
        assertTrue(messages.any { "nowhere" in it })
    }

    // ------------------------------------------------------------------ 5: a reconnect inside a tick --

    @Test fun aDropAndReconnectInsideOneTickReopensEverything() = runTest {
        val server = FakeLspServer(backgroundScope)
        val c = client(server)
        val v = view(c, "abc")
        settle()
        server.blip()
        settle()
        assertEquals(2, server.log.count { it == "initialize" }, "the blip was seen")
        assertEquals("abc", server.documents[uri], "didOpen again")
        v.type(3, "d")
        settle()
        assertEquals("abcd", server.documents[uri])
    }

    // ------------------------------------------------------------------ 6: order on a slow pipe --

    @Test fun onASlowPipeTheDidChangeAlwaysGoesBeforeTheRequestThatNeedsIt() = runTest {
        val server = FakeLspServer(backgroundScope)
        val c = client(server)
        val v = view(c, "fun add(a, b) {\n}\n", cursor = 18)
        settle()
        server.sendDelayMs = 100
        v.type(18, "ad")
        // At once: the 50 ms sync is still pending; the explicit completion syncs itself.
        assertTrue(Autocomplete.startCompletion.run(v))
        // And a delayed sync that a request's sync cancels while it is queued never loses its change.
        v.type(20, "d")
        settle(100)
        assertTrue(key(v, "F12"))
        settle(5_000)
        val w = server.wire
        val completion = w.indexOf("textDocument/completion")
        assertTrue(completion > 0 && w.subList(0, completion).contains("textDocument/didChange"), "$w")
        assertEquals("fun add(a, b) {\n}\nadd", server.textAtRequest["textDocument/completion"], "the server had the text the position is in (the typing cancelled the explicit ask; the next one asked at the newer text)")
        assertEquals(v.text, server.documents[uri])
    }

    // ------------------------------------------------------------------ 7: enabled by an editing transaction --

    @Test fun thePluginEnabledInAnEditingTransactionStartsFromItsResult() = runTest {
        val server = FakeLspServer(backgroundScope)
        val c = client(server)
        val slot = Compartment()
        val v = EditorView(EditorState.create("abc", extensions = extensionOf(lint(), slot.of(extensionOf()))))
        v.startPlugins(backgroundScope)
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 3, "xyz")), effects = listOf(slot.reconfigure(c.plugin(uri, "toy")))))
        settle()
        v.type(3, "!")
        settle()
        assertEquals("xyz!", v.text)
        assertEquals(v.text, server.documents[uri])
    }

    // ------------------------------------------------------------------ 8: rename is all or nothing --

    @Test fun aRenameThatWentOutOfDateAppliesNothingAndSaysSo() = runTest {
        val server = FakeLspServer(backgroundScope)
        server.delayMs["textDocument/rename"] = 300
        val c = client(server)
        val text = "val total = 1\nval x = total\n"
        val v = view(c, text, cursor = 6)
        settle()
        assertTrue(key(v, "F2"))
        LspPlugin.submitRename(c, v, "sum")
        settle(100)
        assertTrue(LspPlugin.state(v.state).rename!!.pending)
        // The user edits one occurrence while the server works.
        val at = text.lastIndexOf("total")
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at + 5, "totals")), userEvent = "input.type"))
        settle()
        assertEquals("val total = 1\nval x = totals\n", v.text, "nothing renamed")
        val prompt = LspPlugin.state(v.state).rename
        assertNotNull(prompt, "the prompt stays")
        assertEquals("Rename out of date — try again", prompt.error)
        assertFalse(prompt.pending)
    }

    // ------------------------------------------------------------------ protocol --

    @Test fun formatSendsTheRealTabSize() = runTest {
        val server = FakeLspServer(backgroundScope)
        val c = client(server)
        val v = view(c, "fun f() {\n}\n", ext = arrayOf(dev.supermux.editor.compose.tabSizeFacet.of(8), dev.supermux.editor.compose.indentUnitFacet.of("\t")))
        settle()
        assertTrue(key(v, "Shift-Alt-f"))
        settle()
        val opts = (server.lastParams["textDocument/formatting"] as JsonObject)["options"] as JsonObject
        assertEquals("8", (opts["tabSize"] as JsonPrimitive).content)
        assertEquals("false", (opts["insertSpaces"] as JsonPrimitive).content)
    }

    // ------------------------------------------------------------------ M4d task 0 --

    @Test fun anEditStillReachesTheServerWhenItsViewDetachesBeforeTheBatch() = runTest {
        val server = FakeLspServer(backgroundScope)
        val c = client(server)
        val a = view(c, "abc\n")
        val b = view(c, "abc\n")
        settle()
        a.type(0, "X")
        // A goes (another document) before the 50 ms batch fires: B keeps the document open.
        a.setState(EditorState.create("other"))
        advanceTimeBy(60); runCurrent()
        assertEquals("Xabc\n", b.text)
        assertEquals("Xabc\n", server.documents[uri], "the pending didChange was sent within the batch delay")
    }

    @Test fun afterASendFailsNothingQueuedIsSentUntilTheNextConnection() = runTest {
        val server = FakeLspServer(backgroundScope)
        val c = client(server)
        val v = view(c, "fun add(a, b) {\n}\nval x = add(1, 2)\n", cursor = 30)
        settle()
        val before = server.wire.size
        server.failAfterSends = 0
        server.failSends = 1
        // Several messages queued in one tick (a didChange, then a request's own); the first send breaks.
        v.type(0, "x")
        assertTrue(key(v, "F12"))
        settle()
        assertEquals(LspClientState.FAILED, c.state.value)
        assertEquals(before, server.wire.size, "nothing after the failed send: ${server.wire.drop(before)}")
        server.blip()
        settle()
        assertEquals(LspClientState.READY, c.state.value)
        assertEquals(v.text, server.documents[uri])
    }

    @Test fun theSlicedParserIsStrict() = runTest {
        val bad = listOf(
            "{\"a\":1,}", "[1,]", "[1 2]", "{\"a\" 1}", "{\"a\":1 \"b\":2}", "tru", "nul", "[01]", "[1.]", "[-]", "\"a\\x\"", "\"a\nb\"",
            "{} x", "", "[", "{\"a\":}", "[1e]", "{1:2}", "]", "\"\\u12\"",
        )
        for (b in bad) {
            val threw = try { SlicedJson.parse(b, 4) {}; false } catch (e: IllegalArgumentException) { true }
            assertTrue(threw, "accepted malformed: $b")
        }
        val good = "{\"a\":[1,-2.5e3,0,true,false,null,\"x\\n\\u00e7\\\"\\/\"],\"b\":{},\"c\":[]}"
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(good), SlicedJson.parse(good, 4) {})
    }
}
