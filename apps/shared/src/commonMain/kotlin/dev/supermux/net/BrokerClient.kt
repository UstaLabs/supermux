package dev.supermux.net

import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import dev.supermux.util.PerfTrace
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    // Optional per-connection reachability signal (multi-host fleet UI): invoked with `true`
    // right after the socket opens and `false` when it drops. Default null keeps every existing
    // single-host / iOS / desktop caller unchanged.
    private val onConnectionChange: ((Boolean) -> Unit)? = null,
    // The `subscribe` frame sent on every (re)connect, built at connect time so it can carry the
    // caller's current state (e.g. which chats are on screen) and this connection's request id for
    // the load-time trace (card #54). Default: a plain subscribe.
    private val subscribeFrame: (req: String) -> String = { "{\"type\":\"subscribe\"}" },
    // Which app this is, sent as /ws query params (TELEMETRY.md `client.connected`). Null = say nothing.
    private val clientInfo: ClientInfo? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val _frames = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<ServerFrame> = _frames
    val sync = ConnectionSyncState()
    private var liveSession: DefaultClientWebSocketSession? = null

    suspend fun run() {
        var attempt = 0
        while (true) {
            // Card #54: one request id per connection; the broker's half of it (auth, the snapshot
            // build) is recorded under the same id and this launch's trace.
            val req = PerfTrace.nextReq()
            val connectingAt = PerfTrace.elapsedMs()
            try {
                println("[BrokerClient] connecting $baseUrl/ws")
                PerfTrace.milestone("ws.connecting", "req" to req, "attempt" to attempt, "host" to baseUrl)
                val wsUrl = wsBaseUrl(baseUrl)
                val query = clientInfo.toQuery()
                http.webSocket(
                    urlString = "$wsUrl/ws$query${if (query.isEmpty()) "?" else "&"}trace=${PerfTrace.launchId}&req=$req",
                    request = { bearer(token) },
                ) {
                    attempt = 0
                    println("[BrokerClient] connected")
                    PerfTrace.milestone(
                        "ws.open",
                        "req" to req,
                        "handshakeMs" to PerfTrace.elapsedMs() - connectingAt,
                        "ext" to (call.response.headers["Sec-WebSocket-Extensions"] ?: "none"),
                    )
                    onConnectionChange?.invoke(true)
                    try {
                        liveSession = this
                        // The broker sends the full snapshot only in reply to a `subscribe`.
                        send(Frame.Text(subscribeFrame(req)))
                        val subscribedAt = PerfTrace.elapsedMs()
                        PerfTrace.milestone("ws.subscribe.sent", "req" to req)
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                // Answer the broker's heartbeat so it doesn't drop us.
                                if (text.contains("\"type\":\"ping\"")) {
                                    send(Frame.Text("{\"type\":\"pong\"}"))
                                    continue
                                }
                                val isSnapshot = text.startsWith("{\"type\":\"snapshot\"")
                                if (isSnapshot) {
                                    PerfTrace.milestone("ws.snapshot.arrived", "req" to req, "chars" to text.length, "sinceSubscribeMs" to PerfTrace.elapsedMs() - subscribedAt)
                                }
                                // Decode per-frame: an unmodeled `type` (the broker sends many
                                // frames we don't model yet) must NOT drop the whole connection.
                                val decodeStart = PerfTrace.elapsedMicros()
                                val parsed = try {
                                    json.decodeFromString<ServerFrame>(text)
                                } catch (e: Throwable) {
                                    println("[BrokerClient] skip frame (${e.message}) :: ${text.take(60)}")
                                    null
                                }
                                val decodeMicros = PerfTrace.elapsedMicros() - decodeStart
                                PerfTrace.countFrame(parsed?.let { it::class.simpleName } ?: "unparsed", text.length, decodeMicros)
                                if (parsed is ServerFrame.Snapshot) {
                                    PerfTrace.milestone("ws.snapshot.decoded", "req" to req, "decodeMs" to decodeMicros / 1000)
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
            onConnectionChange?.invoke(false)
            sync.onDisconnect()
            delay(policy.delayForAttempt(attempt++))
        }
    }

    suspend fun send(frame: ClientFrame) {
        if (!trySend(frame)) println("[BrokerClient] send dropped (not connected)")
    }

    /**
     * Hand [frame] to the live socket. False when there is no socket, or it died under the write —
     * the frame did NOT reach the broker, so a caller that must not lose it (a chat message) keeps
     * it and sends again after the next snapshot.
     */
    suspend fun trySend(frame: ClientFrame): Boolean {
        val s = liveSession ?: return false
        return try {
            s.send(Frame.Text(json.encodeToString(ClientFrame.serializer(), frame)))
            true
        } catch (e: CancellationException) {
            // A dead socket's own job cancels the write; only rethrow if WE were cancelled.
            currentCoroutineContext().ensureActive()
            false
        } catch (e: Throwable) {
            println("[BrokerClient] send failed: $e")
            false
        }
    }
}
