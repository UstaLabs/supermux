// Desktop-parity Task 3: System / maintenance — broker update status + restart.
package dev.supermux.desktop.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.host.FleetState
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.desktop.workspace.SettingsSection
import dev.supermux.desktop.workspace.WorkspaceRoot
import dev.supermux.desktop.workspace.WorkspaceStateStore
import dev.supermux.desktop.workspace.WorkspaceUiState
import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.net.BrokerApi
import dev.supermux.net.RunUpdateResult
import dev.supermux.net.UpdateStatus
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Desktop-parity Task 3: [SystemSettingsScreen] load / recheck / update / restart + hub wiring.
 *
 * Seeds [BrokerApi] via libs.ktor.client.mock; covers status display, last-checked text,
 * recheck via checkUpdate, update-broker path, restart confirm (kills connection), multi-host
 * isolation, and DesktopAppState GET/POST paths. Distinct from app self-update (AppUpdate).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SystemSettingsScreenTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { p -> runCatching { Files.deleteIfExists(p) } }
        tempFiles.clear()
    }

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("system_settings_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    private fun sampleStatus(
        current: String = "1.2.3",
        commit: String = "abcdef0123456789",
        latest: String? = null,
        updateAvailable: Boolean = false,
        notesUrl: String? = "https://github.com/supermux/supermux/releases",
        mode: String = "binary",
        state: String = "idle",
        lastChecked: Double? = System.currentTimeMillis() - 5 * 60_000.0,
        lastError: String? = null,
        disabled: Boolean = false,
    ) = UpdateStatus(
        current = current,
        commit = commit,
        latest = latest,
        updateAvailable = updateAvailable,
        notesUrl = notesUrl,
        mode = mode,
        state = state,
        lastChecked = lastChecked,
        lastError = lastError,
        disabled = disabled,
    )

    private fun screen(
        updateStatus: suspend () -> UpdateStatus? = { sampleStatus() },
        checkUpdate: suspend () -> UpdateStatus? = { sampleStatus() },
        runUpdate: suspend () -> RunUpdateResult? = { null },
        restartBroker: () -> Unit = {},
    ) = @Composable {
        SystemSettingsScreen(
            updateStatus = updateStatus,
            checkUpdate = checkUpdate,
            runUpdate = runUpdate,
            restartBroker = restartBroker,
        )
    }

    // ── pure helpers ────────────────────────────────────────────────────────────────────────────

    @Test fun last_checked_text_formats_relative() {
        val now = 1_000_000_000_000L
        assertNull(lastCheckedText(null, now))
        assertNull(lastCheckedText(0.0, now))
        assertEquals("Checked <1m ago", lastCheckedText((now - 30_000).toDouble(), now))
        assertEquals("Checked 5m ago", lastCheckedText((now - 5 * 60_000).toDouble(), now))
        assertEquals("Checked 2h ago", lastCheckedText((now - 2 * 3_600_000).toDouble(), now))
        assertEquals("Checked 3d ago", lastCheckedText((now - 3 * 86_400_000).toDouble(), now))
    }

    @Test fun state_label_maps_known_states() {
        assertEquals("Checking…", stateLabel("checking"))
        assertEquals("Downloading…", stateLabel("downloading"))
        assertEquals("Swapping…", stateLabel("swapping"))
        assertEquals("Restart required", stateLabel("restart-required"))
        assertEquals("Failed", stateLabel("failed"))
        assertEquals("idle", stateLabel("idle"))
    }

    @Test fun is_running_state_covers_in_flight() {
        assertTrue(isRunningState("checking"))
        assertTrue(isRunningState("downloading"))
        assertTrue(isRunningState("swapping"))
        assertFalse(isRunningState("idle"))
        assertFalse(isRunningState("failed"))
        assertFalse(isRunningState("restart-required"))
    }

    // ── screen load states ──────────────────────────────────────────────────────────────────────

    @Test fun renders_broker_version_commit_and_last_checked() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(updateStatus = {
                    sampleStatus(current = "2.0.1", commit = "deadbeefcafe")
                })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("system_broker_version").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("system_settings_screen").assertIsDisplayed()
        onNodeWithText("supermux 2.0.1").assertIsDisplayed()
        onNodeWithText("deadbeef").assertIsDisplayed()
        onNodeWithTag("system_last_checked").assertIsDisplayed()
        onNodeWithTag("system_up_to_date").assertIsDisplayed()
        onNodeWithTag("system_release_notes").assertIsDisplayed()
        onNodeWithTag("system_broker_vs_app_caption").assertIsDisplayed()
        onNodeWithTag("system_restart_broker").assertIsDisplayed()
        onNodeWithTag("system_recheck").assertIsDisplayed()
    }

    @Test fun update_available_shows_update_broker_for_binary() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(updateStatus = {
                    sampleStatus(
                        updateAvailable = true,
                        latest = "9.9.9",
                        mode = "binary",
                    )
                })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("system_update_available").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Update available: 9.9.9").assertIsDisplayed()
        onNodeWithTag("system_update_broker").assertIsDisplayed()
        onNodeWithText("Update broker").assertIsDisplayed()
    }

    @Test fun source_mode_hides_update_broker_button() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(updateStatus = {
                    sampleStatus(updateAvailable = true, latest = "9.9.9", mode = "source")
                })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("system_broker_version").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("system_update_broker").assertDoesNotExist()
    }

    @Test fun load_failure_shows_error_with_retry() = runComposeUiTest {
        val loads = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(updateStatus = {
                    loads.incrementAndGet()
                    null
                })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("system_settings_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Couldn't load update status.").assertIsDisplayed()
        onNodeWithTag("system_settings_retry").assertIsDisplayed()
        assertTrue(loads.get() >= 1)
    }

    @Test fun retry_after_load_failure_recovers() = runComposeUiTest {
        val loads = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(updateStatus = {
                    val n = loads.incrementAndGet()
                    if (n == 1) null else sampleStatus(current = "3.0.0")
                })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("system_settings_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("system_settings_retry").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithText("supermux 3.0.0").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(loads.get() >= 2)
    }

    @Test fun recheck_calls_check_update_not_status() = runComposeUiTest {
        val statusCalls = AtomicInteger(0)
        val checkCalls = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    updateStatus = {
                        statusCalls.incrementAndGet()
                        sampleStatus(current = "1.0.0")
                    },
                    checkUpdate = {
                        checkCalls.incrementAndGet()
                        sampleStatus(current = "1.0.1", updateAvailable = true, latest = "1.0.1")
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithText("supermux 1.0.0").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(1, statusCalls.get())
        assertEquals(0, checkCalls.get())
        onNodeWithTag("system_recheck").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithText("supermux 1.0.1").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(1, checkCalls.get())
        onNodeWithTag("system_update_available").assertIsDisplayed()
    }

    @Test fun run_update_started_polls_status_until_settled() = runComposeUiTest {
        val runCalls = AtomicInteger(0)
        val polls = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    updateStatus = {
                        val n = polls.incrementAndGet()
                        if (n == 1) {
                            sampleStatus(updateAvailable = true, latest = "2.0.0")
                        } else if (n < 3) {
                            sampleStatus(state = "downloading", updateAvailable = true, latest = "2.0.0")
                        } else {
                            sampleStatus(
                                current = "2.0.0",
                                state = "restart-required",
                                updateAvailable = false,
                            )
                        }
                    },
                    runUpdate = {
                        runCalls.incrementAndGet()
                        RunUpdateResult(started = true)
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("system_update_broker").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("system_update_broker").performClick()
        waitUntil(timeoutMillis = 15_000) {
            try {
                onNodeWithText("supermux 2.0.0").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(1, runCalls.get())
        assertTrue(polls.get() >= 3)
    }

    @Test fun run_update_instruction_surfaces_error() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    updateStatus = {
                        sampleStatus(updateAvailable = true, latest = "2.0.0", mode = "binary")
                    },
                    runUpdate = {
                        RunUpdateResult(
                            started = false,
                            error = "self-update not available",
                            instruction = "Source install — update via git.",
                        )
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("system_update_broker").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("system_update_broker").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("system_run_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Source install — update via git.").assertIsDisplayed()
    }

    // ── restart confirm ─────────────────────────────────────────────────────────────────────────

    @Test fun restart_requires_confirm_and_states_connection_kill() = runComposeUiTest {
        val restarted = AtomicBoolean(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(restartBroker = { restarted.set(true) })()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("system_restart_broker").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("system_restart_warning").assertIsDisplayed()
        onNodeWithTag("system_restart_broker").performClick()
        waitForIdle()
        onNodeWithTag("system_restart_dialog").assertIsDisplayed()
        onNodeWithText("Restart the broker?").assertIsDisplayed()
        onNodeWithText(
            "This kills your connection to the broker on the active host. " +
                "Sessions will reconnect automatically once it is back.",
        ).assertIsDisplayed()
        // Cancel does not restart.
        onNodeWithTag("system_restart_cancel").performClick()
        waitForIdle()
        assertFalse(restarted.get())
        // Confirm fires restartBroker.
        onNodeWithTag("system_restart_broker").performClick()
        waitForIdle()
        onNodeWithTag("system_restart_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { restarted.get() }
        assertTrue(restarted.get())
    }

    // ── DesktopAppState + BrokerApi (ktor mock) ─────────────────────────────────────────────────

    private fun appForSystem(
        statusJson: String? = """{"current":"1.2.3","commit":"abc12345","latest":null,"updateAvailable":false,"notesUrl":"https://n","mode":"binary","state":"idle","lastChecked":1717200000000,"lastError":null,"disabled":false}""",
        checkJson: String? = """{"current":"1.2.3","commit":"abc12345","latest":"1.3.0","updateAvailable":true,"notesUrl":"https://n","mode":"binary","state":"idle","lastChecked":1717200001000,"lastError":null,"disabled":false}""",
        runJson: String = """{"started":true}""",
        runStatus: HttpStatusCode = HttpStatusCode.Accepted,
        restartStatus: HttpStatusCode = HttpStatusCode.OK,
        statusCalls: AtomicInteger? = null,
        checkCalls: AtomicInteger? = null,
        runCalls: AtomicInteger? = null,
        restartCalls: AtomicInteger? = null,
    ): DesktopAppState {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            when {
                path == "/api/update/status" && req.method == HttpMethod.Get -> {
                    statusCalls?.incrementAndGet()
                    if (statusJson == null) {
                        respond("{}", HttpStatusCode.InternalServerError, jsonHeaders)
                    } else {
                        respond(statusJson, HttpStatusCode.OK, jsonHeaders)
                    }
                }
                path == "/api/update/check" && req.method == HttpMethod.Post -> {
                    checkCalls?.incrementAndGet()
                    if (checkJson == null) {
                        respond("{}", HttpStatusCode.InternalServerError, jsonHeaders)
                    } else {
                        respond(checkJson, HttpStatusCode.OK, jsonHeaders)
                    }
                }
                path == "/api/update/run" && req.method == HttpMethod.Post -> {
                    runCalls?.incrementAndGet()
                    respond(runJson, runStatus, jsonHeaders)
                }
                path == "/system/restart" && req.method == HttpMethod.Post -> {
                    restartCalls?.incrementAndGet()
                    respond("{}", restartStatus, jsonHeaders)
                }
                else ->
                    respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
    }

    @Test fun desktop_app_state_update_status_decodes_mock_broker() = runComposeUiTest {
        val app = appForSystem()
        var loaded: UpdateStatus? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SystemSettingsScreen(
                    updateStatus = {
                        loaded = app.updateStatus()
                        loaded
                    },
                    checkUpdate = { app.checkUpdate() },
                    runUpdate = { app.runUpdate() },
                    restartBroker = { app.restartBroker() },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { loaded != null }
        assertEquals("1.2.3", loaded!!.current)
        assertEquals("abc12345", loaded!!.commit)
        assertEquals("binary", loaded!!.mode)
        onNodeWithText("supermux 1.2.3").assertIsDisplayed()
        onNodeWithText("abc12345".take(8)).assertIsDisplayed()
    }

    @Test fun desktop_app_state_update_status_null_on_broker_error() = runComposeUiTest {
        val app = appForSystem(statusJson = null)
        var result: UpdateStatus? = UpdateStatus(current = "sentinel")
        var called = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SystemSettingsScreen(
                    updateStatus = {
                        result = app.updateStatus()
                        called = true
                        result
                    },
                    checkUpdate = { null },
                    runUpdate = { null },
                    restartBroker = {},
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { called }
        assertNull(result)
        onNodeWithTag("system_settings_error").assertIsDisplayed()
    }

    @Test fun desktop_app_state_check_update_posts_and_returns_status() = runBlocking {
        val checks = AtomicInteger(0)
        val app = appForSystem(checkCalls = checks)
        val s = app.checkUpdate()
        assertEquals("1.3.0", s!!.latest)
        assertTrue(s.updateAvailable)
        assertEquals(1, checks.get())
    }

    @Test fun desktop_app_state_run_update_started() = runBlocking {
        val runs = AtomicInteger(0)
        val app = appForSystem(runCalls = runs)
        val r = app.runUpdate()
        assertTrue(r!!.started)
        assertEquals(1, runs.get())
    }

    @Test fun desktop_app_state_run_update_instruction_on_400() = runBlocking {
        val app = appForSystem(
            runJson = """{"error":"self-update not available","instruction":"Use git pull."}""",
            runStatus = HttpStatusCode.BadRequest,
        )
        val r = app.runUpdate()
        assertFalse(r!!.started)
        assertEquals("Use git pull.", r.instruction)
    }

    @Test fun desktop_app_state_restart_broker_posts_without_throw() = runBlocking {
        val restarts = AtomicInteger(0)
        val app = appForSystem(restartCalls = restarts)
        app.restartBroker()
        // Fire-and-forget on stateScope (UnconfinedTestDispatcher runs eagerly).
        waitUntilRestart(restarts, timeoutMs = 2_000)
        assertEquals(1, restarts.get())
    }

    private fun waitUntilRestart(counter: AtomicInteger, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (counter.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    // ── Settings hub overlay wiring ─────────────────────────────────────────────────────────────

    @Test fun settings_hub_opens_system_section_and_loads() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { openSettings(SettingsSection.System) }
        val app = appForSystem()
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
        val ui = WorkspaceUiState().apply { openSettings(SettingsSection.Agents) }
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
        val app = DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(
                    app, ui,
                    WorkspaceStateStore(tempPath("rail-state")),
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
        val fleet = FleetState(
            store = store,
            scope = scope,
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
                DesktopAppState(
                    baseUrl = url,
                    token = token,
                    scope = scope,
                    connectOnInit = false,
                    sendFrameOverride = { },
                    apiOverride = BrokerApi(url, token, HttpClient(engine)),
                    onConnectionChange = onConn,
                )
            },
        )
        val ui = WorkspaceUiState().apply { openSettings(SettingsSection.System) }
        val primary = fleet.appForRecord("h1")!!
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceRoot(
                    primary, ui,
                    WorkspaceStateStore(tempPath("mh-state")),
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
        val ui = WorkspaceUiState()
        ui.openSettings(SettingsSection.System)
        assertTrue(ui.settingsOpen)
        assertEquals(SettingsSection.System, ui.settingsSection)
        assertTrue(ui.overlayOpen)
        assertFalse(ui.launcherOpen)
        assertFalse(ui.appUpdateOpen)
    }

    @Test fun open_settings_system_closes_app_update_overlay() {
        val ui = WorkspaceUiState()
        ui.openAppUpdate()
        assertTrue(ui.appUpdateOpen)
        ui.openSettings(SettingsSection.System)
        assertTrue(ui.settingsOpen)
        assertFalse(ui.appUpdateOpen)
    }
}
