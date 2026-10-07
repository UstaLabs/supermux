package dev.supermux.ui.editor

import dev.supermux.editor.plugins.lsp.LspPosition
import dev.supermux.editor.plugins.lsp.LspRange
import dev.supermux.editor.plugins.lsp.LspTextEdit
import dev.supermux.editor.plugins.lsp.PositionEncoding
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** One LSP client per (session, server) for a store's documents, over the broker (M5 A4). */
@OptIn(ExperimentalCoroutinesApi::class)
class LspHubTest {
    private class Broker {
        val status = MutableStateFlow<Map<String, ServerFrame.LspStatus>>(emptyMap())
        val rpc = MutableSharedFlow<ServerFrame.LspRpcIn>(extraBufferCapacity = 64)
        val methods = mutableListOf<Pair<String, String?>>() // method, textDocument.uri
        var initializes = 0

        val bridge = LspBridge(
            sessionId = "s1",
            lspStatus = status,
            lspRpc = rpc,
            lspStatusQuery = { s, p ->
                val server = if (p.endsWith(".kt")) "kls" else null
                status.value = status.value + ("$s|$p" to ServerFrame.LspStatus(session = s, path = p, supported = server != null, serverId = server, languageId = "kotlin", state = "ready"))
            },
            lspOpen = { _, _ -> },
            lspRpcOut = { _, serverId, m ->
                val msg = Json.parseToJsonElement(m).jsonObject
                val method = msg["method"]?.jsonPrimitive?.content
                if (method != null) {
                    methods += method to msg["params"]?.jsonObject?.get("textDocument")?.jsonObject?.get("uri")?.jsonPrimitive?.content
                    val id = msg["id"]?.jsonPrimitive?.int
                    if (method == "initialize" && id != null) {
                        initializes++
                        rpc.tryEmit(ServerFrame.LspRpcIn("s1", serverId, """{"jsonrpc":"2.0","id":$id,"result":{"capabilities":{"textDocumentSync":2}}}"""))
                    }
                }
            },
        )
    }

    private fun TestScope.settle() { repeat(3) { advanceTimeBy(2_100); runCurrent() } }

    @Test fun documents_of_one_server_share_one_client_and_close_on_their_own() = runTest {
        val b = Broker()
        val files = mapOf("src/a.kt" to "fun a() = 1\n", "src/b.kt" to "fun b() = 2\n", "notes.txt" to "plain")
        val store = DocumentStore({ p -> Result.success(files.getValue(p)) }, { _, _ -> true }, backgroundScope)
        store.native = NativeEditorEnv(backgroundScope, lspParseOnWorker = false)
        val hub = assertNotNull(store.lspHub())
        val link = LspLink("s1", "/w", b.bridge)
        for (p in files.keys) store.open(p)
        runCurrent()

        val a = store.nativeFor(store.get("src/a.kt")!!)!!.also { it.start() }
        val bDoc = store.nativeFor(store.get("src/b.kt")!!)!!.also { it.start() }
        val txt = store.nativeFor(store.get("notes.txt")!!)!!.also { it.start() }
        backgroundScope.launch { hub.attach(a, link) }
        backgroundScope.launch { hub.attach(bDoc, link) }
        backgroundScope.launch { hub.attach(txt, link) }
        settle()

        assertEquals("s1" to "kls", a.lspKey)
        assertEquals("s1" to "kls", bDoc.lspKey)
        assertNull(txt.lspKey)                        // no server for it: plain editing
        assertSame(hub.client("s1", "kls"), hub.client("s1", "kls"))
        assertEquals(1, b.initializes)                 // ONE client for both documents
        val opened = b.methods.filter { it.first == "textDocument/didOpen" }.map { it.second }
        assertEquals(setOf("file:///w/src/a.kt", "file:///w/src/b.kt"), opened.toSet())
        assertNotNull(a.lspWidgets)

        // Asking again (a pane shown again, a tab switch) opens nothing twice.
        backgroundScope.launch { hub.attach(a, link) }
        settle()
        assertEquals(2, b.methods.count { it.first == "textDocument/didOpen" })

        // A document that closes leaves the server; the other stays open.
        store.close("src/a.kt")
        settle()
        assertEquals(listOf("file:///w/src/a.kt"), b.methods.filter { it.first == "textDocument/didClose" }.map { it.second })
        store.disposeNative()
    }

    @Test fun a_uri_maps_back_to_a_workdir_path() {
        assertEquals("src/a b.kt", uriToWorkdirPath("file:///w/src/a%20b.kt", "/w"))
        assertEquals("src/çalış.kt", uriToWorkdirPath(pathToUri("/w/src/çalış.kt"), "/w/"))
        assertEquals("x.kt", uriToWorkdirPath("file:///private/var/w/x.kt", "/var/w"))
        assertNull(uriToWorkdirPath("file:///elsewhere/x.kt", "/w"))
        assertNull(uriToWorkdirPath("jdt://contents/x", "/w"))
    }

    @Test fun an_edit_to_an_unopened_file_is_read_edited_and_written_with_its_line_endings() = runTest {
        val disk = mutableMapOf("src/c.kt" to "val x = 1\r\nval y = x\r\n")
        val store = DocumentStore({ p -> Result.success(disk.getValue(p)) }, { p, t -> disk[p] = t; true }, backgroundScope)
        store.native = NativeEditorEnv(backgroundScope, lspParseOnWorker = false)
        val hub = store.lspHub()!!
        val rename = listOf(
            LspTextEdit(LspRange(LspPosition(0, 4), LspPosition(0, 5)), "count"),
            LspTextEdit(LspRange(LspPosition(1, 8), LspPosition(1, 9)), "count"),
        )
        assertTrue(hub.applyElsewhere("/w", "file:///w/src/c.kt", rename, PositionEncoding.UTF16))
        runCurrent()
        assertEquals("val count = 1\r\nval y = count\r\n", disk["src/c.kt"])
    }

    @Test fun an_edit_to_a_document_open_in_the_store_goes_through_its_view() = runTest {
        val store = DocumentStore({ Result.success("abc\n") }, { _, _ -> true }, backgroundScope)
        store.native = NativeEditorEnv(backgroundScope, lspParseOnWorker = false)
        store.open("d.kt"); runCurrent()
        val native = store.nativeFor(store.get("d.kt")!!)!!
        val hub = store.lspHub()!!
        assertTrue(hub.applyElsewhere("/w", "file:///w/d.kt", listOf(LspTextEdit(LspRange(LspPosition(0, 0), LspPosition(0, 1)), "X")), PositionEncoding.UTF16))
        assertEquals("Xbc\n", native.primary.state.doc.toString())
        assertTrue(store.isDirty("d.kt"))
    }

    // Re-review: a view left read-only by its last pane still takes a workspace edit.
    @Test fun a_workspace_edit_reaches_a_read_only_view() = runTest {
        val store = DocumentStore({ Result.success("abc\n") }, { _, _ -> true }, backgroundScope)
        store.native = NativeEditorEnv(backgroundScope, lspParseOnWorker = false)
        store.open("d.kt"); runCurrent()
        val native = store.nativeFor(store.get("d.kt")!!)!!
        native.primary.readOnly = true
        val hub = store.lspHub()!!
        assertTrue(hub.applyElsewhere("/w", "file:///w/d.kt", listOf(LspTextEdit(LspRange(LspPosition(0, 0), LspPosition(0, 1)), "X")), PositionEncoding.UTF16))
        assertEquals("Xbc\n", native.primary.state.doc.toString())
    }
}
