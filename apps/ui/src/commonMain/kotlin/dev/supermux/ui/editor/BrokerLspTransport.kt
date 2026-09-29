package dev.supermux.ui.editor

import dev.supermux.editor.plugins.lsp.LspConnState
import dev.supermux.editor.plugins.lsp.LspTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The native editor's LSP client over the broker's `lsp` channel (M5 A4): [LspBridge] adapted to
 * [LspTransport]. No broker change: the same frames the old CodeMirror client used.
 *
 * - [send] is `lsp_rpc` out ([LspBridge.rpcOut]); [incoming] the `lsp_rpc` frames of this session
 *   and server only ([LspBridge.rpcIn]).
 * - [status] / [connection]: CONNECTED once `lsp_open` did not fail ([LspBridge.open]), with a NEW
 *   generation for every (re)open, so the client initializes again even when a drop and a reopen
 *   fall inside one tick. A broker reconnect (the status entries turn [LSP_STATE_STALE]: the broker
 *   disposed the old connection's servers) reopens at once; `lsp_exit` / `lsp_error` is
 *   DISCONNECTED until someone asks again ([ensureConnected]: a pane showing one of its documents),
 *   so a server that keeps crashing is not restarted in a loop.
 * - A [send] that throws reports DISCONNECTED and reopens, i.e. a new generation (the
 *   `LspTransport` KDoc: otherwise the client would stay FAILED).
 */
class BrokerLspTransport(
    private val bridge: LspBridge,
    val serverId: String,
    private val scope: CoroutineScope,
) : LspTransport {
    private val statusFlow = MutableStateFlow(LspConnState.CONNECTING)
    private val generation = MutableStateFlow(0)
    private var watcher: Job? = null
    private var opening: Job? = null
    private var closed = false

    override val status: StateFlow<LspConnState> = statusFlow.asStateFlow()
    override val connection: StateFlow<Int> = generation.asStateFlow()
    override val incoming: Flow<String> = bridge.rpcIn(serverId)

    /** How many times this transport opened the server (tests; a debug line). */
    var opens: Int = 0
        private set

    /** Open the server and follow the broker's word about it. Idempotent. */
    fun start() {
        if (closed || watcher != null) return
        watcher = scope.launch {
            bridge.serverState(serverId).collect { state ->
                when (state) {
                    LSP_STATE_STALE -> reopen()
                    "error", "exited" -> if (statusFlow.value != LspConnState.DISCONNECTED) statusFlow.value = LspConnState.DISCONNECTED
                }
            }
        }
        reopen()
    }

    /** A document wants this server: open it again if it went away (exited, a failed open). */
    fun ensureConnected() {
        if (statusFlow.value == LspConnState.DISCONNECTED) reopen()
    }

    override suspend fun send(message: String) {
        try {
            bridge.rpcOut(serverId, message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The client drops this connection (FAILED, nothing more sent): a reopen is the next
            // generation it starts over on.
            statusFlow.value = LspConnState.DISCONNECTED
            reopen()
            throw e
        }
    }

    fun close() {
        closed = true
        reopenPending = false
        watcher?.cancel()
        opening?.cancel()
        statusFlow.value = LspConnState.DISCONNECTED
    }

    /** A reopen asked for while one was in flight: it runs once that one ends (its open may predate the reason). */
    private var reopenPending = false

    private fun reopen() {
        if (closed) return
        if (opening?.isActive == true) { reopenPending = true; return }
        opening = scope.launch {
            if (statusFlow.value == LspConnState.CONNECTED) statusFlow.value = LspConnState.CONNECTING
            opens++
            val ok = bridge.open(serverId)
            if (ok) generation.value = generation.value + 1
            if (reopenPending) {
                // A broker reconnect (or a failed send) landed during this open's settle window: the
                // server it confirmed may already be gone. Open again rather than report CONNECTED
                // to a process that no longer exists.
                reopenPending = false
                opening = null
                reopen()
                return@launch
            }
            statusFlow.value = if (ok) LspConnState.CONNECTED else LspConnState.DISCONNECTED
        }
    }
}
