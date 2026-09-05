// Desktop's `AppShell` wiring for the System section of the Settings hub (cluster E3 split).
package dev.supermux.desktop.settings

import dev.supermux.desktop.testDeps

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.state.FleetStore
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.state.cioHttpFactory
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.desktop.shell.AppShell
import dev.supermux.desktop.shell.ShellStateStore
import dev.supermux.desktop.shell.ShellUiState
import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Desktop's shell around the System settings section.
 *
 * The screen itself moved to `:ui` in cluster E3
 * (`dev.supermux.ui.settings.SystemSettingsScreenTest` holds its 28 load / update / restart /
 * mock-broker tests). What can only be asserted here is what the desktop shell adds: opening the
 * hub from [ShellUiState], the rail switching sections, per-active-host reloading in a fleet,
 * overlay exclusivity with the app updater — and the real disconnect→reconnect against a local
 * stub broker, which needs a ktor SERVER (`:ui` jvmTest has only the mock client engine).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SystemSettingsHubTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { p -> runCatching { Files.deleteIfExists(p) } }
        tempFiles.clear()
    }

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("system_settings_hub_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    private fun appForSystem(
        statusJson: String = """{"current":"1.2.3","commit":"abc12345","latest":null,"updateAvailable":false,"notesUrl":"https://n","mode":"binary","state":"idle","lastChecked":1717200000000,"lastError":null,"disabled":false}""",
    ): HostStore {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                req.url.encodedPath == "/api/update/status" && req.method == HttpMethod.Get ->
                    respond(statusJson, HttpStatusCode.OK, jsonHeaders)
                else -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
    }

    /**
     * Real disconnect → reconnect: local stub broker accepts WS, serves restart POST by closing
     * all sockets, then accepts a second connection with a fresh snapshot. This is the path the
     * fake Boolean-flip test missed (`connectOnInit=false` never opened a socket).
     */
    @Test fun restart_broker_disconnects_and_reconnects_against_stub() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val wsOpens = AtomicInteger(0)
        val restartPosts = AtomicInteger(0)
        val liveSessions =
            java.util.Collections.synchronizedList(mutableListOf<DefaultWebSocketSession>())

        val server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing {
                get("/api/update/status") {
                    call.respondText(
                        """{"current":"stub-1","commit":"deadbeef","mode":"binary","state":"idle","updateAvailable":false}""",
                        contentType = io.ktor.http.ContentType.Application.Json,
                    )
                }
                post("/system/restart") {
                    restartPosts.incrementAndGet()
                    // Close after responding so the client observes a clean disconnect.
                    call.respondText("{}", contentType = io.ktor.http.ContentType.Application.Json)
                    for (session in liveSessions.toList()) {
                        try {
                            session.close(CloseReason(CloseReason.Codes.SERVICE_RESTART, "stub restart"))
                        } catch (_: Throwable) {
                        }
                    }
                    liveSessions.clear()
                }
                webSocket("/ws") {
                    wsOpens.incrementAndGet()
                    liveSessions.add(this)
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text && frame.readText().contains("subscribe")) {
                                send(
                                    Frame.Text(
                                        """{"type":"snapshot","sessions":[],"logs":{},"activity":{},"bgTasks":{},"agentState":{},"commands":{},"commandsResolved":{},"reads":{}}""",
                                    ),
                                )
                            }
                        }
                    } finally {
                        liveSessions.remove(this)
                    }
                }
            }
        }
        server.start(wait = false)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val app = HostStore(
            baseUrl = "ws://127.0.0.1:$port",
            token = "stub-token",
            scope = scope,
            deps = testDeps().let { d ->
                HostStoreDeps(httpFactory = cioHttpFactory(), settings = d.settings, clock = d.clock)
            },
            connectOnInit = true,
        )
        try {
            // Wait for first snapshot (connected).
            val first = withTimeoutOrNull(10_000) {
                while (!app.connected) delay(50)
                true
            }
            assertTrue(first == true, "never received first snapshot (opens=${wsOpens.get()})")
            assertEquals(1, wsOpens.get())

            assertTrue(app.restartBroker())
            assertEquals(1, restartPosts.get())

            // Drop then re-sync: connection count must go 1→2 with a fresh snapshot.
            val reconnected = withTimeoutOrNull(15_000) {
                while (wsOpens.get() < 2 || !app.connected) delay(50)
                true
            }
            assertTrue(
                reconnected == true,
                "did not reconnect after restart (opens=${wsOpens.get()}, connected=${app.connected})",
            )
            assertEquals(2, wsOpens.get())
            assertTrue(app.connected)
        } finally {
            app.close()
            scope.cancel()
            server.stop(100, 500)
        }
    }

    @Test fun settings_hub_opens_system_section_and_loads() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.System) }
        val app = appForSystem()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    app, ui,
                    ShellStateStore(tempPath("state")),
                    LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("settings_overlay").assertIsDisplayed()
        onNodeWithTag("settings_hub").assertIsDisplayed()
        onNodeWithTag("system_settings_screen").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithText("supermux 1.2.3").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("settings_section_system").assertIsDisplayed()
    }

    @Test fun rail_switches_from_agents_to_system() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.Agents) }
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when (req.url.encodedPath) {
                "/agents/status" ->
                    respond(
                        """[{"kind":"claude","installed":true,"authed":true}]""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                "/api/update/status" ->
                    respond(
                        """{"current":"4.5.6","commit":"cafebabe","mode":"binary","state":"idle","updateAvailable":false}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    app, ui,
                    ShellStateStore(tempPath("rail-state")),
                    LauncherStore(tempPath("rail-launcher")),
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_row_claude").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("settings_section_system").performClick()
        waitForIdle()
        assertEquals(SettingsSection.System, ui.settingsSection)
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithText("supermux 4.5.6").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("settings_hub_back").assertIsDisplayed()
    }

    @Test fun multi_host_keying_reloads_system_per_active_host() = runComposeUiTest {
        val statusA = """{"current":"host-a-1.0","commit":"aaaaaaaa","mode":"binary","state":"idle","updateAvailable":false}"""
        val statusB = """{"current":"host-b-2.0","commit":"bbbbbbbb","mode":"docker","state":"idle","updateAvailable":false}"""
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = PairedHostStore(
            object : HostPersistence {
                var hosts = mutableListOf(
                    PairedHost(
                        recordId = "h1",
                        hostId = "host-a",
                        displayName = "Host A",
                        token = "t",
                        relayUrl = "https://a.relay.supermux.dev",
                    ),
                    PairedHost(
                        recordId = "h2",
                        hostId = "host-b",
                        displayName = "Host B",
                        token = "t",
                        relayUrl = "https://b.relay.supermux.dev",
                    ),
                )
                override fun loadAll() = hosts.toList()
                override fun saveAll(hosts: List<PairedHost>) {
                    this.hosts = hosts.toMutableList()
                }
            },
        ) { "rec-unused" }
        val fleet = FleetStore(
            store = store,
            scope = scope,
            deps = testDeps(),
            appFactory = { url, token, onConn ->
                val statusJson = when {
                    url.contains("a.relay") -> statusA
                    else -> statusB
                }
                val engine = MockEngine { req ->
                    val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
                    when {
                        req.url.encodedPath == "/api/update/status" ->
                            respond(statusJson, HttpStatusCode.OK, jsonHeaders)
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
                    }
                }
                HostStore(
                    baseUrl = url,
                    token = token,
                    scope = scope,
                    deps = testDeps(),
                    connectOnInit = false,
                    sendFrameOverride = { },
                    apiOverride = BrokerApi(url, token, HttpClient(engine)),
                    onConnectionChange = onConn,
                )
            },
        )
        val ui = ShellUiState().apply { openSettings(SettingsSection.System) }
        val primary = fleet.appForRecord("h1")!!
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    primary, ui,
                    ShellStateStore(tempPath("mh-state")),
                    LauncherStore(tempPath("mh-launcher")),
                    fleet = fleet,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithText("supermux host-a-1.0").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        fleet.setActiveHost("h2")
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithText("supermux host-b-2.0").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("supermux host-a-1.0").assertDoesNotExist()
        fleet.close()
    }

    @Test fun open_settings_system_selects_section() {
        val ui = ShellUiState()
        ui.openSettings(SettingsSection.System)
        assertTrue(ui.settingsOpen)
        assertEquals(SettingsSection.System, ui.settingsSection)
        assertTrue(ui.overlayOpen)
        assertFalse(ui.launcherOpen)
        assertFalse(ui.appUpdateOpen)
    }

    @Test fun open_settings_system_closes_app_update_overlay() {
        val ui = ShellUiState()
        ui.openAppUpdate()
        assertTrue(ui.appUpdateOpen)
        ui.openSettings(SettingsSection.System)
        assertTrue(ui.settingsOpen)
        assertFalse(ui.appUpdateOpen)
    }
}
