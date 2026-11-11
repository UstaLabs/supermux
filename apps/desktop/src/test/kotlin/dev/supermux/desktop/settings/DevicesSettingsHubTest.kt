// The machine page's Devices (#97 Step 2) through desktop's `AppShell` settings overlay: the hub opening the
// machine page at its Devices, the page listing them after another page, and per-host keying across two paired
// hosts. The page's own behaviour is `:ui`'s `MachinePartsTest`.
package dev.supermux.desktop.settings

import dev.supermux.desktop.testDeps

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.nav.Route
import dev.supermux.desktop.shell.TestAppShell
import dev.supermux.ui.shell.ShellUiState
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
        // #97 Step 2: Devices is a part of the machine page; the old address lands there.
        val ui = ShellUiState().apply { openSettings(SettingsSection.Machine, sub = "devices") }
        val harness = appForDevices()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                TestAppShell(harness.app, ui, online = true)
            }
        }
        waitForIdle()
        onNodeWithTag("settings_overlay").assertIsDisplayed()
        onNodeWithTag("settings_hub").assertIsDisplayed()
        onNodeWithTag("machine_page").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_pixel-8").assertExists()
                true
            } catch (_: Throwable) {
                false
            }
        }
        // The machine page is opened by a machine card, never from a rail row.
        onNodeWithTag("settings_section_machine").assertDoesNotExist()
        assertEquals(SettingsSection.Machine, ui.settingsSection)
        harness.client.close()
    }

    @Test fun the_machine_page_lists_the_devices_after_agents() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.Providers) }
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
                TestAppShell(app, ui, online = true)
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
        // What a machine card does: the machine page lists the devices itself.
        ui.openSettings(SettingsSection.Machine)
        waitForIdle()
        onNodeWithTag("machine_page").assertIsDisplayed()
        assertEquals(SettingsSection.Machine, ui.settingsSection)
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_pixel-8").assertExists()
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
        val conns = mutableListOf<(Boolean) -> Unit>()
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
                conns += onConn
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
        // The machine page loads a machine's parts only while it is online.
        conns.forEach { it(true) }
        val ui = ShellUiState().apply { openMachine("devices") }
        val primary = fleet.appForRecord("h1")!!
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                TestAppShell(primary, ui, fleet = fleet)
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_host-a-phone").assertExists()
                true
            } catch (_: Throwable) {
                false
            }
        }
        fleet.setActiveHost("h2")
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_host-b-tablet").assertExists()
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
        ui.openSettings(SettingsSection.Machine, sub = "devices")
        assertTrue(ui.settingsOpen)
        assertEquals(SettingsSection.Machine, ui.settingsSection)
        assertEquals(Route.Settings(SettingsSection.Machine, anchor = "devices"), ui.currentRoute)
        assertTrue(ui.overlayOpen)
        assertFalse(ui.launcherOpen)
    }
}
