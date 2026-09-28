package dev.supermux.editor.plugins.lsp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON-RPC 2.0 over an [LspTransport]: request ids, responses, notifications, the server's own
 * requests, `$/cancelRequest` when a caller is cancelled, and a timeout per request. Incoming
 * messages are parsed on [lspParseDispatcher] and handled on [scope] (the UI thread's).
 */
internal class JsonRpc(
    private val transport: LspTransport,
    private val scope: CoroutineScope,
    private val timeoutMs: () -> Long,
    private val parseContext: kotlin.coroutines.CoroutineContext,
    private val onNotification: (method: String, params: JsonElement?) -> Unit,
    private val onRequest: suspend (method: String, params: JsonElement?) -> JsonElement?,
) {
    private var nextId = 0
    private val pending = HashMap<Int, CompletableDeferred<JsonElement?>>()
    private var reader: Job? = null

    /** How many messages were sent and received (tests, the sample's status line). */
    var sent = 0; private set
    var received = 0; private set

    fun start() {
        if (reader != null) return
        reader = scope.launch {
            transport.incoming.collect { text ->
                received++
                val msg = try {
                    (if (lspSliceBigMessages && text.length >= SLICED_PARSE_MIN) SlicedJson.parse(text, 4) { lspGiveBack() }
                    else withContext(parseContext) { Json.parseToJsonElement(text) }) as? JsonObject
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    println("editor-plugins/lsp: an unparsable message from the server: ${e.message}")
                    null
                } ?: return@collect
                handle(msg)
            }
        }
    }

    fun stop() { reader?.cancel(); reader = null; failAll("stopped") }

    /** The connection dropped: every pending request fails. */
    fun failAll(why: String) {
        val all = pending.values.toList()
        pending.clear()
        for (d in all) d.completeExceptionally(LspException(LspException.DISCONNECTED, why))
    }

    private fun handle(msg: JsonObject) {
        val id = msg["id"]
        val method = msg["method"].str
        if (method == null && id != null) {
            val d = pending.remove(id.int ?: return) ?: return
            val err = msg["error"]
            if (err != null) d.completeExceptionally(LspException(err["code"].int ?: 0, err["message"].str ?: "error"))
            else d.complete(msg["result"])
            return
        }
        if (method == null) return
        if (id == null) {
            try { onNotification(method, msg["params"]) } catch (e: Throwable) { println("editor-plugins/lsp: handling $method failed: $e") }
            return
        }
        // The server asks (workspace/applyEdit, workspace/configuration, ...): answer it.
        scope.launch {
            val reply = try {
                val r = onRequest(method, msg["params"])
                buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", r ?: JsonNull) }
            } catch (e: LspException) {
                buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", e.code); put("message", e.message ?: "") }) }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", -32603); put("message", e.toString()) }) }
            }
            send(reply)
        }
    }

    private suspend fun send(o: JsonObject) {
        sent++
        transport.send(o.toString())
    }

    /**
     * Ask [method]; its result (null for a JSON null). Throws [LspException] for a server error, a
     * timeout or a dropped connection. Cancelling the caller sends `$/cancelRequest`.
     */
    suspend fun request(method: String, params: JsonElement?): JsonElement? {
        val id = ++nextId
        val d = CompletableDeferred<JsonElement?>()
        pending[id] = d
        var done = false
        try {
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); if (params != null) put("params", params) })
            val r = withTimeoutOrNull(timeoutMs()) { d.await() }
            if (r == null && !d.isCompleted) throw LspException(LspException.TIMEOUT, "$method timed out")
            done = true
            return r
        } finally {
            if (pending.remove(id) != null && !done && !d.isCompleted) {
                // Cancelled (or timed out): tell the server it can stop.
                withContext(NonCancellable) { runCatching { notify("\$/cancelRequest", buildJsonObject { put("id", id) }) } }
            }
        }
    }

    suspend fun notify(method: String, params: JsonElement?) {
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); if (params != null) put("params", params) })
    }
}

internal fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) when (v) {
        null -> put(k, JsonNull)
        is JsonElement -> put(k, v)
        is String -> put(k, v)
        is Number -> put(k, v)
        is Boolean -> put(k, v)
        else -> put(k, JsonPrimitive(v.toString()))
    }
}
