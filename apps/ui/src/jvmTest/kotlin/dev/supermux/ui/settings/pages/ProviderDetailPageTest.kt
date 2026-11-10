package dev.supermux.ui.settings.pages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountRemovalPlanDto
import dev.supermux.net.AccountUnavailableDto
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.net.OpenCodeAuthMethod
import dev.supermux.net.OpenCodeOAuthStart
import dev.supermux.net.OpenCodeProvider
import dev.supermux.state.AccountResult
import dev.supermux.ui.accounts.AccountsActions
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.SettingsMachines
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.http.HttpStatusCode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A provider's page (#97 spec §2.2, §10.3, §11, §12): Your login (the machine's own sign-in, today's login flow, "Use
 * a token instead"), the added logins (#106's list, with usage and Remove), Add a login, OpenCode's key and model
 * providers, and Install. Ported from `AgentSettingsScreenTest`, where each case expanded a row.
 */
@OptIn(ExperimentalTestApi::class)
class ProviderDetailPageTest {

    // ── the brief's cases ───────────────────────────────────────────────────────────────────────

    @Test fun use_a_token_instead_saves_the_machine_login_token_and_adds_no_login() = runComposeUiTest {
        var saved: Pair<String, String>? = null
        var added = 0
        setProviderPage(
            agent = "claude",
            agents = AgentSettingsActions(
                agentStatuses = { claudeInstalledSignedOut() },
                agentSaveSecret = { k, v -> saved = k to v; true },
            ),
            accounts = AccountsActions(addAccount = { _, _, _, _ -> added++; AccountResult.Failed(null, null, null) }),
        )
        awaitText("Use a token instead")
        onNodeWithText("Use a token instead").performClick()
        awaitTag("provider_secret")
        onNodeWithText("A token for this machine's own Claude Code login.").assertExists()
        // A sheet may show the helper command; the page itself may not.
        onNodeWithText("claude setup-token").assertExists()
        onNodeWithTag("provider_secret").performTextInput("sk-ant-oat-1")
        onNodeWithTag("provider_secret_save").performClick()
        waitUntil(timeoutMillis = 5_000) { saved != null }
        assertEquals("claude" to "sk-ant-oat-1", saved)
        assertEquals(0, added)
    }

    @Test fun your_login_cannot_be_removed() = runComposeUiTest {
        setProviderPage(agent = "claude", accounts = AccountsActions(accounts = flowOf(listOf(systemAccount("claude")))))
        awaitTag("provider_login_claude:system")
        onNodeWithText("Your login").assertExists()
        onNodeWithTag("provider_login_claude:system").performClick()
        waitForIdle()
        onNodeWithTag("provider_login_claude:system_remove").assertDoesNotExist()
    }

    @Test fun opencode_has_no_own_login_and_no_add_a_login_but_keeps_its_key_and_model_providers() = runComposeUiTest {
        setProviderPage(agent = "opencode", accounts = AccountsActions(accounts = flowOf(listOf(systemAccount("opencode")))))
        awaitTag("opencode_providers_section")
        onNodeWithText("Your login").assertDoesNotExist()
        onNodeWithText("Add a login").assertDoesNotExist()
        onNodeWithText("MODEL PROVIDERS IN OPENCODE").assertExists()
        onNodeWithTag("opencode_zen_key_row").assertExists()
    }

    @Test fun grok_offers_no_token_instead() = runComposeUiTest {
        setProviderPage(agent = "grok")
        awaitTag("agent_login_start_grok")
        onNodeWithText("Use a token instead").assertDoesNotExist()
    }

    @Test fun an_added_login_shows_its_usage() = runComposeUiTest {
        setProviderPage(agent = "claude", accounts = AccountsActions(accounts = flowOf(listOf(systemAccount("claude"), workAccountWithUsage()))))
        awaitTag("provider_login_usage_work")
        onNodeWithText("62%", substring = true).assertExists()
        onNodeWithText("18%", substring = true).assertExists()
    }

    @Test fun showAddLogin_false_hides_add_a_login() = runComposeUiTest {
        val agents = AgentSettingsActions(agentStatuses = { allInstalled() })
        setPlatformContent(platform = FakePlatform(), widthClass = WindowWidthClass.Expanded, pointer = true) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                val providers = rememberProvidersState(agents)
                ProviderDetailPage("codex", providers, agents, AccountsActions(), showAddLogin = false)
            }
        }
        awaitTag("provider_login_codex:system")
        onNodeWithText("Add a login").assertDoesNotExist()
    }

    // ── Your login ──────────────────────────────────────────────────────────────────────────────

    @Test fun your_login_is_there_without_any_accounts_and_says_whether_it_is_signed_in() = runComposeUiTest {
        setProviderPage(agent = "codex")
        awaitTag("provider_login_codex:system")
        onNodeWithText("Your login").assertExists()
        onNodeWithText("Not signed in").assertExists()
        onNodeWithText("Sign in").assertExists()
        onNodeWithText("Add a login").assertExists()
    }

    @Test fun your_login_shows_its_email_when_signed_in() = runComposeUiTest {
        setProviderPage(
            agent = "claude",
            agents = AgentSettingsActions(agentStatuses = { listOf(AgentInstallStatus(kind = "claude", installed = true, authed = true)) }),
            accounts = AccountsActions(accounts = flowOf(listOf(systemAccount("claude", "me@x.dev")))),
        )
        awaitText("me@x.dev")
        onNodeWithText("Not signed in").assertDoesNotExist()
    }

    @Test fun an_expired_sign_in_offers_sign_in_again() = runComposeUiTest {
        setProviderPage(
            agent = "cursor",
            agents = AgentSettingsActions(agentStatuses = { listOf(AgentInstallStatus(kind = "cursor", installed = true, authed = false, signInExpired = true)) }),
        )
        awaitTag("agent_login_start_cursor")
        onNodeWithText("Sign-in expired").assertExists()
        onNodeWithText("Sign in again").assertExists()
    }

    @Test fun wide_back_returns_to_the_list() = runComposeUiTest {
        var backs = 0
        setProviderPage(agent = "codex", onBack = { backs++ })
        awaitTag("settings_page_back")
        onNodeWithText("Providers & accounts").assertExists()
        onNodeWithText("Codex").assertExists()
        onNodeWithTag("settings_page_back").performClick()
        assertEquals(1, backs)
    }

    @Test fun an_offline_machine_shows_its_offline_line() = runComposeUiTest {
        val off = KitMachine("m2", "studio-mini", local = false, online = false, status = "Offline")
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(off), "m2") {}) {
                    ProviderPageHarness("claude", AgentSettingsActions(agentStatuses = { error("must not load") }), AccountsActions())
                }
            }
        }
        awaitTag("settings_offline")
        onNodeWithText("Your login").assertDoesNotExist()
    }

    // ── login state machine ─────────────────────────────────────────────────────────────────────

    @Test fun login_idle_to_pending_shows_awaiting_user_url_and_cancel() = runComposeUiTest {
        val startCalls = AtomicInteger(0)
        val started = AtomicBoolean(false)
        setProviderPage(
            agent = "claude",
            agents = AgentSettingsActions(
                agentStatuses = { claudeInstalledSignedOut() },
                agentStartLogin = { startCalls.incrementAndGet(); started.set(true); AgentLoginState(kind = "claude", phase = "starting") },
                agentPollLogin = {
                    if (!started.get()) null
                    else AgentLoginState(kind = "claude", phase = "awaiting_user", url = "https://example.com/device", code = "ABCD-1234")
                },
            ),
        )
        awaitTag("agent_login_start_claude")
        onNodeWithTag("agent_login_start_claude").performClick()
        awaitTag("agent_login_open_url")
        assertTrue(startCalls.get() >= 1)
        onNodeWithTag("agent_login_device_code").assertExists()
        onNodeWithText("ABCD-1234", substring = true).assertExists()
        onNodeWithTag("agent_login_cancel").assertExists()
    }

    /** The sign-in link's Copy goes through the app's Platform, like its Open does. */
    @Test fun login_copy_puts_the_sign_in_link_on_the_platform_clipboard() = runComposeUiTest {
        val platform = FakePlatform()
        val started = AtomicBoolean(false)
        setProviderPage(
            agent = "claude",
            platform = platform,
            agents = AgentSettingsActions(
                agentStatuses = { claudeInstalledSignedOut() },
                agentStartLogin = { started.set(true); AgentLoginState(kind = "claude", phase = "starting") },
                agentPollLogin = { if (!started.get()) null else AgentLoginState(kind = "claude", phase = "awaiting_user", url = "https://example.com/device") },
            ),
        )
        awaitTag("agent_login_start_claude")
        onNodeWithTag("agent_login_start_claude").performClick()
        awaitTag("agent_login_copy_url")
        onNodeWithTag("agent_login_copy_url").performClick()
        waitForIdle()
        assertEquals(listOf("https://example.com/device"), platform.copied)
        onNodeWithTag("agent_login_open_url").performClick()
        waitForIdle()
        assertEquals(listOf("https://example.com/device"), platform.openedUrls)
    }

    @Test fun login_pending_to_done_reloads_statuses() = runComposeUiTest {
        val polls = AtomicInteger(0)
        val loads = AtomicInteger(0)
        val started = AtomicBoolean(false)
        setProviderPage(
            agent = "claude",
            agents = AgentSettingsActions(
                agentStatuses = { listOf(AgentInstallStatus(kind = "claude", installed = true, authed = loads.incrementAndGet() > 1)) },
                agentStartLogin = { started.set(true); AgentLoginState(kind = "claude", phase = "starting") },
                agentPollLogin = {
                    if (!started.get()) null
                    else if (polls.incrementAndGet() < 2) AgentLoginState(kind = "claude", phase = "awaiting_user", url = "https://x")
                    else AgentLoginState(kind = "claude", phase = "success")
                },
            ),
        )
        awaitTag("agent_login_start_claude")
        onNodeWithTag("agent_login_start_claude").performClick()
        awaitTag("agent_login_success_claude", timeout = 8_000)
        waitUntil(timeoutMillis = 5_000) { loads.get() >= 2 }
        awaitText("This machine's login")
        onNodeWithText("Not signed in").assertDoesNotExist()
    }

    @Test fun login_pending_to_error_shows_failure_message_and_tries_again() = runComposeUiTest {
        val started = AtomicInteger(0)
        setProviderPage(
            agent = "codex",
            agents = AgentSettingsActions(
                agentStatuses = { listOf(AgentInstallStatus(kind = "codex", installed = true, authed = false)) },
                agentStartLogin = { started.incrementAndGet(); AgentLoginState(kind = "codex", phase = "starting") },
                agentPollLogin = { if (started.get() == 0) null else AgentLoginState(kind = "codex", phase = "failed", error = "device denied") },
            ),
        )
        awaitTag("agent_login_start_codex")
        onNodeWithTag("agent_login_start_codex").performClick()
        awaitTag("agent_login_failed_codex")
        onNodeWithText("Sign-in failed: device denied").assertExists()
        onNodeWithTag("agent_login_retry").performClick()
        waitUntil(timeoutMillis = 5_000) { started.get() >= 2 }
    }

    @Test fun login_start_failure_surfaces_error_instead_of_spinning() = runComposeUiTest {
        setProviderPage(agent = "claude", agents = AgentSettingsActions(agentStatuses = { claudeInstalledSignedOut() }))
        awaitTag("agent_login_start_claude")
        onNodeWithTag("agent_login_start_claude").performClick()
        awaitTag("agent_login_start_failed_claude")
        onNodeWithTag("agent_login_generating").assertDoesNotExist()
        onNodeWithText("Couldn't start the sign-in.").assertExists()
    }

    @Test fun login_cancel_calls_broker_cancel() = runComposeUiTest {
        val cancelled = AtomicReference<String?>(null)
        val started = AtomicBoolean(false)
        setProviderPage(
            agent = "claude",
            agents = AgentSettingsActions(
                agentStatuses = { claudeInstalledSignedOut() },
                agentStartLogin = { started.set(true); AgentLoginState(kind = "claude", phase = "awaiting_user", url = "https://x") },
                agentPollLogin = { if (!started.get()) null else AgentLoginState(kind = "claude", phase = "awaiting_user", url = "https://x") },
                agentCancelLogin = { cancelled.set(it) },
            ),
        )
        awaitTag("agent_login_start_claude")
        onNodeWithTag("agent_login_start_claude").performClick()
        awaitTag("agent_login_cancel")
        onNodeWithTag("agent_login_cancel").performClick()
        waitUntil(timeoutMillis = 5_000) { cancelled.get() != null }
        assertEquals("claude", cancelled.get())
        awaitTag("agent_login_start_claude")
    }

    @Test fun login_resume_on_reopen_does_not_reissue_start() = runComposeUiTest {
        val startCalls = AtomicInteger(0)
        val pollCalls = AtomicInteger(0)
        var mounted by mutableStateOf(true)
        setProviderPage(
            agent = "grok",
            agents = AgentSettingsActions(
                agentStatuses = { listOf(AgentInstallStatus(kind = "grok", installed = true, authed = false)) },
                agentStartLogin = { startCalls.incrementAndGet(); AgentLoginState(kind = "grok", phase = "starting") },
                agentPollLogin = {
                    pollCalls.incrementAndGet()
                    AgentLoginState(kind = "grok", phase = "awaiting_user", url = "https://x.ai/device", code = "GROK-1")
                },
            ),
            mounted = { mounted },
        )
        awaitTag("agent_login_open_url")
        assertEquals(0, startCalls.get(), "first open must not call start")
        val pollsAfterFirst = pollCalls.get()
        assertTrue(pollsAfterFirst >= 1)
        mounted = false
        waitForIdle()
        onNodeWithTag("agent_login_open_url").assertDoesNotExist()
        mounted = true
        awaitTag("agent_login_open_url")
        assertEquals(0, startCalls.get(), "reopen must not reissue start")
        assertTrue(pollCalls.get() > pollsAfterFirst, "reopen should poll again")
    }

    @Test fun login_null_poll_streak_times_out_and_start_works_again() = runComposeUiTest {
        val startCalls = AtomicInteger(0)
        setProviderPage(
            agent = "claude",
            agents = AgentSettingsActions(
                agentStatuses = { claudeInstalledSignedOut() },
                agentStartLogin = { startCalls.incrementAndGet(); AgentLoginState(kind = "claude", phase = "starting") },
                // Always null after start: the null-streak timeout (8 × 1.5 s).
                agentPollLogin = { null },
            ),
        )
        awaitTag("agent_login_start_claude")
        onNodeWithTag("agent_login_start_claude").performClick()
        awaitTag("agent_login_timeout_claude", timeout = 20_000)
        assertEquals(1, startCalls.get())
        // After a timeout, Sign in must start a fresh login.
        onNodeWithTag("agent_login_start_claude").performClick()
        waitUntil(timeoutMillis = 5_000) { startCalls.get() >= 2 }
        assertEquals(2, startCalls.get(), "stale phase must not block a new start after timeout")
    }

    @Test fun enter_submits_login_code() = runComposeUiTest {
        val sent = AtomicReference<String?>(null)
        val started = AtomicBoolean(false)
        setProviderPage(
            agent = "claude",
            agents = AgentSettingsActions(
                agentStatuses = { claudeInstalledSignedOut() },
                agentStartLogin = { started.set(true); AgentLoginState(kind = "claude", phase = "starting") },
                agentPollLogin = {
                    if (!started.get()) null
                    else AgentLoginState(kind = "claude", phase = "awaiting_user", url = "https://example.com/device", needsCode = true)
                },
                agentSendCode = { _, code -> sent.set(code) },
            ),
        )
        awaitTag("agent_login_start_claude")
        onNodeWithTag("agent_login_start_claude").performClick()
        awaitTag("agent_login_code_field")
        onNodeWithTag("agent_login_code_field").performTextInput("login-enter")
        onNodeWithTag("agent_login_code_field").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { sent.get() == "login-enter" }
    }

    // ── install ─────────────────────────────────────────────────────────────────────────────────

    private fun cursorMissing() = listOf(AgentInstallStatus(kind = "cursor", installed = false, authed = false))

    @Test fun install_is_offered_only_for_a_missing_provider() = runComposeUiTest {
        setProviderPage(agent = "cursor", agents = AgentSettingsActions(agentStatuses = { cursorMissing() }))
        awaitTag("agent_install_cursor")
        onNodeWithTag("agent_install_start_cursor").assertExists()
        // Not installed: nothing to sign in to yet.
        onNodeWithText("Use a token instead").assertDoesNotExist()
        onNodeWithTag("agent_login_start_cursor").assertDoesNotExist()
    }

    @Test fun an_installed_provider_offers_no_install() = runComposeUiTest {
        setProviderPage(agent = "codex")
        awaitTag("agent_login_start_codex")
        onNodeWithTag("agent_install_codex").assertDoesNotExist()
        onNodeWithTag("agent_install_start_codex").assertDoesNotExist()
    }

    @Test fun install_resume_on_reopen_does_not_reissue_start() = runComposeUiTest {
        val startCalls = AtomicInteger(0)
        val pollCalls = AtomicInteger(0)
        var mounted by mutableStateOf(true)
        setProviderPage(
            agent = "cursor",
            agents = AgentSettingsActions(
                agentStatuses = { cursorMissing() },
                agentStartInstall = { startCalls.incrementAndGet(); AgentInstallJob(state = "running", log = "…") },
                agentPollInstall = { pollCalls.incrementAndGet(); AgentInstallJob(state = "running", log = "still installing") },
            ),
            mounted = { mounted },
        )
        awaitTag("agent_install_running_cursor")
        assertEquals(0, startCalls.get(), "first open resume must not call install start")
        val pollsAfterFirst = pollCalls.get()
        mounted = false
        waitForIdle()
        mounted = true
        awaitTag("agent_install_running_cursor")
        assertEquals(0, startCalls.get(), "reopen must not reissue install start")
        assertTrue(pollCalls.get() > pollsAfterFirst)
    }

    @Test fun install_idle_to_running_shows_progress_and_stop_watching() = runComposeUiTest {
        val started = AtomicBoolean(false)
        setProviderPage(
            agent = "cursor",
            agents = AgentSettingsActions(
                agentStatuses = { cursorMissing() },
                agentStartInstall = { started.set(true); AgentInstallJob(state = "running", log = "fetching…") },
                agentPollInstall = { if (!started.get()) null else AgentInstallJob(state = "running", log = "still going") },
            ),
        )
        awaitTag("agent_install_start_cursor")
        onNodeWithTag("agent_install_start_cursor").performClick()
        awaitTag("agent_install_running_cursor")
        onNodeWithTag("agent_install_cancel_cursor").assertExists()
    }

    @Test fun install_stop_watching_is_local_only_and_honest() = runComposeUiTest {
        val started = AtomicBoolean(false)
        val pollCount = AtomicInteger(0)
        setProviderPage(
            agent = "cursor",
            agents = AgentSettingsActions(
                agentStatuses = { cursorMissing() },
                agentStartInstall = { started.set(true); AgentInstallJob(state = "running", log = "…") },
                agentPollInstall = { pollCount.incrementAndGet(); if (!started.get()) null else AgentInstallJob(state = "running", log = "…") },
            ),
        )
        awaitTag("agent_install_start_cursor")
        onNodeWithTag("agent_install_start_cursor").performClick()
        awaitTag("agent_install_cancel_cursor")
        onNodeWithText("Stop watching").assertExists()
        onNodeWithTag("agent_install_stop_hint_cursor").assertExists()
        val pollsBefore = pollCount.get()
        onNodeWithTag("agent_install_cancel_cursor").performClick()
        awaitTag("agent_install_stopped_watching_cursor")
        onNodeWithText("Watch progress").assertExists()
        onNodeWithTag("agent_install_running_cursor").assertDoesNotExist()
        waitForIdle()
        val pollsAfter = pollCount.get()
        assertTrue(pollsAfter <= pollsBefore + 2, "polls kept growing after stop: before=$pollsBefore after=$pollsAfter")
    }

    @Test fun install_running_to_failed_shows_error() = runComposeUiTest {
        val started = AtomicBoolean(false)
        setProviderPage(
            agent = "cursor",
            agents = AgentSettingsActions(
                agentStatuses = { cursorMissing() },
                agentStartInstall = { started.set(true); AgentInstallJob(state = "running") },
                agentPollInstall = { if (!started.get()) null else AgentInstallJob(state = "failed", log = "npm ERR") },
            ),
        )
        awaitTag("agent_install_start_cursor")
        onNodeWithTag("agent_install_start_cursor").performClick()
        awaitTag("agent_install_error_cursor")
        onNodeWithText("Installation failed.").assertExists()
        onNodeWithText("Retry installation").assertExists()
    }

    @Test fun install_failed_shows_exit_code_reason_and_the_last_20_log_lines() = runComposeUiTest {
        val started = AtomicBoolean(false)
        val log = (1..30).joinToString("\n") { "line $it" }
        setProviderPage(
            agent = "grok",
            agents = AgentSettingsActions(
                agentStatuses = { listOf(AgentInstallStatus(kind = "grok", installed = false, authed = false)) },
                agentStartInstall = { started.set(true); AgentInstallJob(state = "running") },
                agentPollInstall = {
                    if (!started.get()) null
                    else AgentInstallJob(state = "failed", log = log, exitCode = 1, error = "the Grok download is only 4096 bytes; refusing it")
                },
            ),
        )
        awaitTag("agent_install_start_grok")
        onNodeWithTag("agent_install_start_grok").performClick()
        awaitTag("agent_install_error_grok")
        onNodeWithText("Installation failed (exit code 1). The Grok download is only 4096 bytes; refusing it").assertExists()
        onNodeWithTag("agent_install_log_grok").assertTextEquals((11..30).joinToString("\n") { "line $it" })
    }

    // ── Use a token instead ─────────────────────────────────────────────────────────────────────

    private fun codexSignedOut() = listOf(AgentInstallStatus(kind = "codex", installed = true, authed = false))

    @Test fun secret_save_failure_keeps_input_and_shows_error() = runComposeUiTest {
        val saved = AtomicReference<String?>(null)
        val saves = AtomicInteger(0)
        setProviderPage(
            agent = "codex",
            agents = AgentSettingsActions(agentStatuses = { codexSignedOut() }, agentSaveSecret = { _, v -> saves.incrementAndGet(); saved.set(v); false }),
        )
        awaitText("Use a token instead")
        onNodeWithText("Use a token instead").performClick()
        awaitTag("provider_secret")
        onNodeWithText("Paste your OpenAI API key.").assertExists()
        onNodeWithTag("provider_secret").performTextInput("sk-test-keep-me")
        onNodeWithTag("provider_secret_save").performClick()
        awaitTag("provider_secret_error")
        assertEquals(1, saves.get())
        assertEquals("sk-test-keep-me", saved.get())
        // The field is masked: a second save with the same value proves it was kept.
        onNodeWithTag("provider_secret_save").performClick()
        waitUntil(timeoutMillis = 5_000) { saves.get() >= 2 }
        assertEquals("sk-test-keep-me", saved.get())
    }

    @Test fun secret_save_success_closes_the_sheet_and_reloads() = runComposeUiTest {
        val loads = AtomicInteger(0)
        setProviderPage(
            agent = "codex",
            agents = AgentSettingsActions(
                agentStatuses = { listOf(AgentInstallStatus(kind = "codex", installed = true, authed = loads.incrementAndGet() > 1)) },
                agentSaveSecret = { _, _ -> true },
            ),
        )
        awaitText("Use a token instead")
        onNodeWithText("Use a token instead").performClick()
        awaitTag("provider_secret")
        onNodeWithTag("provider_secret").performTextInput("sk-ok")
        onNodeWithTag("provider_secret_save").performClick()
        waitUntil(timeoutMillis = 5_000) { loads.get() >= 2 }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("provider_secret").assertDoesNotExist() }.isSuccess }
        awaitText("This machine's login")
    }

    @Test fun enter_submits_secret_field() = runComposeUiTest {
        val saved = AtomicReference<String?>(null)
        setProviderPage(agent = "codex", agents = AgentSettingsActions(agentStatuses = { codexSignedOut() }, agentSaveSecret = { _, v -> saved.set(v); true }))
        awaitText("Use a token instead")
        onNodeWithText("Use a token instead").performClick()
        awaitTag("provider_secret")
        onNodeWithTag("provider_secret").performTextInput("sk-enter")
        onNodeWithTag("provider_secret").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { saved.get() == "sk-enter" }
    }

    @Test fun antigravity_offers_the_link_login_and_a_gemini_key() = runComposeUiTest {
        setProviderPage(agent = "antigravity")
        awaitTag("agent_login_start_antigravity")
        onNodeWithText("Use a token instead").performClick()
        awaitTag("provider_secret")
        onNodeWithText("Paste your Gemini API key.").assertExists()
    }

    @Test fun desktop_app_state_mutation_non2xx_keeps_secret_input_in_ui() = runComposeUiTest {
        // An idle login (not awaiting_user), so nothing resumes over Your login.
        val app = appForAgents(
            statusJson = """[{"kind":"codex","installed":true,"authed":false}]""",
            loginJson = """{"kind":"codex","phase":"idle"}""",
            mutationStatus = HttpStatusCode.InternalServerError,
        )
        var secretOk: Boolean? = null
        setProviderPage(agent = "codex", agents = hostActions(app) { secretOk = it })
        awaitText("Use a token instead")
        onNodeWithText("Use a token instead").performClick()
        awaitTag("provider_secret")
        onNodeWithTag("provider_secret").performTextInput("sk-should-fail")
        onNodeWithTag("provider_secret_save").performClick()
        awaitTag("provider_secret_error")
        assertEquals(false, secretOk)
        onNodeWithTag("provider_secret_save").performClick()
        awaitTag("provider_secret_error")
        assertEquals(false, secretOk)
    }

    // ── OpenCode ────────────────────────────────────────────────────────────────────────────────

    private fun opencodeInstalled() = listOf(AgentInstallStatus(kind = "opencode", installed = true, authed = false))

    /** OpenCode's Console (Go) sign-in: the same device-link flow as Codex/Grok, above the model providers (#109). */
    @Test fun opencode_sign_in_runs_the_device_link_flow() = runComposeUiTest {
        val started = AtomicBoolean(false)
        setProviderPage(
            agent = "opencode",
            agents = AgentSettingsActions(
                agentStatuses = { opencodeInstalled() },
                agentStartLogin = { kind -> assertEquals("opencode", kind); started.set(true); AgentLoginState(kind = "opencode", phase = "starting") },
                agentPollLogin = {
                    if (!started.get()) null
                    else AgentLoginState(kind = "opencode", phase = "awaiting_user", url = "https://opencode.ai/console/device?user_code=QLCP-HTDB", code = "QLCP-HTDB")
                },
            ),
        )
        awaitTag("opencode_providers_section")
        onNodeWithText("Sign in with OpenCode").assertExists()
        onNodeWithTag("agent_login_start_opencode").performClick()
        awaitTag("agent_login_device_code")
        onNodeWithTag("agent_login_open_url").assertExists()
    }

    @Test fun opencode_key_failure_keeps_input_and_shows_error() = runComposeUiTest {
        val seen = AtomicInteger(0)
        val lastKey = AtomicReference<String?>(null)
        setProviderPage(
            agent = "opencode",
            agents = AgentSettingsActions(
                agentStatuses = { opencodeInstalled() },
                openCodeSetKey = { _, key -> seen.incrementAndGet(); lastKey.set(key); false },
            ),
        )
        awaitTag("opencode_zen_key_field")
        onNodeWithTag("opencode_zen_key_field").performTextInput("oc-key-keep")
        onNodeWithTag("opencode_zen_key_save").performClick()
        awaitTag("opencode_zen_key_error")
        assertEquals("oc-key-keep", lastKey.get())
        onNodeWithTag("opencode_zen_key_save").performClick()
        waitUntil(timeoutMillis = 5_000) { seen.get() >= 2 && lastKey.get() == "oc-key-keep" }
    }

    @Test fun opencode_key_success_clears_and_reloads() = runComposeUiTest {
        val setCalls = AtomicInteger(0)
        val providerLoads = AtomicInteger(0)
        setProviderPage(
            agent = "opencode",
            agents = AgentSettingsActions(
                agentStatuses = { opencodeInstalled() },
                openCodeProviders = { providerLoads.incrementAndGet(); emptyList() },
                openCodeSetKey = { id, key -> setCalls.incrementAndGet(); assertEquals("opencode", id); assertEquals("oc-good", key); true },
            ),
        )
        awaitTag("opencode_zen_key_field")
        onNodeWithTag("opencode_zen_key_field").performTextInput("oc-good")
        onNodeWithTag("opencode_zen_key_save").performClick()
        waitUntil(timeoutMillis = 5_000) { setCalls.get() >= 1 && providerLoads.get() >= 2 }
    }

    private fun google(type: String, label: String) = listOf(
        OpenCodeProvider(id = "google", configured = false, methods = listOf(OpenCodeAuthMethod(type = type, index = 0, label = label))),
    )

    @Test fun opencode_oauth_finish_failure_keeps_code() = runComposeUiTest {
        setProviderPage(
            agent = "opencode",
            agents = AgentSettingsActions(
                agentStatuses = { opencodeInstalled() },
                openCodeProviders = { google("oauth", "OAuth") },
                openCodeStartOAuth = { _, _ -> OpenCodeOAuthStart(url = "https://oauth.example/start") },
                openCodeFinishOAuth = { _, _, _ -> false },
            ),
        )
        awaitText("Login via browser")
        onNodeWithText("Login via browser").performClick()
        awaitTag("opencode_oauth_code_google")
        onNodeWithTag("opencode_oauth_code_google").performTextInput("bad-code")
        onNodeWithTag("opencode_oauth_finish_google").performClick()
        awaitTag("opencode_oauth_error_google")
        // The code field is not masked: the text stays visible on failure.
        onNodeWithText("bad-code", substring = true).assertExists()
    }

    @Test fun enter_submits_opencode_zen_key() = runComposeUiTest {
        val saved = AtomicReference<String?>(null)
        setProviderPage(agent = "opencode", agents = AgentSettingsActions(agentStatuses = { opencodeInstalled() }, openCodeSetKey = { _, key -> saved.set(key); true }))
        awaitTag("opencode_zen_key_field")
        onNodeWithTag("opencode_zen_key_field").performTextInput("oc-enter")
        onNodeWithTag("opencode_zen_key_field").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { saved.get() == "oc-enter" }
    }

    @Test fun enter_submits_provider_key() = runComposeUiTest {
        val saved = AtomicReference<String?>(null)
        setProviderPage(
            agent = "opencode",
            agents = AgentSettingsActions(
                agentStatuses = { opencodeInstalled() },
                openCodeProviders = { google("api", "API key") },
                openCodeSetKey = { id, key -> if (id == "google") saved.set(key); true },
            ),
        )
        awaitTag("opencode_provider_key_google")
        onNodeWithTag("opencode_provider_key_google").performTextInput("prov-enter")
        onNodeWithTag("opencode_provider_key_google").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { saved.get() == "prov-enter" }
    }

    @Test fun enter_submits_oauth_code() = runComposeUiTest {
        val finished = AtomicReference<String?>(null)
        setProviderPage(
            agent = "opencode",
            agents = AgentSettingsActions(
                agentStatuses = { opencodeInstalled() },
                openCodeProviders = { google("oauth", "Login via browser") },
                openCodeStartOAuth = { _, _ -> OpenCodeOAuthStart(url = "https://auth.example", method = "code") },
                openCodeFinishOAuth = { _, _, code -> finished.set(code); true },
            ),
        )
        awaitText("Login via browser")
        onNodeWithText("Login via browser").performClick()
        awaitTag("opencode_oauth_code_google")
        onNodeWithTag("opencode_oauth_code_google").performTextInput("oauth-enter")
        onNodeWithTag("opencode_oauth_code_google").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { finished.get() == "oauth-enter" }
    }

    // ── added logins (#106's list on the page) ──────────────────────────────────────────────────

    private val ci = AccountDto(id = "claude-ci", agent = "claude", method = "token", label = "CI token", isolated = true, createdAt = "2026-10-02T00:00:00.000Z")

    @Test fun added_logins_follow_your_login_with_their_method_and_kept_apart_ones_say_so() = runComposeUiTest {
        setProviderPage(agent = "claude", accounts = AccountsActions(accounts = flowOf(listOf(ci, workAccountWithUsage(), systemAccount("claude", "me@x.dev")))))
        awaitTag("provider_login_work")
        onNodeWithTag("provider_login_claude-ci").assertExists()
        assertTrue(onNodeWithTag("provider_login_work").getBoundsInRoot().top >= onNodeWithTag("provider_login_claude:system").getBoundsInRoot().bottom)
        onNodeWithText("Subscription · me@acme.dev").assertExists()
        onNodeWithText("Token").assertExists()
        onNodeWithText("Doesn't switch automatically").assertExists()
    }

    @Test fun removing_a_subscription_can_delete_its_sign_in_folder() = runComposeUiTest {
        val removed = mutableListOf<Pair<String, Boolean>>()
        setProviderPage(
            agent = "claude",
            accounts = AccountsActions(
                accounts = flowOf(listOf(systemAccount("claude"), workAccountWithUsage())),
                removalPlan = { AccountRemovalPlanDto(it, 2, "claude:system", "System login") },
                removeAccount = { id, home -> removed += id to home; AccountResult.Ok(Unit) },
            ),
        )
        awaitTag("provider_login_work")
        onNodeWithTag("provider_login_work").performClick()
        awaitTag("provider_login_work_remove")
        onNodeWithTag("provider_login_work_remove").performClick()
        awaitText("Its 2 chats move to System login.", substring = true)
        onNodeWithText("Also delete its sign-in folder").assertExists()
        onNodeWithTag("account-remove-delete-home").performClick()
        onNodeWithTag("provider_login_remove_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { removed.isNotEmpty() }
        assertEquals(listOf("work" to true), removed)
    }

    @Test fun an_expired_added_login_signs_in_again_as_that_login() = runComposeUiTest {
        val expired = AccountUnavailableDto("expired", "2026-11-07T20:44:00.000Z", code = "account_expired")
        val started = mutableListOf<Pair<String, String?>>()
        setProviderPage(
            agent = "claude",
            accounts = AccountsActions(
                accounts = flowOf(listOf(systemAccount("claude"), workAccountWithUsage().copy(unavailable = expired))),
                startLogin = { agent, email -> started += agent to email; AccountResult.Failed(null, null, null) },
            ),
        )
        awaitTag("provider_login_work_sign_in")
        onNodeWithText("Sign-in expired").assertExists()
        onNodeWithTag("provider_login_work_sign_in").performClick()
        waitUntil(timeoutMillis = 5_000) { started.isNotEmpty() }
        assertEquals(listOf<Pair<String, String?>>("claude" to "me@acme.dev"), started)
    }

    @Test fun add_a_login_opens_the_sheet_with_todays_options() = runComposeUiTest {
        setProviderPage(agent = "codex", accounts = AccountsActions(accounts = flowOf(listOf(systemAccount("codex")))))
        awaitTag("provider_login_add")
        onNodeWithTag("provider_login_add").performClick()
        awaitTag("account-add-sheet")
        onNodeWithTag("account-add-option:login").assertExists()
        onNodeWithTag("account-add-option:api_key").assertExists()
        onNodeWithTag("account-add-option:token").assertDoesNotExist()
    }
}
