package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TerminalStatus { CONNECTING, CONNECTED, DISCONNECTED }

/**
 * Build the /ws/term URL. Pure + testable. Mirrors the broker handler
 * (channels/web/index.ts): `kind=agent` forces the singular agent terminal and
 * ignores terminalId; scratch may name a terminal (broker defaults to "main").
 */
internal fun termWsUrl(baseUrl: String, sessionId: String, kind: String, terminalId: String?): String {
    // Darwin's (iOS) WebSocket requires a ws/wss scheme; the broker base may be an
    // http(s) pair URL. Normalize exactly like BrokerClient before opening the socket.
    val wsBase = wsBaseUrl(baseUrl)
    val base = "$wsBase/ws/term?session=$sessionId"
    return when {
        kind == "agent" -> "$base&kind=agent"
        terminalId != null -> "$base&terminal=$terminalId"
        else -> base
    }
}

/**
 * Websocket client for a session's pty (/ws/term). Binary frames carry raw pty
 * bytes both directions; a JSON text frame carries resize and exit/error. Mirrors
 * BrokerClient's reconnect-with-backoff structure.
 */
class TerminalClient(
    private val baseUrl: String,   // e.g. ws://host:9898  (ws/wss base, same as BrokerClient)
    private val token: String,
    private val http: HttpClient,
    private val sessionId: String,
    private val kind: String = "scratch",      // "scratch" | "agent"
    private val terminalId: String? = null,    // scratch only; broker defaults to "main"
) {
    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    val output: SharedFlow<ByteArray> = _output
    private val _status = MutableStateFlow(TerminalStatus.DISCONNECTED)
    val status: StateFlow<TerminalStatus> = _status
    private val _exit = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val exit: SharedFlow<Int> = _exit

    private var liveSession: DefaultClientWebSocketSession? = null
    @Volatile private var stopped = false
    // Pty input, drained FIFO by a single per-connection sender so rapid keystrokes
    // never race out of order (the UI enqueues them synchronously). Named to avoid
    // clashing with the WebSocket session's own `outgoing` SendChannel<Frame>.
    private val inputQueue = Channel<ByteArray>(Channel.UNLIMITED)
    // Last requested size, re-sent on (re)connect: the view often reports its size
    // before the socket is open, so that first resize would otherwise be dropped.
    @Volatile private var lastCols = 0
    @Volatile private var lastRows = 0

    suspend fun run() {
        var attempt = 0
        while (!stopped) {
            try {
                _status.value = TerminalStatus.CONNECTING
                http.webSocket(
                    urlString = termWsUrl(baseUrl, sessionId, kind, terminalId),
                    request = { header("Authorization", "Bearer $token") },
                ) {
                    attempt = 0
                    liveSession = this
                    _status.value = TerminalStatus.CONNECTED
                    // Flush the last known size (the initial resize is usually reported
                    // before the socket opens and would otherwise be lost).
                    if (lastCols > 0 && lastRows > 0) {
                        send(Frame.Text("{\"type\":\"resize\",\"cols\":$lastCols,\"rows\":$lastRows}"))
                    }
                    // Single FIFO sender for pty input → preserves keystroke order.
                    val ws = this
                    val sender = launch {
                        try {
                            while (true) ws.send(Frame.Binary(true, inputQueue.receive()))
                        } catch (_: Throwable) { /* outgoing closed or sender cancelled */ }
                    }
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Binary -> _output.emit(frame.readBytes())
                                is Frame.Text -> {
                                    val t = frame.readText()
                                    if (t.contains("\"type\":\"exit\"") || t.contains("\"type\":\"error\"")) {
                                        _exit.emit(0)
                                    }
                                }
                                else -> {}
                            }
                        }
                    } finally {
                        sender.cancel()
                        liveSession = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // fall through to backoff
            }
            _status.value = TerminalStatus.DISCONNECTED
            if (stopped) break
            val delayMs = listOf(500L, 1000L, 2000L, 4000L, 8000L)[minOf(attempt, 4)]
            attempt++
            delay(delayMs)
        }
    }

    /** Enqueue pty input (FIFO, non-suspending) so callers can't reorder keystrokes. */
    fun sendInput(bytes: ByteArray) {
        inputQueue.trySend(bytes)
    }

    suspend fun resize(cols: Int, rows: Int) {
        lastCols = cols
        lastRows = rows
        liveSession?.send(Frame.Text("{\"type\":\"resize\",\"cols\":$cols,\"rows\":$rows}"))
    }

    fun stop() { stopped = true; inputQueue.close() }
}
