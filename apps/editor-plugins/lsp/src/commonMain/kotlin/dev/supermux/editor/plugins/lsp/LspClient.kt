package dev.supermux.editor.plugins.lsp

import dev.supermux.editor.core.Extension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where the client is in its life with the server. */
enum class LspClientState { DISCONNECTED, INITIALIZING, READY, FAILED }

/**
 * The client's options.
 *
 * - [rootUri]: the workspace root sent in `initialize`.
 * - [requestTimeoutMs]: a request with no answer by then fails (CM6: 15 s); the feature that asked
 *   does nothing, nothing crashes.
 * - [syncDelayMs]: edits are sent as ONE `didChange` this long after the last one (and always right
 *   before a request).
 * - [onNavigate]: a definition or a reference in ANOTHER document (the host opens it; M5).
 * - [onWorkspaceEdit]: a rename's, a code action's or the server's edits to ANOTHER document; return
 *   true when applied.
 * - [onMessage]: `window/showMessage` (type 1 error … 4 log) and the client's own errors (a failed
 *   rename, format, …).
 * - [markdown]: turns the server's markdown into the tooltips' text (default [LspMarkdown.toPlainText]).
 * - [fallbackTriggers]: completion trigger characters when the server names none (today's CM6
 *   editor's list).
 */
data class LspClientConfig(
    val rootUri: String? = null,
    val requestTimeoutMs: Long = 15_000,
    val syncDelayMs: Long = 50,
    val onNavigate: ((uri: String, range: LspRange) -> Unit)? = null,
    val onWorkspaceEdit: ((uri: String, edits: List<LspTextEdit>) -> Boolean)? = null,
    val onMessage: ((type: Int, message: String) -> Unit)? = null,
    val markdown: (String) -> String = LspMarkdown::toPlainText,
    val fallbackTriggers: Set<Char> = setOf('.', ':', '"', '\'', '`', '<', '/', '@', '#'),
    /**
     * Parse incoming messages (and map big completion lists) on a worker thread where the platform
     * has one (the default). False parses on the client's own scope (tests on a virtual clock).
     */
    val parseOnWorker: Boolean = true,
)

/**
 * A Language Server Protocol client (CM6's `LSPClient`): ONE per server connection, owned by the
 * host, shared by every editor showing one of its documents. It runs on [scope] (the UI thread's)
 * and talks JSON-RPC over [transport].
 *
 * Lifecycle: when the transport is connected the client sends `initialize` (its capabilities, and
 * `positionEncodings` with **utf-16 first**: the editor's own unit), then `initialized`, then
 * `didOpen` for every document a view shows ([plugin]); when the transport drops, pending requests
 * fail and, once it is back, the client initializes again and re-opens its documents (their text as
 * it is then). [close] sends `shutdown` and `exit`.
 */
class LspClient(
    val transport: LspTransport,
    private val scope: CoroutineScope,
    val config: LspClientConfig = LspClientConfig(),
) {
    private val stateFlow = MutableStateFlow(LspClientState.DISCONNECTED)
    val state: StateFlow<LspClientState> = stateFlow.asStateFlow()

    private val featuresFlow = MutableStateFlow(ServerFeatures())
    /** What the server can do (after `initialize`). */
    val features: StateFlow<ServerFeatures> = featuresFlow.asStateFlow()

    /** The server's `serverInfo.name`, if it gave one. */
    var serverName: String? = null
        private set

    private val sendLock = Mutex()
    private val ordered = object : LspTransport by transport {
        override suspend fun send(message: String) = sendLock.withLock { transport.send(message) }
    }

    /** Where incoming JSON is parsed and completion lists mapped. */
    internal val parseContext: kotlin.coroutines.CoroutineContext =
        if (config.parseOnWorker) lspParseDispatcher else kotlin.coroutines.EmptyCoroutineContext

    internal val rpc = JsonRpc(ordered, scope, { config.requestTimeoutMs }, parseContext, ::notification, ::serverRequest)
    internal val documents = LinkedHashMap<String, LspDocument>()
    private var watcher: Job? = null
    private var initJob: Job? = null
    private var closed = false

    /** Hover texts by tooltip id (what `tooltip:lsp-hover` shows), the most recent few. */
    internal val hoverTexts = LinkedHashMap<String, String>()

    internal fun putHover(id: String, text: String) {
        hoverTexts[id] = text
        while (hoverTexts.size > 8) hoverTexts.remove(hoverTexts.keys.first())
    }

    /** Messages sent and received so far (a debug line). */
    val messagesSent: Int get() = rpc.sent
    val messagesReceived: Int get() = rpc.received

    init { start() }

    private fun start() {
        rpc.start()
        watcher = scope.launch {
            transport.status.collect { s ->
                when (s) {
                    LspConnState.CONNECTED -> if (stateFlow.value == LspClientState.DISCONNECTED) initJob = launch { initialize() }
                    LspConnState.DISCONNECTED -> disconnected()
                    LspConnState.CONNECTING -> Unit
                }
            }
        }
    }

    private fun disconnected() {
        initJob?.cancel()
        if (stateFlow.value == LspClientState.DISCONNECTED) return
        stateFlow.value = LspClientState.DISCONNECTED
        rpc.failAll("the connection dropped")
        for (d in documents.values) d.connectionLost()
    }

    private suspend fun initialize() {
        stateFlow.value = LspClientState.INITIALIZING
        val params = buildJsonObject {
            put("processId", JsonNull)
            put("clientInfo", buildJsonObject { put("name", "supermux-editor") })
            put("rootUri", config.rootUri?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            config.rootUri?.let { root ->
                put("workspaceFolders", buildJsonArray { add(buildJsonObject { put("uri", root); put("name", root.substringAfterLast('/')) }) })
            }
            put("capabilities", clientCapabilities())
        }
        val result = try {
            rpc.request("initialize", params)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            report(1, "The language server did not initialize: ${e.message}")
            stateFlow.value = if (transport.status.value == LspConnState.CONNECTED) LspClientState.FAILED else LspClientState.DISCONNECTED
            return
        }
        featuresFlow.value = ServerFeatures.parse(result["capabilities"])
        serverName = result["serverInfo"]["name"].str
        rpc.notify("initialized", buildJsonObject {})
        stateFlow.value = LspClientState.READY
        for (d in documents.values.toList()) d.open()
    }

    /**
     * The editor extension for the document [uri] (CM6's `client.plugin(uri, languageId)`): sync,
     * diagnostics, completion, hover, signature help and the commands and keys. [languageId] is the
     * LSP language id (the host maps its syntax registry's language to it: `kotlin`, `typescript`,
     * …). The editor needs `autocompletion()` and `lint()` too (installed once, by the host), and the
     * widgets: [registerWidgets].
     */
    fun plugin(uri: String, languageId: String): Extension = LspPlugin.extension(this, uri, languageId)

    internal fun attach(d: LspDocument) { documents[d.uri] = d }
    internal fun detach(d: LspDocument) { if (documents[d.uri] === d) documents.remove(d.uri) }

    /** The document for [uri] as a server wrote it (percent-encoding, a `file:` drive letter's case may differ). */
    internal fun document(uri: String?): LspDocument? {
        if (uri == null) return null
        documents[uri]?.let { return it }
        val k = normalizeUri(uri)
        return documents.values.firstOrNull { normalizeUri(it.uri) == k }
    }

    internal val ready: Boolean get() = stateFlow.value == LspClientState.READY

    internal fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    /** Start [block] now, in call order: its first send queues behind the ones before it (didOpen, didClose). */
    internal fun launchOrdered(block: suspend () -> Unit): Job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { block() }

    internal fun report(type: Int, message: String) {
        config.onMessage?.invoke(type, message) ?: println("editor-plugins/lsp: $message")
    }

    /** Ask the server; null when it is not ready, the request failed, or the server said nothing. */
    internal suspend fun request(method: String, params: JsonElement?, quiet: Boolean = true): JsonElement? {
        if (!ready) return null
        return try {
            rpc.request(method, params)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: LspException) {
            if (!quiet && e.code != LspException.REQUEST_CANCELLED && e.code != LspException.CONTENT_MODIFIED) report(1, "$method failed: ${e.message}")
            null
        }
    }

    internal suspend fun notify(method: String, params: JsonElement?) {
        if (!ready) return
        try { rpc.notify(method, params) } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Throwable) { report(1, "$method failed: $e") }
    }

    private fun notification(method: String, params: JsonElement?) {
        when (method) {
            "textDocument/publishDiagnostics" -> document(params["uri"].str)?.diagnostics(params)
            "window/showMessage", "window/logMessage" -> if (method == "window/showMessage") report(params["type"].int ?: 4, params["message"].str.orEmpty())
            else -> Unit
        }
    }

    private suspend fun serverRequest(method: String, params: JsonElement?): JsonElement? = when (method) {
        "workspace/applyEdit" -> {
            val edits = workspaceEdit(params["edit"])
            var applied = true
            for ((uri, list) in edits) {
                val d = document(uri)
                applied = applied && (if (d != null) d.applyServerEdits(list, userEvent = "lsp") else config.onWorkspaceEdit?.invoke(uri, list) == true)
            }
            buildJsonObject { put("applied", applied) }
        }
        "workspace/configuration" -> JsonArray(params["items"].arr.orEmpty().map { JsonNull })
        "client/registerCapability", "client/unregisterCapability", "window/workDoneProgress/create" -> JsonNull
        "window/showMessageRequest" -> { report(params["type"].int ?: 4, params["message"].str.orEmpty()); JsonNull }
        else -> throw LspException(LspException.METHOD_NOT_FOUND, "Method not implemented: $method")
    }

    /** `shutdown`, then `exit`, then stop listening. The documents close first (`didClose`). */
    suspend fun close() {
        if (closed) return
        closed = true
        withContext(NonCancellable) {
            for (d in documents.values.toList()) d.close()
            if (ready) {
                withTimeoutOrNull(2_000) { runCatching { rpc.request("shutdown", null) } }
                runCatching { rpc.notify("exit", null) }
            }
            watcher?.cancel()
            rpc.stop()
            stateFlow.value = LspClientState.DISCONNECTED
        }
    }
}

/** A URI compared loosely: percent-escapes decoded, `file:` URIs' drive letter lower-cased, no trailing slash. */
internal fun normalizeUri(uri: String): String {
    val sb = StringBuilder()
    var i = 0
    val bytes = ArrayList<Byte>()
    fun flush() { if (bytes.isNotEmpty()) { sb.append(bytes.toByteArray().decodeToString()); bytes.clear() } }
    while (i < uri.length) {
        val c = uri[i]
        if (c == '%' && i + 2 < uri.length) {
            val v = uri.substring(i + 1, i + 3).toIntOrNull(16)
            if (v != null) { bytes += v.toByte(); i += 3; continue }
        }
        flush(); sb.append(c); i++
    }
    flush()
    var s = sb.toString()
    if (s.startsWith("file:///") && s.length > 9 && s[9] == ':') s = s.substring(0, 8) + s[8].lowercaseChar() + s.substring(9)
    return s.trimEnd('/')
}
