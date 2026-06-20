package dev.supermux.net

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
import io.ktor.websocket.readBytes
import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end run-loop test: a throwaway local WS server replays the captured
 * server→client RFB byte stream (`rfb-zrle-session.bin`), and a real [VncClient]
 * connects over a real Ktor CIO WebSocket. Asserts the client completes the
 * handshake (size = 1280x800) and emits one FramebufferUpdate of one rect,
 * exercising the suspend `readN` rolling buffer against arbitrary frame splits.
 *
 * The client runs in a dedicated scope (not a child of the test's runBlocking) so
 * teardown is deterministic — we cancel the scope and stop the server explicitly.
 */
class VncClientTest {
    private fun loadFixture(name: String): ByteArray {
        javaClass.getResourceAsStream("/$name")?.use { return it.readBytes() }
        for (c in listOf("src/commonTest/resources/$name", "shared/src/commonTest/resources/$name", "apps/shared/src/commonTest/resources/$name")) {
            val f = File(c); if (f.exists()) return f.readBytes()
        }
        error("fixture $name not found")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test fun runs_handshake_and_emits_one_update() {
        val serverBytes = loadFixture("rfb-zrle-session.bin")
        val port = freePort()
        val server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/display") {
                    val reader = launch {
                        try { for (f in incoming) { if (f is Frame.Binary) f.readBytes() } } catch (_: Throwable) {}
                    }
                    // Stream the captured bytes split small to prove cross-frame reassembly.
                    var off = 0
                    val step = 137
                    while (off < serverBytes.size) {
                        val end = minOf(off + step, serverBytes.size)
                        send(Frame.Binary(true, serverBytes.copyOfRange(off, end)))
                        off = end
                    }
                    try { delay(10_000) } catch (_: Throwable) {}
                    reader.cancel()
                }
            }
        }
        server.start(wait = false)

        val client = HttpClient(ClientCIO) { install(ClientWebSockets) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val vnc = VncClient("ws://127.0.0.1:$port", "test-token", client, "fixture")

        val firstUpdate = CompletableDeferred<List<VncRect>>()
        scope.launch { vnc.updates.collect { if (!firstUpdate.isCompleted) firstUpdate.complete(it) } }
        scope.launch { vnc.run() }

        try {
            val update = runBlocking { withTimeoutOrNull(15_000) { firstUpdate.await() } }
            assertNotNull(update, "VncClient did not emit a FramebufferUpdate within 15s (size=${vnc.size.value}, status=${vnc.status.value})")
            assertEquals(1280 to 800, vnc.size.value)
            assertEquals(VncStatus.CONNECTED, vnc.status.value)
            assertEquals(1, update.size)
            val rect = update[0]
            assertEquals(1280, rect.width)
            assertEquals(800, rect.height)
            assertEquals(1280 * 800 * 4, rect.bgra.size)
            val o = (400 * 1280 + 640) * 4
            assertEquals(
                listOf(95, 58, 31, 255),
                listOf(
                    rect.bgra[o].toInt() and 0xff, rect.bgra[o + 1].toInt() and 0xff,
                    rect.bgra[o + 2].toInt() and 0xff, rect.bgra[o + 3].toInt() and 0xff,
                ),
            )
        } finally {
            vnc.stop()
            scope.cancel()
            client.close()
            server.stop(100, 300)
        }
    }

    @Test fun pointer_and_key_encoders_produce_expected_bytes() {
        assertTrue(RfbCodec.encodePointerEvent(0x01, 100, 200).size == 6)
        assertTrue(RfbCodec.encodeKeyEvent(Keysyms.RETURN, true).size == 8)
    }
}
