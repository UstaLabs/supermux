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

enum class ScrcpyStatus { CONNECTING, CONNECTED, DISCONNECTED }

/** One decoded video chunk: [isKey] keyframe flag, [data] = Annex-B H.264 payload (byte 0 stripped). */
class ScrcpyFrame(val isKey: Boolean, val data: ByteArray)

/**
 * Websocket client for a display's H.264 stream (/ws/scrcpy). A JSON `init` text
 * frame carries width/height/codec; binary frames carry [flagByte][annex-b payload]
 * with bit0 of the flag byte = keyframe. Input events are sent as JSON text frames.
 * Mirrors TerminalClient's reconnect structure.
 */
class ScrcpyClient(
    private val baseUrl: String,    // ws/wss base
    private val token: String,
    private val http: HttpClient,
    private val streamId: String,
) {
    private val _frames = MutableSharedFlow<ScrcpyFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<ScrcpyFrame> = _frames
    private val _status = MutableStateFlow(ScrcpyStatus.DISCONNECTED)
    val status: StateFlow<ScrcpyStatus> = _status
    private val _dims = MutableStateFlow<Pair<Int, Int>?>(null)
    val dims: StateFlow<Pair<Int, Int>?> = _dims

    private var liveSession: DefaultClientWebSocketSession? = null
    @Volatile private var stopped = false

    suspend fun run() {
        var attempt = 0
        while (!stopped) {
            try {
                _status.value = ScrcpyStatus.CONNECTING
                http.webSocket(
                    urlString = "$baseUrl/ws/scrcpy?id=$streamId",
                    request = { header("Authorization", "Bearer $token") },
                ) {
                    attempt = 0
                    liveSession = this
                    _status.value = ScrcpyStatus.CONNECTED
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val t = frame.readText()
                                    // parse {"type":"init","width":W,"height":H,...}
                                    if (t.contains("\"init\"")) {
                                        val w = Regex("\"width\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
                                        val h = Regex("\"height\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
                                        if (w != null && h != null) _dims.value = w to h
                                    }
                                }
                                is Frame.Binary -> {
                                    val b = frame.readBytes()
                                    if (b.isNotEmpty()) {
                                        val isKey = (b[0].toInt() and 0x01) == 1
                                        _frames.emit(ScrcpyFrame(isKey, b.copyOfRange(1, b.size)))
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
            _status.value = ScrcpyStatus.DISCONNECTED
            if (stopped) break
            val delayMs = listOf(500L, 1000L, 2000L, 4000L, 8000L)[minOf(attempt, 4)]
            attempt++
            delay(delayMs)
        }
    }

    suspend fun sendInput(json: String) {
        liveSession?.send(Frame.Text(json))
    }

    fun stop() { stopped = true }
}
