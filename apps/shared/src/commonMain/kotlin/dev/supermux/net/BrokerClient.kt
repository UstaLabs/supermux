package dev.supermux.net

import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ReconnectPolicy(private val baseMs: Long, private val maxMs: Long) {
    fun delayForAttempt(attempt: Int): Long {
        var d = baseMs
        repeat(attempt) { d = (d * 2).coerceAtMost(maxMs) }
        return d
    }
}

// The broker sends a full snapshot on (re)connect; until it arrives we are not
// synced and must not assume any session state. Mirrors the broker's
// seekToEnd/re-snapshot reconnect semantics.
class ConnectionSyncState {
    var synced: Boolean = false; private set
    fun onFrame(isSnapshot: Boolean) { if (isSnapshot) synced = true }
    fun onDisconnect() { synced = false }
}

class BrokerClient(
    private val baseUrl: String,           // e.g. wss://host
    private val token: String,             // device token (bearer)
    private val http: HttpClient,
    private val policy: ReconnectPolicy = ReconnectPolicy(500, 8000),
) {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val _frames = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<ServerFrame> = _frames
    val sync = ConnectionSyncState()
    private var liveSession: DefaultClientWebSocketSession? = null

    suspend fun run() {
        var attempt = 0
        while (true) {
            try {
                println("[BrokerClient] connecting $baseUrl/ws")
                val wsUrl = wsBaseUrl(baseUrl)
                http.webSocket(
                    urlString = "$wsUrl/ws",
                    request = { header("Authorization", "Bearer $token") },
                ) {
                    attempt = 0
                    println("[BrokerClient] connected")
                    try {
                        liveSession = this
                        // The broker sends the full snapshot only in reply to a `subscribe`.
                        send(Frame.Text("{\"type\":\"subscribe\"}"))
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                // Answer the broker's heartbeat so it doesn't drop us.
                                if (text.contains("\"type\":\"ping\"")) {
                                    send(Frame.Text("{\"type\":\"pong\"}"))
                                    continue
                                }
                                // Decode per-frame: an unmodeled `type` (the broker sends many
                                // frames we don't model yet) must NOT drop the whole connection.
                                val parsed = try {
                                    json.decodeFromString<ServerFrame>(text)
                                } catch (e: Throwable) {
                                    println("[BrokerClient] skip frame (${e.message}) :: ${text.take(60)}")
                                    null
                                }
                                if (parsed != null) {
                                    sync.onFrame(parsed is ServerFrame.Snapshot)
                                    _frames.emit(parsed)
                                    println("[BrokerClient] rx ${parsed::class.simpleName}")
                                }
                            }
                        }
                    } finally {
                        liveSession = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("[BrokerClient] ws error: $e")
            }
            sync.onDisconnect()
            delay(policy.delayForAttempt(attempt++))
        }
    }

    suspend fun send(frame: ClientFrame) {
        val s = liveSession ?: run { println("[BrokerClient] send dropped (not connected)"); return }
        s.send(Frame.Text(json.encodeToString(ClientFrame.serializer(), frame)))
    }
}
