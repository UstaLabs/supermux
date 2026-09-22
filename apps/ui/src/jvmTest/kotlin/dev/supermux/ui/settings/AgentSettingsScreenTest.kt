package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
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
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared [AgentSettingsScreen] (cluster E2) — desktop's suite, moved by name.
 *
 * Covers the login and install state machines, mutation failure handling, load Error vs Empty,
 * poll cancel/timeout, Enter-to-submit and the real `HostStore` + mocked `BrokerApi` paths, plus
 * the Compact branch Android contributed (its own top bar, every section rendered, install offered
 * only for the agents the host reports as missing). Desktop's `AppShell` overlay wiring — Escape,
 * the rail, multi-host keying — stays in `:desktop` (`AgentSettingsHubTest`).
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

    /**
     * `setContent` with the theme, a [FakePlatform] on `LocalPlatform` (the OAuth / "Open sign-in"
     * paths call `openUrl`, and a real browser must never launch from a test) and an explicit
     * width class. Defaults are the DESKTOP shape, so every suite ported from `:desktop` asserts
     * exactly what it always asserted.
     */
    private fun ComposeUiTest.agentContent(
        platform: FakePlatform = FakePlatform(),
        pointer: Boolean = true,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        content: @Composable () -> Unit,
    ) = setPlatformContent(platform = platform, pointer = pointer, widthClass = widthClass) {
        content()
    }

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
        /** Compact suites flip this to false to prove the screen brings Android's own bar. */
        topBarShown: Boolean = true,
        onBack: () -> Unit = {},
    ) = @Composable {
        AgentSettingsScreen(
            actions = AgentSettingsActions(
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
            ),
            onBack = onBack,
            topBarShown = topBarShown,
        )
    }

    @Test fun agents_render_from_a_fake_status_list() = runComposeUiTest {
        agentContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
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
        agentContent {
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
        agentContent {
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
        agentContent {
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
        agentContent {
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
        agentContent {
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
        agentContent {
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
        agentContent {
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
        agentContent {
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
        agentContent {
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
        // Actually close + reopen composition (unmount/remount), not just first-mount resume.
        var mounted by mutableStateOf(true)
        agentContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                if (mounted) {
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
        }
        waitForIdle()
        // First open: resume path finds active broker login → no start POST.
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_login_open_url").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(0, startCalls.get(), "first open must not call start")
        val pollsAfterFirst = pollCalls.get()
        assertTrue(pollsAfterFirst >= 1)

        // Close settings (unmount) then reopen — still must not re-POST start.
        mounted = false
        waitForIdle()
        onNodeWithTag("agent_login_open_url").assertDoesNotExist()
        mounted = true
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_login_open_url").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(0, startCalls.get(), "reopen must not reissue start")
        assertTrue(pollCalls.get() > pollsAfterFirst, "reopen should poll again")
    }

    @Test fun login_null_poll_streak_times_out_and_start_works_again() = runComposeUiTest {
        val startCalls = AtomicInteger(0)
        agentContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "claude", installed = true, authed = false))
                    },
                    agentStartLogin = {
                        startCalls.incrementAndGet()
                        AgentLoginState(kind = "claude", phase = "starting")
                    },
                    // Always null after start → null-streak timeout (8 × 1.5s).
                    agentPollLogin = { null },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_login_start_claude").performClick()
        waitUntil(timeoutMillis = 20_000) {
            try {
                onNodeWithTag("agent_login_timeout_claude").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(1, startCalls.get())
        // Regression: after timeout, Start authorization must POST a fresh login.
        onNodeWithTag("agent_login_start_claude").performClick()
        waitUntil(timeoutMillis = 5_000) { startCalls.get() >= 2 }
        assertEquals(2, startCalls.get(), "stale phase must not block a new start after timeout")
    }

    @Test fun install_resume_on_reopen_does_not_reissue_start() = runComposeUiTest {
        val startCalls = AtomicInteger(0)
        val pollCalls = AtomicInteger(0)
        var mounted by mutableStateOf(true)
        agentContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                if (mounted) {
                    screen(
                        agentStatuses = {
                            listOf(AgentInstallStatus(kind = "cursor", installed = false, authed = false))
                        },
                        agentStartInstall = {
                            startCalls.incrementAndGet()
                            AgentInstallJob(state = "running", log = "…")
                        },
                        agentPollInstall = {
                            pollCalls.incrementAndGet()
                            AgentInstallJob(state = "running", log = "still installing")
                        },
                    )()
                }
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_install_running_cursor").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(0, startCalls.get(), "first open resume must not call install start")
        val pollsAfterFirst = pollCalls.get()
        mounted = false
        waitForIdle()
        mounted = true
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_install_running_cursor").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(0, startCalls.get(), "reopen must not reissue install start")
        assertTrue(pollCalls.get() > pollsAfterFirst)
    }

    // ── install state machine ───────────────────────────────────────────────────────────────────

    @Test fun install_idle_to_running_shows_progress_and_cancel() = runComposeUiTest {
        val started = AtomicReference(false)
        agentContent {
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

    @Test fun install_stop_watching_is_local_only_and_honest() = runComposeUiTest {
        val started = AtomicReference(false)
        val pollCount = AtomicInteger(0)
        agentContent {
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
        onNodeWithText("Stop watching").assertIsDisplayed()
        onNodeWithTag("agent_install_stop_hint_cursor").assertIsDisplayed()
        val pollsBefore = pollCount.get()
        onNodeWithTag("agent_install_cancel_cursor").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_install_stopped_watching_cursor").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Watch progress").assertIsDisplayed()
        onNodeWithTag("agent_install_running_cursor").assertDoesNotExist()
        // Give a beat — stop watching should stop the poll loop (polls may tick once more mid-cancel).
        waitForIdle()
        val pollsAfter = pollCount.get()
        assertTrue(pollsAfter <= pollsBefore + 2, "polls kept growing after stop: before=$pollsBefore after=$pollsAfter")
    }

    @Test fun install_running_to_done_reloads_statuses() = runComposeUiTest {
        val loadCount = AtomicInteger(0)
        val pollN = AtomicInteger(0)
        val started = AtomicReference(false)
        agentContent {
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
        agentContent {
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
        val saveCalls = AtomicInteger(0)
        agentContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "codex", installed = true, authed = false))
                    },
                    agentSaveSecret = { _, v ->
                        saveCalls.incrementAndGet()
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
        assertEquals(1, saveCalls.get())
        assertEquals("sk-test-keep-me", savedValue.get())
        // Password fields mask text — a *second* submit must fire with the same value
        // (proves input was kept; saveCalls was already 1 before this click).
        onNodeWithTag("agent_secret_save_codex").performClick()
        waitUntil(timeoutMillis = 5_000) { saveCalls.get() >= 2 }
        assertEquals(2, saveCalls.get())
        assertEquals("sk-test-keep-me", savedValue.get())
    }

    @Test fun secret_save_success_clears_input() = runComposeUiTest {
        val loadCount = AtomicInteger(0)
        agentContent {
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
        agentContent {
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
        agentContent {
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
        agentContent {
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
        agentContent {
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

    @Test fun enter_submits_opencode_zen_key() = runComposeUiTest {
        val saved = AtomicReference<String?>(null)
        agentContent {
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

    @Test fun enter_submits_provider_key() = runComposeUiTest {
        val saved = AtomicReference<String?>(null)
        agentContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "opencode", installed = true, authed = false))
                    },
                    openCodeProviders = {
                        listOf(
                            OpenCodeProvider(
                                id = "google",
                                methods = listOf(OpenCodeAuthMethod(type = "api", label = "API key", index = 0)),
                            ),
                        )
                    },
                    openCodeSetKey = { id, key ->
                        if (id == "google") saved.set(key)
                        true
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("opencode_provider_key_google").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("opencode_provider_key_google").performTextInput("prov-enter")
        onNodeWithTag("opencode_provider_key_google").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { saved.get() == "prov-enter" }
    }

    @Test fun enter_submits_oauth_code() = runComposeUiTest {
        val finished = AtomicReference<String?>(null)
        agentContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "opencode", installed = true, authed = false))
                    },
                    openCodeProviders = {
                        listOf(
                            OpenCodeProvider(
                                id = "google",
                                methods = listOf(OpenCodeAuthMethod(type = "oauth", label = "Login via browser", index = 0)),
                            ),
                        )
                    },
                    openCodeStartOAuth = { _, _ -> OpenCodeOAuthStart(url = "https://auth.example", method = "code") },
                    openCodeFinishOAuth = { _, _, code ->
                        finished.set(code)
                        true
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithText("Login via browser").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Login via browser").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("opencode_oauth_code_google").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("opencode_oauth_code_google").performTextInput("oauth-enter")
        onNodeWithTag("opencode_oauth_code_google").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { finished.get() == "oauth-enter" }
    }

    @Test fun enter_submits_login_code() = runComposeUiTest {
        val sent = AtomicReference<String?>(null)
        val started = AtomicReference(false)
        agentContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "claude", installed = true, authed = false))
                    },
                    agentStartLogin = {
                        started.set(true)
                        AgentLoginState(kind = "claude", phase = "starting")
                    },
                    agentPollLogin = {
                        if (!started.get()) null
                        else AgentLoginState(
                            kind = "claude",
                            phase = "awaiting_user",
                            url = "https://example.com/device",
                            needsCode = true,
                        )
                    },
                    agentSendCode = { _, code -> sent.set(code) },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_login_start_claude").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_login_code_field").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("agent_login_code_field").performTextInput("login-enter")
        onNodeWithTag("agent_login_code_field").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { sent.get() == "login-enter" }
    }

    // ── HostStore + mocked BrokerApi ──────────────────────────────────────────────────────

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
            deps = HostStoreDeps(
                httpFactory = { HttpClient(engine) },
                settings = FakeSettingsStore(),
                clock = FixedClock(),
            ),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = api,
        )
    }

    @Test fun desktop_app_state_agent_statuses_decodes_mock_broker() = runComposeUiTest {
        val app = appForAgents()
        var listed: List<AgentInstallStatus>? = emptyList()
        agentContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AgentSettingsScreen(
                    actions = AgentSettingsActions(
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
                    ),
                    topBarShown = true,
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
        agentContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AgentSettingsScreen(
                    actions = AgentSettingsActions(
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
                    ),
                    topBarShown = true,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { called }
        assertNull(result)
        onNodeWithTag("agent_settings_error").assertIsDisplayed()
    }

    @Test fun desktop_app_state_mutation_non2xx_is_failure() = runBlocking {
        // Real HTTP-level path through HostStore + BrokerApi (not injected booleans).
        // This is the gap that previously treated non-2xx as success.
        val app = appForAgents(
            statusJson = """[{"kind":"codex","installed":true,"authed":false}]""",
            mutationStatus = HttpStatusCode.InternalServerError,
        )
        assertFalse(app.saveAgentSecret("codex", "sk-should-fail"), "saveAgentSecret must fail on HTTP 500")
        assertFalse(app.setOpenCodeKey("opencode", "k"), "setOpenCodeKey must fail on HTTP 500")
        assertFalse(app.finishOpenCodeOAuth("google", 0, "code"), "finishOpenCodeOAuth must fail on HTTP 500")
    }

    @Test fun desktop_app_state_mutation_non2xx_keeps_secret_input_in_ui() = runComposeUiTest {
        // Idle login poll (not awaiting_user) so the secret field is shown — otherwise the
        // resume path would open LoginFlow and hide agent_secret_*.
        val app = appForAgents(
            statusJson = """[{"kind":"codex","installed":true,"authed":false}]""",
            loginJson = """{"kind":"codex","phase":"idle"}""",
            mutationStatus = HttpStatusCode.InternalServerError,
        )
        var secretOk: Boolean? = null
        agentContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AgentSettingsScreen(
                    actions = AgentSettingsActions(
                        agentStatuses = { app.agentStatuses() },
                        agentStartLogin = { app.startAgentLogin(it) },
                        agentPollLogin = { app.agentLoginState(it) },
                        agentSendCode = { k, c -> app.sendAgentLoginCode(k, c) },
                        agentCancelLogin = { app.cancelAgentLogin(it) },
                        agentSaveSecret = { k, v ->
                            secretOk = app.saveAgentSecret(k, v)
                            secretOk!!
                        },
                        agentStartInstall = { app.startAgentInstall(it) },
                        agentPollInstall = { app.agentInstallState(it) },
                        openCodeProviders = { app.openCodeProviders() },
                        openCodeSetKey = { id, key -> app.setOpenCodeKey(id, key) },
                        openCodeStartOAuth = { id, m -> app.startOpenCodeOAuth(id, m) },
                        openCodeFinishOAuth = { id, m, c -> app.finishOpenCodeOAuth(id, m, c) },
                    ),
                    topBarShown = true,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_secret_codex").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("agent_secret_codex").performTextInput("sk-should-fail")
        onNodeWithTag("agent_secret_save_codex").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_secret_error_codex").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(false, secretOk)
        // Re-submit proves the masked field retained the value after HTTP 500 (not cleared).
        onNodeWithTag("agent_secret_save_codex").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_secret_error_codex").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(false, secretOk)
    }

    @Test fun desktop_app_state_mutation_2xx_is_success() = runBlocking {
        val app = appForAgents(mutationStatus = HttpStatusCode.OK)
        assertTrue(app.saveAgentSecret("codex", "sk-ok"))
        assertTrue(app.setOpenCodeKey("opencode", "k"))
        assertTrue(app.finishOpenCodeOAuth("google", 0, "code"))
    }

    // ── Compact branch (Android's phone layout) ─────────────────────────────────────────────────

    @Test fun compact_renders_every_agent_section_the_host_reports() = runComposeUiTest {
        // A phone: the whole list scrolls in one LazyColumn, and every un-authed row starts
        // expanded, so each kind's section body must be reachable — install for the missing CLI,
        // the provider list for opencode, the secret field + link login for the rest.
        agentContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(
                            AgentInstallStatus(kind = "codex", installed = true, authed = false),
                            AgentInstallStatus(kind = "cursor", installed = false, authed = false),
                            AgentInstallStatus(kind = "opencode", installed = true, authed = false),
                        )
                    },
                    topBarShown = false,
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_row_codex").assertExists()
                true
            } catch (_: Throwable) {
                false
            }
        }
        // codex: installed but not authed → paste-a-key + authorize-via-link.
        onNodeWithTag("agent_secret_codex").assertExists()
        onNodeWithTag("agent_login_start_codex").assertExists()
        // cursor: the host says it is missing → the install section Android just gained.
        onNodeWithTag("agent_install_cursor").assertExists()
        onNodeWithTag("agent_install_start_cursor").assertExists()
        // opencode: the provider sub-list, with the synthetic Zen key row.
        onNodeWithTag("opencode_providers_section").assertExists()
        onNodeWithTag("opencode_zen_key_row").assertExists()
    }

    @Test fun compact_grok_offers_the_link_login_and_no_key_field() = runComposeUiTest {
        // grok authenticates only via `grok login --device-auth`; there is no key to paste. Its
        // own render because a phone-height LazyColumn does not compose a fourth expanded row.
        agentContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(AgentInstallStatus(kind = "grok", installed = true, authed = false))
                    },
                    topBarShown = false,
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_login_start_grok").assertExists()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("agent_secret_grok").assertDoesNotExist()
    }

    @Test fun compact_brings_its_own_top_bar_when_the_hub_did_not() = runComposeUiTest {
        var backs = 0
        agentContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(topBarShown = false, onBack = { backs++ })()
            }
        }
        waitForIdle()
        onNodeWithTag("agent_settings_back").assertIsDisplayed()
        onNodeWithText("Agents").assertIsDisplayed()
        onNodeWithTag("agent_settings_back").performClick()
        assertEquals(1, backs)
    }

    /**
     * The touch bump is real, not just a token swap: the same row is TALLER without a pointer.
     *
     * Keyed on `LocalPointerAvailable`, never `LocalInputMode` — a phone with a Bluetooth keyboard
     * is still a thumb. (Folded in from the E2 review, which noted the padding had no assertion.)
     */
    /**
     * Hit targets key on `LocalPointerAvailable`. Measured as row PITCH, not node height: the
     * padding sits inside the tagged row, so two consecutive rows' offsets are what actually moves
     * (the E3 reviewer's note — the old height probe only passed because the two rows' CONTENT
     * differed).
     */
    @Test fun touch_agent_rows_are_taller_than_pointer_rows() {
        fun rowPitch(pointer: Boolean): Float {
            var pitch = 0f
            runComposeUiTest {
                agentContent(pointer = pointer, widthClass = WindowWidthClass.Compact) {
                    SupermuxTheme(appearance = AppearanceMode.DARK) { screen(topBarShown = true)() }
                }
                waitForIdle()
                waitUntil(timeoutMillis = 5_000) {
                    try {
                        onNodeWithTag("agent_row_codex").assertExists()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                val first = onNodeWithTag("agent_row_claude").fetchSemanticsNode().positionInRoot.y
                val second = onNodeWithTag("agent_row_codex").fetchSemanticsNode().positionInRoot.y
                pitch = second - first
            }
            return pitch
        }
        val touch = rowPitch(pointer = false)
        val mouse = rowPitch(pointer = true)
        assertTrue(touch > mouse, "touch pitch $touch should exceed pointer pitch $mouse")
    }

    @Test fun compact_leaves_the_chrome_alone_when_the_hub_painted_it() = runComposeUiTest {
        agentContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { screen(topBarShown = true)() }
        }
        waitForIdle()
        onNodeWithTag("agent_settings_screen").assertExists()
        onNodeWithTag("agent_settings_back").assertDoesNotExist()
    }

    @Test fun a_wide_window_never_paints_the_screens_own_top_bar() = runComposeUiTest {
        // The E1 reviewer's double-chrome bug: on a tablet the hub owns the header, so the
        // section must not add one even though `topBarShown` is false there.
        agentContent(pointer = false, widthClass = WindowWidthClass.Expanded) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { screen(topBarShown = false)() }
        }
        waitForIdle()
        onNodeWithTag("agent_settings_screen").assertExists()
        onNodeWithTag("agent_settings_back").assertDoesNotExist()
    }

    @Test fun install_is_offered_only_for_the_agents_the_host_reports_as_missing() = runComposeUiTest {
        agentContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    agentStatuses = {
                        listOf(
                            AgentInstallStatus(kind = "codex", installed = true, authed = false),
                            AgentInstallStatus(kind = "cursor", installed = false, authed = false),
                        )
                    },
                    topBarShown = false,
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("agent_install_cursor").assertExists()
                true
            } catch (_: Throwable) {
                false
            }
        }
        // codex IS installed — no install button, and the secret field takes the slot instead.
        onNodeWithTag("agent_install_codex").assertDoesNotExist()
        onNodeWithTag("agent_install_start_codex").assertDoesNotExist()
        onNodeWithTag("agent_secret_codex").assertExists()
        // cursor is not installed — no key field until it is there to authenticate.
        onNodeWithTag("agent_secret_cursor").assertDoesNotExist()
    }
}
