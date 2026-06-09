package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TerminalStatus { CONNECTING, CONNECTED, DISCONNECTED }

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
) {
    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    val output: SharedFlow<ByteArray> = _output
    private val _status = MutableStateFlow(TerminalStatus.DISCONNECTED)
    val status: StateFlow<TerminalStatus> = _status
    private val _exit = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val exit: SharedFlow<Int> = _exit

    private var liveSession: DefaultClientWebSocketSession? = null
    @Volatile private var stopped = false

    suspend fun run() {
        var attempt = 0
        while (!stopped) {
            try {
                _status.value = TerminalStatus.CONNECTING
                http.webSocket(
                    urlString = "$baseUrl/ws/term?session=$sessionId",
                    request = { header("Authorization", "Bearer $token") },
                ) {
                    attempt = 0
                    liveSession = this
                    _status.value = TerminalStatus.CONNECTED
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

    suspend fun sendInput(bytes: ByteArray) {
        liveSession?.send(Frame.Binary(true, bytes))
    }

    suspend fun resize(cols: Int, rows: Int) {
        liveSession?.send(Frame.Text("{\"type\":\"resize\",\"cols\":$cols,\"rows\":$rows}"))
    }

    fun stop() { stopped = true }
}
