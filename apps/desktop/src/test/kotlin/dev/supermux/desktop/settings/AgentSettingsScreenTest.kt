package dev.supermux.desktop.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.desktop.workspace.SettingsSection
import dev.supermux.desktop.workspace.WorkspaceRoot
import dev.supermux.desktop.workspace.WorkspaceStateStore
import dev.supermux.desktop.workspace.WorkspaceUiState
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.net.BrokerApi
import dev.supermux.net.OpenCodeOAuthStart
import dev.supermux.net.OpenCodeProvider
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop-parity Task 1: [AgentSettingsScreen] login/install state machines + Settings hub wiring.
 *
 * Login phases: idle → pending (starting/awaiting_user) → done (success) | error (failed).
 * Install states: idle → running → done | failed.
 * BrokerApi is seeded via [MockEngine] / faked suspend lambdas — no live broker.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AgentSettingsScreenTest {

    // ── pure helpers ────────────────────────────────────────────────────────────────────────────

    @Test fun status_label_authed_is_authenticated() {
        assertEquals("Authenticated", statusLabel(AgentInstallStatus(kind = "claude", installed = true, authed = true)))
    }

    @Test fun status_label_missing_cli_is_not_installed() {
        assertEquals("Not installed", statusLabel(AgentInstallStatus(kind = "codex", installed = false, authed = false)))
    }

    @Test fun status_label_installed_unauthed() {
        assertEquals("Installed, not authenticated", statusLabel(AgentInstallStatus(kind = "cursor", installed = true, authed = false)))
    }

    @Test fun status_label_opencode_free_tier() {
        assertEquals("Ready · free tier", statusLabel(AgentInstallStatus(kind = "opencode", installed = true, authed = false)))
    }

    @Test fun normalize_install_state_maps_pending_to_running_and_error_to_failed() {
        assertEquals("running", normalizeInstallState("running"))
        assertEquals("running", normalizeInstallState("pending"))
        assertEquals("done", normalizeInstallState("done"))
        assertEquals("failed", normalizeInstallState("failed"))
        assertEquals("failed", normalizeInstallState("error"))
    }

    @Test fun is_active_login_phase_only_for_starting_and_awaiting_user() {
        assertTrue(isActiveLoginPhase("starting"))
        assertTrue(isActiveLoginPhase("awaiting_user"))
        assertFalse(isActiveLoginPhase("success"))
        assertFalse(isActiveLoginPhase("failed"))
        assertFalse(isActiveLoginPhase("cancelled"))
        assertFalse(isActiveLoginPhase(null))
    }

    @Test fun pretty_provider_name_title_cases_hyphenated_ids() {
        assertEquals("Google Vertex Ai", prettyProviderName("google-vertex_ai"))
    }

    // ── screen: load + list ─────────────────────────────────────────────────────────────────────

    private fun statuses() = listOf(
        AgentInstallStatus(kind = "claude", installed = true, authed = true),
        AgentInstallStatus(kind = "codex", installed = true, authed = false),
        AgentInstallStatus(kind = "cursor", installed = false, authed = false),
        AgentInstallStatus(kind = "opencode", installed = true, authed = false),
        AgentInstallStatus(kind = "grok", installed = true, authed = false),
    )

    private fun screen(
        agentStatuses: suspend () -> List<AgentInstallStatus> = { statuses() },
        agentStartLogin: suspend (String) -> AgentLoginState? = { null },
        agentPollLogin: suspend (String) -> AgentLoginState? = { null },
        agentSendCode: suspend (String, String) -> Unit = { _, _ -> },
        agentCancelLogin: suspend (String) -> Unit = {},
        agentSaveSecret: suspend (String, String) -> Boolean = { _, _ -> true },
        agentStartInstall: suspend (String) -> AgentInstallJob? = { null },
        agentPollInstall: suspend (String) -> AgentInstallJob? = { null },
        openCodeProviders: suspend () -> List<OpenCodeProvider> = { emptyList() },
        openCodeSetKey: suspend (String, String) -> Boolean = { _, _ -> true },
        openCodeStartOAuth: suspend (String, Int) -> OpenCodeOAuthStart? = { _, _ -> null },
        openCodeFinishOAuth: suspend (String, Int, String) -> Boolean = { _, _, _ -> true },
    ) = @Composable {
        AgentSettingsScreen(
            agentStatuses = agentStatuses,
            agentStartLogin = agentStartLogin,
            agentPollLogin = agentPollLogin,
            agentSendCode = agentSendCode,
            agentCancelLogin = agentCancelLogin,
            agentSaveSecret = agentSaveSecret,
            agentStartInstall = agentStartInstall,
            agentPollInstall = agentPollInstall,
            openCodeProviders = openCodeProviders,
            openCodeSetKey = openCodeSetKey,
            openCodeStartOAuth = openCodeStartOAuth,
            openCodeFinishOAuth = openCodeFinishOAuth,
        )
    }

    @Test fun agents_render_from_a_fake_status_list() = runComposeUiTest {
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
        waitForIdle()
        onNodeWithTag("agent_settings_screen").assertIsDisplayed()
        onNodeWithTag("agent_row_claude").assertIsDisplayed()
        onNodeWithTag("agent_row_codex").assertIsDisplayed()
        onNodeWithTag("agent_row_cursor").assertIsDisplayed()
        onNodeWithText("Authenticated").assertIsDisplayed()
        onNodeWithText("Not installed").assertIsDisplayed()
        onNodeWithText("Installed, not authenticated").assertIsDisplayed()
    }

    @Test fun empty_status_list_shows_error_caption() = runComposeUiTest {
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen(agentStatuses = { emptyList() })() } }
        waitForIdle()
        onNodeWithTag("agent_settings_error").assertIsDisplayed()
        onNodeWithText("Couldn't load agent statuses.").assertIsDisplayed()
    }

    // ── login state machine: idle → pending → done | error ──────────────────────────────────────

    @Test fun login_idle_to_pending_shows_awaiting_user_url_and_cancel() = runComposeUiTest {
        val startCalls = AtomicInteger(0)
        val started = AtomicReference(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "claude", installed = true, authed = false))
                    },
                    agentStartLogin = {
                        startCalls.incrementAndGet()
                        started.set(true)
                        AgentLoginState(kind = "claude", phase = "starting")
                    },
                    // Resume poll returns null until start so we exercise idle → start, not resume.
                    agentPollLogin = {
                        if (!started.get()) null
                        else AgentLoginState(
                            kind = "claude",
                            phase = "awaiting_user",
                            url = "https://example.com/device",
                            code = "ABCD-1234",
                            needsCode = false,
                        )
                    },
                )()
            }
        }
        waitForIdle()
        // claude unauthed starts expanded; Start authorization is visible
        onNodeWithTag("agent_login_start_claude").assertIsDisplayed()
        onNodeWithTag("agent_login_start_claude").performClick()
        waitForIdle()
        // Poll interval is 1.5s — wait for awaiting_user UI (URL + device code), not just the shell.
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_login_open_url").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(startCalls.get() >= 1)
        onNodeWithTag("agent_login_device_code").assertIsDisplayed()
        onNodeWithTag("agent_login_cancel").assertIsDisplayed()
        onNodeWithText("ABCD-1234").assertIsDisplayed()
    }

    @Test fun login_pending_to_done_reloads_statuses() = runComposeUiTest {
        val pollCount = AtomicInteger(0)
        val loadCount = AtomicInteger(0)
        val started = AtomicReference(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        val n = loadCount.incrementAndGet()
                        if (n == 1) {
                            listOf(AgentInstallStatus(kind = "claude", installed = true, authed = false))
                        } else {
                            listOf(AgentInstallStatus(kind = "claude", installed = true, authed = true))
                        }
                    },
                    agentStartLogin = {
                        started.set(true)
                        AgentLoginState(kind = "claude", phase = "starting")
                    },
                    agentPollLogin = {
                        if (!started.get()) {
                            null
                        } else {
                            val n = pollCount.incrementAndGet()
                            if (n < 2) {
                                AgentLoginState(kind = "claude", phase = "awaiting_user", url = "https://x")
                            } else {
                                AgentLoginState(kind = "claude", phase = "success")
                            }
                        }
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_login_start_claude").performClick()
        waitUntil(timeoutMillis = 8_000) {
            try {
                onNodeWithText("Ready").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(loadCount.get() >= 2, "expected reload after success, loads=${loadCount.get()}")
        assertTrue(pollCount.get() >= 2)
    }

    @Test fun login_pending_to_error_shows_failure_message() = runComposeUiTest {
        val started = AtomicReference(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "codex", installed = true, authed = false))
                    },
                    agentStartLogin = {
                        started.set(true)
                        AgentLoginState(kind = "codex", phase = "starting")
                    },
                    agentPollLogin = {
                        if (!started.get()) null
                        else AgentLoginState(kind = "codex", phase = "failed", error = "device denied")
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_login_start_codex").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_login_failed_codex").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Login failed: device denied").assertIsDisplayed()
    }

    @Test fun login_cancel_calls_broker_cancel() = runComposeUiTest {
        val cancelled = AtomicReference<String?>(null)
        val started = AtomicReference(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "claude", installed = true, authed = false))
                    },
                    agentStartLogin = {
                        started.set(true)
                        AgentLoginState(kind = "claude", phase = "awaiting_user", url = "https://x")
                    },
                    agentPollLogin = {
                        if (!started.get()) null
                        else AgentLoginState(kind = "claude", phase = "awaiting_user", url = "https://x")
                    },
                    agentCancelLogin = { cancelled.set(it) },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_login_start_claude").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_login_cancel").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("agent_login_cancel").performClick()
        waitForIdle()
        assertEquals("claude", cancelled.get())
        // Back to idle — Start authorization visible again
        onNodeWithTag("agent_login_start_claude").assertIsDisplayed()
    }

    // ── install state machine: idle → running → done | error ────────────────────────────────────

    @Test fun install_idle_to_running_shows_progress() = runComposeUiTest {
        val started = AtomicReference(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "cursor", installed = false, authed = false))
                    },
                    agentStartInstall = {
                        started.set(true)
                        AgentInstallJob(state = "running", log = "fetching…")
                    },
                    agentPollInstall = {
                        if (!started.get()) null
                        else AgentInstallJob(state = "running", log = "still going")
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_install_start_cursor").assertIsDisplayed()
        onNodeWithTag("agent_install_start_cursor").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_install_running_cursor").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Installing cursor…").assertIsDisplayed()
    }

    @Test fun install_running_to_done_reloads_statuses() = runComposeUiTest {
        val loadCount = AtomicInteger(0)
        val pollN = AtomicInteger(0)
        val started = AtomicReference(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        val n = loadCount.incrementAndGet()
                        if (n == 1) {
                            listOf(AgentInstallStatus(kind = "cursor", installed = false, authed = false))
                        } else {
                            listOf(AgentInstallStatus(kind = "cursor", installed = true, authed = false))
                        }
                    },
                    agentStartInstall = {
                        started.set(true)
                        AgentInstallJob(state = "running", log = "…")
                    },
                    agentPollInstall = {
                        if (!started.get()) {
                            null
                        } else {
                            val n = pollN.incrementAndGet()
                            if (n < 2) AgentInstallJob(state = "running", log = "…")
                            else AgentInstallJob(state = "done", log = "ok", exitCode = 0)
                        }
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_install_start_cursor").performClick()
        waitUntil(timeoutMillis = 8_000) {
            try {
                onNodeWithText("Installed, not authenticated").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(loadCount.get() >= 2)
    }

    @Test fun install_running_to_failed_shows_error() = runComposeUiTest {
        val started = AtomicReference(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "cursor", installed = false, authed = false))
                    },
                    agentStartInstall = {
                        started.set(true)
                        AgentInstallJob(state = "running")
                    },
                    agentPollInstall = {
                        if (!started.get()) null
                        else AgentInstallJob(state = "failed", log = "npm ERR")
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_install_start_cursor").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_install_error_cursor").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Installation failed.").assertIsDisplayed()
        onNodeWithText("Retry installation").assertIsDisplayed()
    }

    // ── DesktopAppState + mocked BrokerApi ──────────────────────────────────────────────────────

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
        statusJson: String = """[{"kind":"claude","installed":true,"authed":true},{"kind":"codex","installed":true,"authed":false}]""",
        loginJson: String = """{"kind":"claude","phase":"awaiting_user","url":"https://auth.example","code":"XYZ"}""",
        installJson: String = """{"state":"running","log":"installing"}""",
    ): DesktopAppState {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            when {
                req.method == HttpMethod.Get && path == "/agents/status" ->
                    respond(statusJson, HttpStatusCode.OK, jsonHeaders)
                path.endsWith("/login") && req.method == HttpMethod.Post ->
                    respond(loginJson, HttpStatusCode.OK, jsonHeaders)
                path.endsWith("/login") && req.method == HttpMethod.Get ->
                    respond(loginJson, HttpStatusCode.OK, jsonHeaders)
                path.endsWith("/install") ->
                    respond(installJson, HttpStatusCode.OK, jsonHeaders)
                path == "/opencode/providers" ->
                    respond("[]", HttpStatusCode.OK, jsonHeaders)
                else ->
                    respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = api,
        )
    }

    @Test fun desktop_app_state_agent_statuses_decodes_mock_broker() = runComposeUiTest {
        // Exercise the real DesktopAppState wrappers with ktor MockEngine (not just UI fakes).
        val app = appForAgents()
        var listed: List<AgentInstallStatus> = emptyList()
        // run blocking via compose LaunchedEffect is heavy; call through the screen's load path
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AgentSettingsScreen(
                    agentStatuses = {
                        listed = app.agentStatuses()
                        listed
                    },
                    agentStartLogin = { app.startAgentLogin(it) },
                    agentPollLogin = { app.agentLoginState(it) },
                    agentSendCode = { k, c -> app.sendAgentLoginCode(k, c) },
                    agentCancelLogin = { app.cancelAgentLogin(it) },
                    agentSaveSecret = { k, v -> app.saveAgentSecret(k, v) },
                    agentStartInstall = { app.startAgentInstall(it) },
                    agentPollInstall = { app.agentInstallState(it) },
                    openCodeProviders = { app.openCodeProviders() },
                    openCodeSetKey = { id, key -> app.setOpenCodeKey(id, key) },
                    openCodeStartOAuth = { id, m -> app.startOpenCodeOAuth(id, m) },
                    openCodeFinishOAuth = { id, m, c -> app.finishOpenCodeOAuth(id, m, c) },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            listed.isNotEmpty()
        }
        assertEquals(2, listed.size)
        assertEquals("claude", listed[0].kind)
        assertTrue(listed[0].authed)
        onNodeWithTag("agent_row_claude").assertIsDisplayed()
        onNodeWithTag("agent_row_codex").assertIsDisplayed()
    }

    // ── Settings hub overlay wiring ─────────────────────────────────────────────────────────────

    @Test fun settings_hub_opens_from_ui_and_loads_agents() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { openSettings(SettingsSection.Agents) }
        val app = appForAgents()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(
                    app, ui,
                    WorkspaceStateStore(tempPath("state")),
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
        val ui = WorkspaceUiState().apply { openSettings() }
        val app = appForAgents()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(
                    app, ui,
                    WorkspaceStateStore(tempPath("state")),
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

    @Test fun workspace_shortcuts_are_gated_off_while_settings_hub_is_up() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { openSettings() }
        val app = appForAgents()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(
                    app, ui,
                    WorkspaceStateStore(tempPath("state")),
                    LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        assertFalse(ui.layout.sidebarCollapsed)
        onNodeWithTag("settings_hub").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.B) }
        }
        waitForIdle()
        assertFalse(ui.layout.sidebarCollapsed)
        assertTrue(ui.settingsOpen)
    }

    @Test fun opening_settings_closes_any_other_open_overlay() {
        val ui = WorkspaceUiState()
        ui.openUsage()
        ui.openSettings()
        assertFalse(ui.usageOpen)
        assertTrue(ui.settingsOpen)
        assertEquals(SettingsSection.Agents, ui.settingsSection)
    }

    @Test fun rail_switches_to_editor_lsp_section() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { openSettings(SettingsSection.Agents) }
        val app = appForAgents(
            statusJson = "[]",
        ).let { base ->
            // Need LSP endpoint too for section switch
            val engine = MockEngine { req ->
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
            }
            DesktopAppState(
                baseUrl = "ws://test:9898",
                token = "t",
                scope = TestScope(UnconfinedTestDispatcher()),
                connectOnInit = false,
                sendFrameOverride = { },
                apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
            )
        }
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(
                    app, ui,
                    WorkspaceStateStore(tempPath("state2")),
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
        onNodeWithText("TypeScript").assertIsDisplayed()
    }
}
