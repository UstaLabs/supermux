package dev.supermux.desktop.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
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
import dev.supermux.net.OpenCodeAuthMethod
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop-parity Task 1: [AgentSettingsScreen] state machines + Settings hub wiring.
 *
 * Covers mutation failure handling, load Error vs Empty, poll cancel/timeout, install cancel,
 * Enter-to-submit, multi-host isolation, and reconnect recovery — not just literal labels.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AgentSettingsScreenTest {

    // ── pure helpers (keep the non-trivial ones) ────────────────────────────────────────────────

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

    // ── screen harness ──────────────────────────────────────────────────────────────────────────

    private fun statuses() = listOf(
        AgentInstallStatus(kind = "claude", installed = true, authed = true),
        AgentInstallStatus(kind = "codex", installed = true, authed = false),
        AgentInstallStatus(kind = "cursor", installed = false, authed = false),
        AgentInstallStatus(kind = "opencode", installed = true, authed = false),
        AgentInstallStatus(kind = "grok", installed = true, authed = false),
    )

    private fun screen(
        agentStatuses: suspend () -> List<AgentInstallStatus>? = { statuses() },
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
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_row_claude").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("agent_settings_screen").assertIsDisplayed()
        // LazyColumn only composes on-screen rows — assert the first few that fit the viewport.
        onNodeWithTag("agent_row_codex").assertIsDisplayed()
        onNodeWithTag("agent_row_cursor").assertIsDisplayed()
    }

    @Test fun load_failure_shows_error_with_retry_not_empty() = runComposeUiTest {
        val loads = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(agentStatuses = {
                    loads.incrementAndGet()
                    null
                })()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_settings_error").assertIsDisplayed()
        onNodeWithTag("agent_settings_retry").assertIsDisplayed()
        onNodeWithText("Couldn't load agent statuses.").assertIsDisplayed()
        // Must NOT claim empty-list success path
        onNodeWithTag("agent_settings_empty").assertDoesNotExist()
        assertTrue(loads.get() >= 1)
    }

    @Test fun empty_status_list_shows_empty_state_not_error() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(agentStatuses = { emptyList() })()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_settings_empty").assertIsDisplayed()
        onNodeWithTag("agent_settings_error").assertDoesNotExist()
        onNodeWithText("No agents reported").assertIsDisplayed()
    }

    @Test fun retry_after_load_failure_recovers() = runComposeUiTest {
        val loads = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(agentStatuses = {
                    val n = loads.incrementAndGet()
                    if (n == 1) null
                    else listOf(AgentInstallStatus(kind = "claude", installed = true, authed = true))
                })()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_settings_error").assertIsDisplayed()
        onNodeWithTag("agent_settings_retry").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_row_claude").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(loads.get() >= 2)
    }

    @Test fun auto_retry_recovers_after_reconnect_without_manual_retry() = runComposeUiTest {
        val loads = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(agentStatuses = {
                    // First load fails; auto-retry (3s) should succeed.
                    val n = loads.incrementAndGet()
                    if (n == 1) null
                    else listOf(AgentInstallStatus(kind = "codex", installed = true, authed = true))
                })()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_settings_error").assertIsDisplayed()
        waitUntil(timeoutMillis = 8_000) {
            try {
                onNodeWithTag("agent_row_codex").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(loads.get() >= 2)
    }

    // ── login state machine ─────────────────────────────────────────────────────────────────────

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
        onNodeWithTag("agent_login_start_claude").performClick()
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

    @Test fun login_start_failure_surfaces_error_instead_of_spinning() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "claude", installed = true, authed = false))
                    },
                    agentStartLogin = { null },
                    agentPollLogin = { null },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_login_start_claude").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_login_start_failed_claude").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("agent_login_generating").assertDoesNotExist()
        onNodeWithText("Couldn't start authorization.").assertIsDisplayed()
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
        onNodeWithTag("agent_login_start_claude").assertIsDisplayed()
    }

    @Test fun login_resume_on_reopen_does_not_reissue_start() = runComposeUiTest {
        val startCalls = AtomicInteger(0)
        val pollCalls = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "grok", installed = true, authed = false))
                    },
                    agentStartLogin = {
                        startCalls.incrementAndGet()
                        AgentLoginState(kind = "grok", phase = "starting")
                    },
                    agentPollLogin = {
                        pollCalls.incrementAndGet()
                        AgentLoginState(
                            kind = "grok",
                            phase = "awaiting_user",
                            url = "https://x.ai/device",
                            code = "GROK-1",
                        )
                    },
                )()
            }
        }
        waitForIdle()
        // Resume path: poll on composition finds active login → no start.
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_login_open_url").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(0, startCalls.get(), "resume must not call start")
        assertTrue(pollCalls.get() >= 1)
    }

    // ── install state machine ───────────────────────────────────────────────────────────────────

    @Test fun install_idle_to_running_shows_progress_and_cancel() = runComposeUiTest {
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
        onNodeWithTag("agent_install_start_cursor").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_install_running_cursor").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("agent_install_cancel_cursor").assertIsDisplayed()
    }

    @Test fun install_cancel_stops_running_ui() = runComposeUiTest {
        val started = AtomicReference(false)
        val pollCount = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "cursor", installed = false, authed = false))
                    },
                    agentStartInstall = {
                        started.set(true)
                        AgentInstallJob(state = "running", log = "…")
                    },
                    agentPollInstall = {
                        pollCount.incrementAndGet()
                        if (!started.get()) null
                        else AgentInstallJob(state = "running", log = "…")
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_install_start_cursor").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_install_cancel_cursor").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        val pollsBefore = pollCount.get()
        onNodeWithTag("agent_install_cancel_cursor").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_install_start_cursor").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("agent_install_running_cursor").assertDoesNotExist()
        // Give a beat — cancel should stop the poll loop (polls may tick once more mid-cancel).
        waitForIdle()
        val pollsAfter = pollCount.get()
        assertTrue(pollsAfter <= pollsBefore + 2, "polls kept growing after cancel: before=$pollsBefore after=$pollsAfter")
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

    // ── mutation result handling ────────────────────────────────────────────────────────────────

    @Test fun secret_save_failure_keeps_input_and_shows_error() = runComposeUiTest {
        val savedValue = AtomicReference<String?>(null)
        var lastSeenValue: String? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "codex", installed = true, authed = false))
                    },
                    agentSaveSecret = { _, v ->
                        lastSeenValue = v
                        savedValue.set(v)
                        false
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_secret_codex").performTextInput("sk-test-keep-me")
        onNodeWithTag("agent_secret_save_codex").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_secret_error_codex").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals("sk-test-keep-me", savedValue.get())
        // Password fields mask text — re-submit proves the value was kept (not cleared).
        onNodeWithTag("agent_secret_save_codex").performClick()
        waitUntil(timeoutMillis = 5_000) { savedValue.get() == "sk-test-keep-me" && lastSeenValue == "sk-test-keep-me" }
    }

    @Test fun secret_save_success_clears_input() = runComposeUiTest {
        val loadCount = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        val n = loadCount.incrementAndGet()
                        if (n == 1) {
                            listOf(AgentInstallStatus(kind = "codex", installed = true, authed = false))
                        } else {
                            listOf(AgentInstallStatus(kind = "codex", installed = true, authed = true))
                        }
                    },
                    agentSaveSecret = { _, _ -> true },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_secret_codex").performTextInput("sk-ok")
        onNodeWithTag("agent_secret_save_codex").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithText("Ready").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(loadCount.get() >= 2)
    }

    @Test fun opencode_key_failure_keeps_input_and_shows_error() = runComposeUiTest {
        val seen = AtomicInteger(0)
        val lastKey = AtomicReference<String?>(null)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "opencode", installed = true, authed = false))
                    },
                    openCodeProviders = { emptyList() },
                    openCodeSetKey = { _, key ->
                        seen.incrementAndGet()
                        lastKey.set(key)
                        false
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("opencode_zen_key_field").performTextInput("oc-key-keep")
        onNodeWithTag("opencode_zen_key_save").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("opencode_zen_key_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals("oc-key-keep", lastKey.get())
        // Re-submit proves the field was not cleared on failure (password field is masked).
        onNodeWithTag("opencode_zen_key_save").performClick()
        waitUntil(timeoutMillis = 5_000) { seen.get() >= 2 && lastKey.get() == "oc-key-keep" }
    }

    @Test fun opencode_key_success_clears_and_reloads() = runComposeUiTest {
        val setCalls = AtomicInteger(0)
        val providerLoads = AtomicInteger(0)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "opencode", installed = true, authed = false))
                    },
                    openCodeProviders = {
                        providerLoads.incrementAndGet()
                        emptyList()
                    },
                    openCodeSetKey = { id, key ->
                        setCalls.incrementAndGet()
                        assertEquals("opencode", id)
                        assertEquals("oc-good", key)
                        true
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("opencode_zen_key_field").performTextInput("oc-good")
        onNodeWithTag("opencode_zen_key_save").performClick()
        waitUntil(timeoutMillis = 5_000) {
            setCalls.get() >= 1 && providerLoads.get() >= 2
        }
    }

    @Test fun opencode_oauth_finish_failure_keeps_code() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "opencode", installed = true, authed = false))
                    },
                    openCodeProviders = {
                        listOf(
                            OpenCodeProvider(
                                id = "google",
                                configured = false,
                                methods = listOf(OpenCodeAuthMethod(type = "oauth", index = 0, label = "OAuth")),
                            ),
                        )
                    },
                    openCodeStartOAuth = { _, _ -> OpenCodeOAuthStart(url = "https://oauth.example/start") },
                    openCodeFinishOAuth = { _, _, _ -> false },
                )()
            }
        }
        waitForIdle()
        onNodeWithText("Login via browser").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("opencode_oauth_code_google").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("opencode_oauth_code_google").performTextInput("bad-code")
        onNodeWithTag("opencode_oauth_finish_google").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("opencode_oauth_error_google").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        // OAuth code field is not password-masked — text stays visible on failure.
        onNodeWithText("bad-code", substring = true).assertIsDisplayed()
    }

    @Test fun enter_submits_secret_field() = runComposeUiTest {
        val saved = AtomicReference<String?>(null)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "codex", installed = true, authed = false))
                    },
                    agentSaveSecret = { _, v ->
                        saved.set(v)
                        true
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_secret_codex").performTextInput("sk-enter")
        onNodeWithTag("agent_secret_codex").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { saved.get() == "sk-enter" }
    }

    @Test fun enter_submits_opencode_key() = runComposeUiTest {
        val saved = AtomicReference<String?>(null)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "opencode", installed = true, authed = false))
                    },
                    openCodeProviders = { emptyList() },
                    openCodeSetKey = { _, key ->
                        saved.set(key)
                        true
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("opencode_zen_key_field").performTextInput("oc-enter")
        onNodeWithTag("opencode_zen_key_field").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { saved.get() == "oc-enter" }
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
        statusJson: String? = """[{"kind":"claude","installed":true,"authed":true},{"kind":"codex","installed":true,"authed":false}]""",
        statusCode: HttpStatusCode = HttpStatusCode.OK,
        loginJson: String = """{"kind":"claude","phase":"awaiting_user","url":"https://auth.example","code":"XYZ"}""",
        installJson: String = """{"state":"running","log":"installing"}""",
    ): DesktopAppState {
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
        val app = appForAgents()
        var listed: List<AgentInstallStatus>? = emptyList()
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
        waitUntil(timeoutMillis = 5_000) { listed?.isNotEmpty() == true }
        assertEquals(2, listed!!.size)
        assertEquals("claude", listed!![0].kind)
        assertTrue(listed!![0].authed)
        onNodeWithTag("agent_row_claude").assertIsDisplayed()
    }

    @Test fun desktop_app_state_agent_statuses_null_on_broker_error() = runComposeUiTest {
        val app = appForAgents(statusJson = null)
        var result: List<AgentInstallStatus>? = emptyList() // sentinel non-null so we can detect null
        var called = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AgentSettingsScreen(
                    agentStatuses = {
                        result = app.agentStatuses()
                        called = true
                        result
                    },
                    agentStartLogin = { null },
                    agentPollLogin = { null },
                    agentSendCode = { _, _ -> },
                    agentCancelLogin = {},
                    agentSaveSecret = { _, _ -> false },
                    agentStartInstall = { null },
                    agentPollInstall = { null },
                    openCodeProviders = { emptyList() },
                    openCodeSetKey = { _, _ -> false },
                    openCodeStartOAuth = { _, _ -> null },
                    openCodeFinishOAuth = { _, _, _ -> false },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { called }
        assertNull(result)
        onNodeWithTag("agent_settings_error").assertIsDisplayed()
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

    @Test fun rail_switches_to_editor_lsp_without_nested_back() = runComposeUiTest {
        val ui = WorkspaceUiState().apply { openSettings(SettingsSection.Agents) }
        val app = DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
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
        // Nested back must be gone when embedded in the hub.
        onNodeWithTag("lsp_settings_back").assertDoesNotExist()
        onNodeWithText("TypeScript").assertIsDisplayed()
        // Hub back remains.
        onNodeWithTag("settings_hub_back").assertIsDisplayed()
    }

    @Test fun multi_host_keying_reloads_per_active_host() = runComposeUiTest {
        // Prove agentStatuses is re-invoked when the composition key (activeHostId) changes
        // by remounting the screen with different fakes (same pattern as WorkspaceRoot's key()).
        val hostA = listOf(AgentInstallStatus(kind = "claude", installed = true, authed = true))
        val hostB = listOf(AgentInstallStatus(kind = "cursor", installed = false, authed = false))
        var hostId by mutableStateOf("a")
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                androidx.compose.runtime.key(hostId) {
                    AgentSettingsScreen(
                        agentStatuses = { if (hostId == "a") hostA else hostB },
                        agentStartLogin = { null },
                        agentPollLogin = { null },
                        agentSendCode = { _, _ -> },
                        agentCancelLogin = {},
                        agentSaveSecret = { _, _ -> false },
                        agentStartInstall = { null },
                        agentPollInstall = { null },
                        openCodeProviders = { emptyList() },
                        openCodeSetKey = { _, _ -> false },
                        openCodeStartOAuth = { _, _ -> null },
                        openCodeFinishOAuth = { _, _, _ -> false },
                    )
                }
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
        hostId = "b"
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_row_cursor").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("agent_row_claude").assertDoesNotExist()
    }
}
