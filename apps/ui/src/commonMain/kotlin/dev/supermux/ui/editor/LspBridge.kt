package dev.supermux.ui.editor

import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Flow-based LSP control-plane + relay — the shared counterpart to iOS `LspBridge.swift` / the
 * web `stores/lsp.ts`. The broker is a dumb JSON-RPC pipe; the real LSP protocol (initialize,
 * didOpen, completion, hover…) runs inside cm6's `LSPClient` in the editor engine's web view
 * (JCEF on desktop, `WebView` on Android).
 *
 * [HostStore] already folds every inbound frame into app-wide flows ([lspStatus] keyed
 * "session|path", [lspRpc] a SharedFlow) — so this bridge just sends the outbound control frames
 * and awaits the corresponding flow transition with the Android/iOS timeouts. It is constructed
 * per editor panel from session-bound lambdas; all RPC is filtered by `session` (the flows are
 * app-wide — never cross-wire another session's server).
 */
class LspBridge(
    private val sessionId: String,
    private val lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>>,
    private val lspRpc: Flow<ServerFrame.LspRpcIn>,
    private val lspStatusQuery: (sessionId: String, path: String) -> Unit,
    private val lspOpen: (sessionId: String, serverId: String) -> Unit,
    private val lspRpcOut: (sessionId: String, serverId: String, message: String) -> Unit,
    /** `lsp_close`: the broker stops the server (the native editor's clients, when their store goes). */
    private val lspClose: (sessionId: String, serverId: String) -> Unit = { _, _ -> },
) {
    private fun statusKey(path: String) = "$sessionId|$path"

    /**
     * Query the language-server status for [path]: send `lsp_status_query`, then await the
     * `lsp_status` frame the broker replies with (9s timeout → "unavailable", parity
     * LspBridge.swift:36-48). Skips a stale retained value so we wait
     * for a fresh response.
     */
    suspend fun queryStatus(path: String): ServerFrame.LspStatus {
        val key = statusKey(path)
        // A "stale" entry (the broker connection was replaced) is not an answer: wait for a fresh
        // one as if nothing were cached, and never fall back to it.
        val prior = lspStatus.value[key]?.takeIf { it.state != LSP_STATE_STALE }
        val stalePrior = lspStatus.value[key]?.takeIf { it.state == LSP_STATE_STALE }
        lspStatusQuery(sessionId, path)
        // Wait for a status OBJECT that is not the one held when we asked (=== identity).
        // If nothing is cached yet, wait the full 9s for the first response (parity iOS).
        // If a value IS cached, the broker's re-response may be value-equal → StateFlow
        // dedups and never re-emits, so wait only briefly for a *change* and otherwise reuse
        // the cached entry (the correct answer) instead of mislabelling it "unavailable".
        val window = if (prior == null) 9_000L else 1_500L
        val fresh = withTimeoutOrNull(window) {
            lspStatus.first { map -> map[key]?.let { it !== prior && it !== stalePrior && it.state != LSP_STATE_STALE } == true }[key]
        }
        return fresh
            ?: prior
            ?: ServerFrame.LspStatus(session = sessionId, path = path, state = "unavailable")
    }

    /**
     * Open the server [serverId]: send `lsp_open`, then confirm it didn't fail (parity
     * LspBridge.swift:50-61 — iOS awaits lsp_ready/lsp_error).
     *
     * The broker spawns the process and replies `lsp_ready` (or `lsp_error`/`lsp_exit`)
     * synchronously — see src/core/lsp/bridge.ts:onOpen. These fold into [lspStatus] via
     * `markLspState` ([HostStore]). BUT queryStatus already left the matching entries at
     * state="ready" (server *available*), and `lsp_ready` re-applies state="ready" → a
     * value-equal map that StateFlow DEDUPS, so a "ready" flip never emits. A failure, however,
     * flips state to "error"/"exited" → that DOES emit.
     *
     * So: caller has already confirmed status.state=="ready" (installed) and a non-empty
     * workdir (the only two failure modes in onOpen), making success the expected outcome. We
     * therefore treat "no fresh error within a short settle window" as ready, while still
     * catching a real lsp_error/lsp_exit (which emits) and returning false. Identity (===) not
     * value-equality, since lsp_error yields a copy().
     */
    suspend fun open(serverId: String): Boolean {
        fun matches(s: ServerFrame.LspStatus) = s.session == sessionId && s.serverId == serverId
        val prior = lspStatus.value.values.filter(::matches)
        fun isFreshFailure(s: ServerFrame.LspStatus) =
            matches(s) && prior.none { it === s } && (s.state == "error" || s.state == "exited")
        lspOpen(sessionId, serverId)
        // Returns the failure entry if one arrives within the window, else null (→ ready).
        val failure = withTimeoutOrNull(2_000) {
            lspStatus.map { map -> map.values.firstOrNull(::isFreshFailure) }.first { it != null }
        }
        return failure == null
    }

    /** Send an outbound JSON-RPC message from the cm6 LSP client to the broker. */
    fun rpcOut(serverId: String, message: String) = lspRpcOut(sessionId, serverId, message)

    /** Stop [serverId] for this session at the broker. */
    fun close(serverId: String) = lspClose(sessionId, serverId)

    /** The session this bridge serves. */
    val session: String get() = sessionId

    /** Inbound JSON-RPC of [serverId] for THIS session, one message per string (the native client's `incoming`). */
    fun rpcIn(serverId: String): Flow<String> =
        lspRpc.filter { it.session == sessionId && it.serverId == serverId }.map { it.message }

    /**
     * What the broker says about [serverId] for this session now, folded over every status entry
     * naming it (lsp_ready / lsp_error / lsp_exit patch them all): [LSP_STATE_STALE] first (the
     * broker connection was re-established and its servers are gone), then a failure, else the
     * state itself; null while no entry names it.
     */
    fun serverState(serverId: String): Flow<String?> = lspStatus.map { m ->
        val states = m.values.filter { it.session == sessionId && it.serverId == serverId }.map { it.state }
        when {
            states.isEmpty() -> null
            LSP_STATE_STALE in states -> LSP_STATE_STALE
            "error" in states -> "error"
            "exited" in states -> "exited"
            else -> states.first()
        }
    }.distinctUntilChanged()

    /**
     * Inbound RPC pump — collect [lspRpc] filtered to this session (and a single server),
     * delivering each message into the cm6 client via [deliver]. Suspends until cancelled (the
     * caller runs it in a child coroutine of the connect LaunchedEffect, so a tab switch tears
     * it down). Filtering by session + serverId prevents cross-wiring.
     */
    suspend fun pumpRpcIn(serverId: String, deliver: (serverId: String, message: String) -> Unit) {
        lspRpc.collect { f ->
            if (f.session == sessionId && f.serverId == serverId) deliver(f.serverId, f.message)
        }
    }
}

/**
 * The state a status entry gets when the broker connection is re-established (HostReducer, on a
 * snapshot): the broker kills a connection's language servers with it, so a client that talked to
 * one must open it again. The shared reducer writes the same literal.
 */
const val LSP_STATE_STALE = dev.supermux.state.LSP_STATE_STALE
