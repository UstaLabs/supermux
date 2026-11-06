package dev.supermux.state

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Card #76: the startup race. The socket used to open off the UI thread at once, while the frame collector only
 * subscribed when the caller's (UI) thread first ran it. `frames` keeps no replay, so a snapshot that arrived while
 * that thread was still busy (a slow first composition) was dropped, and the app showed no sessions and no
 * workspaces until a reconnect. Seen with the desktop app under Xvfb: window at +21 s, snapshot at +28 s.
 */
class HostStoreStartupRaceTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test fun a_snapshot_that_arrives_while_the_ui_thread_is_busy_is_not_lost() = runBlocking<Unit> {
        val port = freePort()
        val snapshotsSent = AtomicInteger()
        // A broker that answers the subscribe with a one-session snapshot at once.
        val server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing {
                webSocket("/ws") {
                    for (frame in incoming) {
                        if (frame is Frame.Text && frame.readText().contains("\"subscribe\"")) {
                            send(Frame.Text("""{"type":"snapshot","sessions":[{"id":"s1","name":"alpha","workdir":"/w","agent":"claude"}]}"""))
                            snapshotsSent.incrementAndGet()
                        }
                    }
                }
            }
        }
        server.start(wait = false)

        // The "UI thread": one thread, busy (a slow first composition) until the latch opens.
        val uiThread = Executors.newSingleThreadExecutor()
        val busy = CountDownLatch(1)
        uiThread.execute { busy.await() }
        val ui = CoroutineScope(SupervisorJob() + uiThread.asCoroutineDispatcher())
        val http = HttpClient(ClientCIO) { install(ClientWebSockets) }
        val store = HostStore("http://127.0.0.1:$port", "token", ui, testDeps(http = http))
        try {
            // Give the socket every chance to connect and take the snapshot while the UI thread is still busy.
            withTimeoutOrNull(5_000) { while (!store.connected) delay(20) }
            delay(300)
            busy.countDown()

            val sessions = withTimeoutOrNull(10_000) { store.sessions.first { it.isNotEmpty() } }
            assertNotNull(sessions, "the snapshot was lost: no sessions (snapshots sent: ${snapshotsSent.get()})")
        } finally {
            busy.countDown()
            store.close()
            ui.cancel()
            uiThread.shutdownNow()
            http.close()
            server.stop(0, 0)
        }
    }
}
