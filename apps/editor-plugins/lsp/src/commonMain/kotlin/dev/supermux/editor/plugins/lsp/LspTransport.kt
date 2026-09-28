package dev.supermux.editor.plugins.lsp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Whether the transport reaches a server now. */
enum class LspConnState { CONNECTING, CONNECTED, DISCONNECTED }

/**
 * Where the LSP client's JSON-RPC messages go and come from: ONE JSON message per string (no
 * `Content-Length` framing; a stdio transport frames, the broker's channel already does).
 *
 * - M5's host adapts `LspBridge` (`rpcOut` / `pumpRpcIn` / the `lspStatus` frames) to this.
 * - The desktop has [ProcessLspTransport] (jvm): a language server process over stdio.
 * - Tests and the sample use `FakeLspServer` (`:editor-plugins:lsp-fake`), in-process.
 *
 * [status] going to [LspConnState.CONNECTED] (again, after a drop) makes the client initialize
 * and re-open its documents; [LspConnState.DISCONNECTED] fails every pending request.
 */
interface LspTransport {
    suspend fun send(message: String)
    val incoming: Flow<String>
    val status: StateFlow<LspConnState>
}

/** A request that failed: a server error ([code], JSON-RPC's), a timeout, or a dropped connection. */
class LspException(val code: Int, message: String) : Exception(message) {
    companion object {
        const val METHOD_NOT_FOUND = -32601
        const val REQUEST_CANCELLED = -32800
        const val CONTENT_MODIFIED = -32801
        /** Not JSON-RPC's: the client's own. */
        const val TIMEOUT = -1
        const val DISCONNECTED = -2
    }
}

/**
 * Where big messages are parsed: a worker thread on the JVM, Android and iOS (a 5,000-item
 * completion list never parses on the UI thread); the browser's one thread on the web, where
 * parsing is kept within a frame budget instead (see the README's measurements).
 */
internal expect val lspParseDispatcher: CoroutineDispatcher
