// Cluster E4 moved [dev.supermux.ui.settings.DevicesSettingsScreen] and its screen-level suite into
// `:ui` (`DevicesSettingsScreenTest`). What stays here is what only desktop can drive: the
// `AppShell` settings overlay — the hub opening on the Devices section, the rail switching to it,
// and per-host keying across two paired hosts.
package dev.supermux.desktop.settings

import dev.supermux.desktop.testDeps

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.state.FleetStore
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.state.HostStore
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
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DevicesSettingsHubTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { p -> runCatching { Files.deleteIfExists(p) } }
        tempFiles.clear()
    }

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("devices_settings_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }


    private data class DevicesAppHarness(
        val app: HostStore,
        val methods: CopyOnWriteArrayList<Pair<HttpMethod, String>>,
        val client: HttpClient,
    )

    private fun appForDevices(
        devicesJson: String? = """[{"name":"pixel-8","created_at":"2024-01-01T00:00:00Z","last_seen_at":"2024-06-01T12:00:00Z"}]""",
        addJson: String = """{"url":"https://pair.example/tok","name":"new-phone"}""",
        addStatus: HttpStatusCode = HttpStatusCode.OK,
        revokeStatus: HttpStatusCode = HttpStatusCode.OK,
        mutableDevices: AtomicReference<String>? = null,
    ): DevicesAppHarness {
        val methods = CopyOnWriteArrayList<Pair<HttpMethod, String>>()
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            methods.add(req.method to path)
            when {
                path == "/devices" && req.method == HttpMethod.Get -> {
                    val body = mutableDevices?.get() ?: devicesJson
                    if (body == null) {
                        respond("{}", HttpStatusCode.InternalServerError, jsonHeaders)
                    } else {
                        respond(body, HttpStatusCode.OK, jsonHeaders)
                    }
                }
                path == "/devices" && req.method == HttpMethod.Post ->
                    respond(addJson, addStatus, jsonHeaders)
                path.startsWith("/devices/") && req.method == HttpMethod.Delete ->
                    respond("{}", revokeStatus, jsonHeaders)
                else ->
                    respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = HttpClient(engine)
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", client),
        )
        return DevicesAppHarness(app, methods, client)
    }

    @Test fun settings_hub_opens_devices_section_and_loads() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.Devices) }
        val harness = appForDevices()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    harness.app, ui,
                    ShellStateStore(tempPath("state")),
                    LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("settings_overlay").assertIsDisplayed()
        onNodeWithTag("settings_hub").assertIsDisplayed()
        onNodeWithTag("devices_settings_screen").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_pixel-8").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("settings_section_devices").assertIsDisplayed()
        harness.client.close()
    }

    @Test fun rail_switches_from_agents_to_devices() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.Agents) }
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when (req.url.encodedPath) {
                "/agents/status" ->
                    respond(
                        """[{"kind":"claude","installed":true,"authed":true}]""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                "/devices" ->
                    respond(
                        """[{"name":"pixel-8","last_seen_at":"2024-06-01T12:00:00Z"}]""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = HttpClient(engine)
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", client),
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
        onNodeWithTag("settings_section_devices").performClick()
        waitForIdle()
        assertEquals(SettingsSection.Devices, ui.settingsSection)
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_pixel-8").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("settings_hub_back").assertIsDisplayed()
        client.close()
    }

    @Test fun multi_host_keying_reloads_devices_per_active_host() = runComposeUiTest {
        val devicesA = """[{"name":"host-a-phone"}]"""
        val devicesB = """[{"name":"host-b-tablet"}]"""
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
                val devicesJson = when {
                    url.contains("a.relay") -> devicesA
                    else -> devicesB
                }
                val engine = MockEngine { req ->
                    val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
                    when {
                        req.url.encodedPath == "/devices" ->
                            respond(devicesJson, HttpStatusCode.OK, jsonHeaders)
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
        val ui = ShellUiState().apply { openSettings(SettingsSection.Devices) }
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
                onNodeWithTag("device_row_host-a-phone").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        fleet.setActiveHost("h2")
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_host-b-tablet").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("device_row_host-a-phone").assertDoesNotExist()
        fleet.close()
    }

    @Test fun open_settings_devices_selects_section() {
        val ui = ShellUiState()
        ui.openSettings(SettingsSection.Devices)
        assertTrue(ui.settingsOpen)
        assertEquals(SettingsSection.Devices, ui.settingsSection)
        assertTrue(ui.overlayOpen)
        assertFalse(ui.launcherOpen)
    }
}
