package dev.supermux.ui.editor

import dev.supermux.editor.plugins.lsp.LspClient
import dev.supermux.editor.plugins.lsp.LspClientConfig
import dev.supermux.editor.plugins.lsp.LspClientState
import dev.supermux.editor.plugins.lsp.LspConnState
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [BrokerLspTransport] with scripted broker frames (M5 A4): the `lsp_open` / `lsp_ready` / snapshot
 * sequence, inbound filtering, a quick reconnect, a failed send, an exit; and a real [LspClient] on
 * top that must initialize again for every new generation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrokerLspTransportTest {
    /** The broker as the frames show it: status entries, `lsp_rpc` in, and what the client sent. */
    private class Broker {
        val status = MutableStateFlow(
            mapOf("s1|a.kt" to ServerFrame.LspStatus(session = "s1", path = "a.kt", supported = true, serverId = "kls", languageId = "kotlin", state = "ready")),
        )
        val rpc = MutableSharedFlow<ServerFrame.LspRpcIn>(extraBufferCapacity = 64)
        val sent = mutableListOf<String>()
        var opens = 0
        var failNextSend = false
        var server: (JsonObject) -> Unit = {}

        val bridge = LspBridge(
            sessionId = "s1",
            lspStatus = status,
            lspRpc = rpc,
            lspStatusQuery = { s, p -> status.value[("$s|$p")]?.let { status.value = status.value + ("$s|$p" to it.copy()) } },
            // lsp_open → lsp_ready: every entry naming the server is marked ready (HostReducer's markLspState).
            lspOpen = { _, id -> opens++; status.value = status.value.mapValues { (_, st) -> if (st.serverId == id) st.copy(state = "ready") else st } },
            lspRpcOut = { _, _, m ->
                if (failNextSend) { failNextSend = false; throw java.io.IOException("socket closed") }
                sent += m
                server(Json.parseToJsonElement(m).jsonObject)
            },
        )

        fun reply(json: String, session: String = "s1", serverId: String = "kls") { rpc.tryEmit(ServerFrame.LspRpcIn(session, serverId, json)) }

        /** What a snapshot does to the entries (HostReducer): the broker connection is new. */
        fun reconnect() { status.value = status.value.mapValues { (_, st) -> if (st.state == "ready") st.copy(state = LSP_STATE_STALE) else st } }

        fun exit() { status.value = status.value.mapValues { (_, st) -> st.copy(state = "exited") } }
    }

    /** lsp_open's settle window (LspBridge.open: no failure within 2 s = ready). */
    private fun TestScope.settle() { advanceTimeBy(2_100); runCurrent() }

    @Test fun it_connects_after_the_open_and_delivers_only_its_own_server_messages() = runTest {
        val b = Broker()
        val t = BrokerLspTransport(b.bridge, "kls", backgroundScope)
        assertEquals(LspConnState.CONNECTING, t.status.value)
        t.start(); runCurrent()
        settle()
        assertEquals(LspConnState.CONNECTED, t.status.value)
        assertEquals(1, t.connection.value)
        assertEquals(1, b.opens)

        val got = mutableListOf<String>()
        backgroundScope.launch { t.incoming.collect { got += it } }
        runCurrent()
        b.reply("""{"a":1}""", session = "s2")
        b.reply("""{"a":2}""", serverId = "pyright")
        b.reply("""{"a":3}""")
        runCurrent()
        assertEquals(listOf("""{"a":3}"""), got)

        t.send("""{"jsonrpc":"2.0","method":"x"}""")
        assertEquals(listOf("""{"jsonrpc":"2.0","method":"x"}"""), b.sent)
    }

    @Test fun a_broker_reconnect_reopens_the_server_as_a_new_generation() = runTest {
        val b = Broker()
        val t = BrokerLspTransport(b.bridge, "kls", backgroundScope)
        val generations = mutableListOf<Int>()
        backgroundScope.launch { t.connection.collect { generations += it } }
        t.start(); runCurrent(); settle()

        // A snapshot (the entries turn stale): the transport opens the server again, and the
        // client sees a NEW generation (its StateFlow status alone would be CONNECTED again).
        b.reconnect(); runCurrent()
        settle()
        assertEquals(LspConnState.CONNECTED, t.status.value)
        assertEquals(2, t.connection.value)
        assertEquals(2, b.opens)
        assertEquals(listOf(0, 1, 2), generations)
    }

    @Test fun a_failed_send_throws_and_bumps_the_generation() = runTest {
        val b = Broker()
        val t = BrokerLspTransport(b.bridge, "kls", backgroundScope)
        t.start(); runCurrent(); settle()

        b.failNextSend = true
        assertFailsWith<java.io.IOException> { t.send("{}") }
        assertEquals(LspConnState.DISCONNECTED, t.status.value)
        runCurrent(); settle()
        assertEquals(LspConnState.CONNECTED, t.status.value)
        assertEquals(2, t.connection.value)
    }

    @Test fun an_exited_server_is_disconnected_until_a_document_asks_again() = runTest {
        val b = Broker()
        val t = BrokerLspTransport(b.bridge, "kls", backgroundScope)
        t.start(); runCurrent(); settle()

        b.exit(); runCurrent()
        assertEquals(LspConnState.DISCONNECTED, t.status.value)
        settle()
        assertEquals(1, b.opens)                       // no restart loop on its own

        t.ensureConnected(); runCurrent(); settle()
        assertEquals(LspConnState.CONNECTED, t.status.value)
        assertEquals(2, b.opens)
        assertEquals(2, t.connection.value)
    }

    @Test fun the_real_client_initializes_again_after_a_reconnect_and_after_a_failed_send() = runTest {
        val b = Broker()
        var initializes = 0
        b.server = { msg ->
            val method = msg["method"]?.jsonPrimitive?.content
            val id = msg["id"]?.jsonPrimitive?.int
            if (method == "initialize" && id != null) {
                initializes++
                b.reply("""{"jsonrpc":"2.0","id":$id,"result":{"capabilities":{"textDocumentSync":2}}}""")
            }
        }
        val t = BrokerLspTransport(b.bridge, "kls", backgroundScope)
        val client = LspClient(t, backgroundScope, LspClientConfig(rootUri = "file:///w/", parseOnWorker = false))
        t.start(); runCurrent(); settle(); runCurrent()
        assertEquals(1, initializes)
        assertEquals(LspClientState.READY, client.state.value)

        b.reconnect(); runCurrent(); settle(); runCurrent()
        assertEquals(2, initializes)
        assertEquals(LspClientState.READY, client.state.value)

        // A send that fails (the socket went) leaves the client FAILED only until the reopen.
        b.failNextSend = true
        runCatching { t.send("{}") }
        runCurrent(); settle(); runCurrent()
        assertEquals(3, initializes)
        assertEquals(LspClientState.READY, client.state.value)
        assertTrue(b.opens >= 3)
    }
}
