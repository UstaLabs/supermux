package dev.supermux.desktop.settings

import dev.supermux.desktop.testDeps

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.input.key.Key
import dev.supermux.state.FleetStore
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.state.HostStore
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.desktop.platform.openInBrowserOverride
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop's `AppShell` wiring for the Agents section of the Settings hub.
 *
 * The screen itself moved to `:ui` in cluster E2 (`dev.supermux.ui.settings.AgentSettingsScreenTest`
 * holds its 34 state-machine / mutation / mock-broker tests). What can only be asserted here is
 * what the desktop shell adds around it: opening the hub from [ShellUiState], Escape closing it,
 * the shell shortcuts being gated off while it is up, overlay exclusivity, the rail switching
 * sections without a nested Back, and the hub reloading per active host in a fleet.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AgentSettingsHubTest {

    // OAuth / "Open sign-in" paths call openInBrowser; never spawn a real browser from tests.
    private val openedUrls = mutableListOf<String>()

    @kotlin.test.BeforeTest
    fun installBrowserSeam() {
        openedUrls.clear()
        openInBrowserOverride = { openedUrls.add(it) }
    }

    @AfterTest
    fun restoreBrowserSeam() {
        openInBrowserOverride = null
        openedUrls.clear()
    }

    // ── HostStore + mocked BrokerApi ──────────────────────────────────────────────────────


    private val tempFiles = mutableListOf<Path>()

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("agent_settings_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    @AfterTest fun cleanup() {
        tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun appForAgents(
        statusJson: String? = """[{"kind":"claude","installed":true,"authed":true},{"kind":"codex","installed":true,"authed":false}]""",
        statusCode: HttpStatusCode = HttpStatusCode.OK,
        loginJson: String = """{"kind":"claude","phase":"awaiting_user","url":"https://auth.example","code":"XYZ"}""",
        installJson: String = """{"state":"running","log":"installing"}""",
        mutationStatus: HttpStatusCode = HttpStatusCode.OK,
        baseUrl: String = "ws://test:9898",
    ): HostStore {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            when {
                req.method == HttpMethod.Get && path == "/agents/status" -> {
                    if (statusJson == null) {
                        respond("{}", HttpStatusCode.InternalServerError, jsonHeaders)
                    } else {
                        respond(statusJson, statusCode, jsonHeaders)
                    }
                }
                path.endsWith("/login") && req.method == HttpMethod.Post ->
                    respond(loginJson, HttpStatusCode.OK, jsonHeaders)
                path.endsWith("/login") && req.method == HttpMethod.Get ->
                    respond(loginJson, HttpStatusCode.OK, jsonHeaders)
                path.endsWith("/install") ->
                    respond(installJson, HttpStatusCode.OK, jsonHeaders)
                path == "/opencode/providers" ->
                    respond("[]", HttpStatusCode.OK, jsonHeaders)
                // Mutation endpoints: honor [mutationStatus] so non-2xx can be proven through
                // HostStore + BrokerApi (not injected booleans).
                path == "/settings/config" && req.method == HttpMethod.Put ->
                    respond("{}", mutationStatus, jsonHeaders)
                path == "/opencode/auth/key" && req.method == HttpMethod.Post ->
                    respond("{}", mutationStatus, jsonHeaders)
                path == "/opencode/auth/oauth/finish" && req.method == HttpMethod.Post ->
                    respond("{}", mutationStatus, jsonHeaders)
                path.endsWith("/login/code") && req.method == HttpMethod.Post ->
                    respond("{}", mutationStatus, jsonHeaders)
                else ->
                    respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val api = BrokerApi(baseUrl, "t", HttpClient(engine))
        return HostStore(
            baseUrl = baseUrl,
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = api,
        )
    }
    // ── Settings hub overlay wiring ─────────────────────────────────────────────────────────────

    @Test fun settings_hub_opens_from_ui_and_loads_agents() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.Agents) }
        val app = appForAgents()
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
        onNodeWithTag("agent_settings_screen").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_row_claude").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    @Test fun escape_closes_the_settings_hub() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings() }
        val app = appForAgents()
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
        onNodeWithTag("settings_overlay").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(ui.settingsOpen)
        onNodeWithTag("settings_overlay").assertDoesNotExist()
    }

    @Test fun shell_shortcuts_are_gated_off_while_settings_hub_is_up() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings() }
        val app = appForAgents()
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
        assertFalse(ui.sidebarCollapsed)
        onNodeWithTag("settings_hub").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.B) }
        }
        waitForIdle()
        assertFalse(ui.sidebarCollapsed)
        assertTrue(ui.settingsOpen)
    }

    @Test fun opening_settings_closes_any_other_open_overlay() {
        val ui = ShellUiState()
        ui.openUsage()
        ui.openSettings()
        assertFalse(ui.usageOpen)
        assertTrue(ui.settingsOpen)
        assertEquals(SettingsSection.Agents, ui.settingsSection)
    }

    @Test fun rail_switches_to_editor_lsp_without_nested_back() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.Agents) }
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi(
                "ws://test:9898",
                "t",
                HttpClient(MockEngine { req ->
                    val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
                    when {
                        req.url.encodedPath == "/agents/status" ->
                            respond("[]", HttpStatusCode.OK, jsonHeaders)
                        req.url.encodedPath == "/settings/editor" ->
                            respond(
                                """{"lsp":{"servers":[{"id":"typescript","label":"TypeScript","extensions":[".ts"],"enabled":true,"state":"ready","installable":true}]}}""",
                                HttpStatusCode.OK, jsonHeaders,
                            )
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
                    }
                }),
            ),
        )
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    app, ui,
                    ShellStateStore(tempPath("state2")),
                    LauncherStore(tempPath("launcher2")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("settings_section_editorlsp").performClick()
        waitForIdle()
        assertEquals(SettingsSection.EditorLsp, ui.settingsSection)
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("lsp_settings_screen").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        // Nested back must be gone when embedded in the hub.
        onNodeWithTag("lsp_settings_back").assertDoesNotExist()
        onNodeWithText("TypeScript").assertIsDisplayed()
        // Hub back remains.
        onNodeWithTag("settings_hub_back").assertIsDisplayed()
    }

    @Test fun multi_host_keying_reloads_per_active_host() = runComposeUiTest {
        // Go through Fleet + AppShell (key(activeHostId) + hostApp), not a manual remount.
        val statusA =
            """[{"kind":"claude","installed":true,"authed":true}]"""
        val statusB =
            """[{"kind":"cursor","installed":false,"authed":false}]"""
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
                        req.url.encodedPath == "/agents/status" ->
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
        val ui = ShellUiState().apply { openSettings(SettingsSection.Agents) }
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
                onNodeWithTag("agent_row_claude").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        fleet.setActiveHost("h2")
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_row_cursor").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("agent_row_claude").assertDoesNotExist()
        fleet.close()
    }

}
