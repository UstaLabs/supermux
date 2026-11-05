package dev.supermux.state

import dev.supermux.net.ClientInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Card #76: "Show replies as they're written" is a per-device setting, on by default. Off, the app's subscribe does
 * not ask for live replies (so it shows finished messages, as before); a change applies at once by resubscribing.
 */
class HostStoreLiveRepliesSettingTest {
    /** A broker that answers every subscribe with an empty snapshot and keeps what each subscribe asked. */
    private class Broker : AutoCloseable {
        val port = ServerSocket(0).use { it.localPort }
        val subscribes = CopyOnWriteArrayList<JsonObject>()
        private val server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing {
                webSocket("/ws") {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val text = frame.readText()
                        if (!text.contains("\"subscribe\"")) continue
                        subscribes += Json.parseToJsonElement(text).jsonObject
                        send(Frame.Text("""{"type":"snapshot","sessions":[]}"""))
                    }
                }
            }
        }.start(wait = false)

        suspend fun awaitSubscribes(n: Int): Boolean = withTimeoutOrNull(10_000) { while (subscribes.size < n) delay(20); true } ?: false
        override fun close() = server.stop(0, 0)
    }

    private fun JsonObject.asksForLiveReplies(): Boolean = this["streams"]?.jsonPrimitive?.int == 1

    private fun connect(broker: Broker, settings: SettingsStore, block: suspend () -> Unit) = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient(ClientCIO) { install(ClientWebSockets) }
        val store = HostStore(
            "http://127.0.0.1:${broker.port}", "token", scope,
            HostStoreDeps(httpFactory = { http }, settings = settings, clock = FixedClock(), clientInfo = ClientInfo("desktop")),
        )
        try {
            block()
        } finally {
            store.close()
            scope.cancel()
            http.close()
            broker.close()
        }
    }

    @Test fun byDefaultTheAppAsksForLiveReplies() {
        val broker = Broker()
        connect(broker, FakeSettingsStore()) {
            assertEquals(true, broker.awaitSubscribes(1), "no subscribe arrived")
            assertEquals(true, broker.subscribes[0].asksForLiveReplies())
        }
    }

    @Test fun turnedOffItNeverAsksNotEvenOnTheFirstSubscribe() {
        val broker = Broker()
        val settings = FakeSettingsStore().apply { map.value = mapOf(SettingsKeys.LIVE_REPLIES to "false") }
        connect(broker, settings) {
            assertEquals(true, broker.awaitSubscribes(1), "no subscribe arrived")
            assertNull(broker.subscribes[0]["streams"], "a device that turned live replies off asked for them")
            delay(1_500)
            assertEquals(1, broker.subscribes.size, "no resubscribe without a change")
        }
    }

    @Test fun turningItOffAndOnAgainResubscribesAtOnce() {
        val broker = Broker()
        val settings = FakeSettingsStore()
        connect(broker, settings) {
            assertEquals(true, broker.awaitSubscribes(1))
            assertEquals(true, broker.subscribes[0].asksForLiveReplies())
            settings.putString(SettingsKeys.LIVE_REPLIES, "false")
            assertEquals(true, broker.awaitSubscribes(2), "turning it off did not resubscribe")
            assertNull(broker.subscribes[1]["streams"])
            settings.putString(SettingsKeys.LIVE_REPLIES, null)
            assertEquals(true, broker.awaitSubscribes(3), "turning it back on did not resubscribe")
            assertNotNull(broker.subscribes[2]["streams"])
            assertEquals(true, broker.subscribes[2].asksForLiveReplies())
        }
    }

    @Test fun onlyFalseTurnsItOff() {
        assertEquals(true, liveRepliesOn(null))
        assertEquals(true, liveRepliesOn("true"))
        assertEquals(false, liveRepliesOn("false"))
        assertEquals(true, liveRepliesOn("garbage"))
    }
}
