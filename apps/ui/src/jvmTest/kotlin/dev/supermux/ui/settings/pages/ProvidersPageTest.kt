package dev.supermux.ui.settings.pages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.net.AccountDto
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.state.AccountResult
import dev.supermux.ui.accounts.AccountsActions
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.AgentSettingsScreen
import dev.supermux.ui.settings.installFailureMessage
import dev.supermux.ui.settings.installLogTail
import dev.supermux.ui.settings.isActiveLoginPhase
import dev.supermux.ui.settings.kit.KitAnchorKey
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.SettingsMachines
import dev.supermux.ui.settings.normalizeInstallState
import dev.supermux.ui.settings.statusLabel
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.http.HttpStatusCode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Providers & accounts, the list (#97 spec §2.2, §10.3, §11, §12): one item per provider the machine reports, its
 * status and trailing state, Install from the list, Switch accounts at a limit and Resume after a limit reset, and
 * the wizard's `AgentSettingsScreen`, which opens a provider in place. Ported from `AgentSettingsScreenTest`.
 */
@OptIn(ExperimentalTestApi::class)
class ProvidersPageTest {

    private fun anchored(key: String) = SemanticsMatcher.expectValue(KitAnchorKey, key)

    // ── pure helpers ────────────────────────────────────────────────────────────────────────────

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

    @Test fun status_label_names_an_expired_sign_in_before_anything_else() {
        assertEquals("Sign-in expired — sign in again", statusLabel(AgentInstallStatus(kind = "cursor", installed = true, authed = false, signInExpired = true)))
        assertEquals("Authenticated", statusLabel(AgentInstallStatus(kind = "cursor", installed = true, authed = true)))
    }

    @Test fun install_log_tail_and_failure_message() {
        assertEquals("b\nc", installLogTail("a\nb\nc\n\n", lines = 2))
        assertEquals("", installLogTail(""))
        assertEquals("Installation failed.", installFailureMessage(AgentInstallJob(state = "failed")))
        assertEquals(
            "Installation failed. Grok can't be installed on this OS.",
            installFailureMessage(AgentInstallJob(state = "failed", error = "Grok can't be installed on this OS.")),
        )
        assertEquals("Installation failed (exit code 3).", installFailureMessage(AgentInstallJob(state = "failed", exitCode = 3)))
    }

    // ── the list ────────────────────────────────────────────────────────────────────────────────

    private fun statuses() = listOf(
        AgentInstallStatus(kind = "claude", installed = true, authed = true),
        AgentInstallStatus(kind = "codex", installed = true, authed = false),
        AgentInstallStatus(kind = "cursor", installed = false, authed = false),
        AgentInstallStatus(kind = "opencode", installed = true, authed = false),
        AgentInstallStatus(kind = "grok", installed = true, authed = false),
    )

    @Test fun one_item_per_provider_in_the_machines_order_with_the_lede_and_the_note() = runComposeUiTest {
        setPlatformContent { SupermuxTheme(appearance = AppearanceMode.DARK) { ProvidersListHarness(AgentSettingsActions(agentStatuses = { statuses() })) } }
        awaitTag("providers_grok")
        onNodeWithTag("agent_settings_screen").assertExists()
        onNodeWithText("Providers & accounts").assertExists()
        onNodeWithText("The coding agents this machine can run, and the logins they use.").assertExists()
        listOf("claude", "codex", "cursor", "opencode", "grok").forEach {
            onNodeWithTag("providers_$it").assertExists()
            onNodeWithTag("agent_row_$it").assertExists()
            onNode(anchored("provider-$it")).assertExists()
        }
        onNodeWithText("Claude Code").assertExists()
        onNodeWithText("OpenCode").assertExists()
        val top = { kind: String -> onNodeWithTag("providers_$kind").getBoundsInRoot().top }
        assertTrue(top("claude") < top("codex") && top("codex") < top("cursor") && top("cursor") < top("opencode"))
        onNodeWithText("Model, effort and permissions are picked per chat, in the composer.").assertExists()
    }

    @Test fun ready_sign_in_again_and_install_trail_the_items() = runComposeUiTest {
        val list = listOf(
            AgentInstallStatus(kind = "claude", installed = true, authed = true),
            AgentInstallStatus(kind = "cursor", installed = true, authed = false, signInExpired = true),
            AgentInstallStatus(kind = "grok", installed = false, authed = false),
        )
        setPlatformContent { SupermuxTheme(appearance = AppearanceMode.DARK) { ProvidersListHarness(AgentSettingsActions(agentStatuses = { list })) } }
        awaitTag("providers_grok")
        onNodeWithText("Ready").assertExists()
        // An expired sign-in still leaves the CLI's credential file; the machine reports the refusal (#63).
        onNodeWithText("Sign-in expired — sign in again").assertExists()
        onNodeWithText("Sign in again").assertExists()
        onNodeWithText("Not installed").assertExists()
        onNodeWithTag("agent_install_start_grok").assertExists()
        onNodeWithTag("agent_install_start_claude").assertDoesNotExist()
    }

    @Test fun the_status_line_names_your_login_and_counts_the_added_ones() = runComposeUiTest {
        val ci = AccountDto(id = "claude-ci", agent = "claude", method = "token", label = "CI token", createdAt = "2026-10-02T00:00:00.000Z")
        var ensured = 0
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProvidersListHarness(
                    AgentSettingsActions(agentStatuses = { statuses() }),
                    AccountsActions(
                        accounts = flowOf(listOf(systemAccount("claude", "me@x.dev"), workAccountWithUsage(), ci)),
                        ensureAccounts = { ensured++ },
                    ),
                )
            }
        }
        awaitText("Your login · me@x.dev + 2 more")
        assertTrue(ensured >= 1)
    }

    @Test fun tapping_a_provider_opens_its_page() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProvidersListHarness(AgentSettingsActions(agentStatuses = { statuses() }), onOpen = { opened += it })
            }
        }
        awaitTag("providers_codex")
        onNodeWithTag("providers_codex").performClick()
        waitForIdle()
        assertEquals(listOf("codex"), opened)
    }

    @Test fun install_runs_from_the_list_and_says_installing() = runComposeUiTest {
        val started = AtomicBoolean(false)
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProvidersListHarness(
                    AgentSettingsActions(
                        agentStatuses = { listOf(AgentInstallStatus(kind = "cursor", installed = false, authed = false)) },
                        agentStartInstall = { started.set(true); AgentInstallJob(state = "running", log = "fetching…") },
                        agentPollInstall = { if (!started.get()) null else AgentInstallJob(state = "running", log = "still going") },
                    ),
                )
            }
        }
        awaitTag("agent_install_start_cursor")
        onNodeWithTag("agent_install_start_cursor").performClick()
        awaitTag("agent_install_running_cursor")
        onNodeWithText("Installing…").assertExists()
        assertTrue(started.get())
    }

    @Test fun install_running_to_done_reloads_statuses() = runComposeUiTest {
        val loads = AtomicInteger(0)
        val polls = AtomicInteger(0)
        val started = AtomicBoolean(false)
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProvidersListHarness(
                    AgentSettingsActions(
                        agentStatuses = {
                            listOf(AgentInstallStatus(kind = "cursor", installed = loads.incrementAndGet() > 1, authed = false))
                        },
                        agentStartInstall = { started.set(true); AgentInstallJob(state = "running", log = "…") },
                        agentPollInstall = {
                            if (!started.get()) null
                            else if (polls.incrementAndGet() < 2) AgentInstallJob(state = "running", log = "…")
                            else AgentInstallJob(state = "done", log = "ok", exitCode = 0)
                        },
                    ),
                )
            }
        }
        awaitTag("agent_install_start_cursor")
        onNodeWithTag("agent_install_start_cursor").performClick()
        awaitText("Installed, not authenticated", timeout = 8_000)
        assertTrue(loads.get() >= 2)
    }

    @Test fun a_failed_install_says_so_on_its_item() = runComposeUiTest {
        val started = AtomicBoolean(false)
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProvidersListHarness(
                    AgentSettingsActions(
                        agentStatuses = { listOf(AgentInstallStatus(kind = "cursor", installed = false, authed = false)) },
                        agentStartInstall = { started.set(true); AgentInstallJob(state = "running") },
                        agentPollInstall = { if (!started.get()) null else AgentInstallJob(state = "failed", log = "npm ERR") },
                    ),
                )
            }
        }
        awaitTag("agent_install_start_cursor")
        onNodeWithTag("agent_install_start_cursor").performClick()
        awaitText("Installation failed.")
    }

    // ── loading ─────────────────────────────────────────────────────────────────────────────────

    @Test fun load_failure_shows_error_with_try_again_not_empty() = runComposeUiTest {
        val loads = AtomicInteger(0)
        setPlatformContent { SupermuxTheme(appearance = AppearanceMode.DARK) { ProvidersListHarness(AgentSettingsActions(agentStatuses = { loads.incrementAndGet(); null })) } }
        awaitTag("agent_settings_error")
        onNodeWithTag("agent_settings_retry").assertExists()
        onNodeWithText("Couldn't reach this machine.").assertExists()
        onNodeWithTag("agent_settings_empty").assertDoesNotExist()
        assertTrue(loads.get() >= 1)
    }

    @Test fun empty_status_list_shows_empty_state_not_error() = runComposeUiTest {
        setPlatformContent { SupermuxTheme(appearance = AppearanceMode.DARK) { ProvidersListHarness(AgentSettingsActions(agentStatuses = { emptyList() })) } }
        awaitTag("agent_settings_empty")
        onNodeWithTag("agent_settings_error").assertDoesNotExist()
        onNodeWithText("This machine reported no coding agents.").assertExists()
    }

    @Test fun retry_after_load_failure_recovers() = runComposeUiTest {
        val loads = AtomicInteger(0)
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProvidersListHarness(
                    AgentSettingsActions(agentStatuses = {
                        if (loads.incrementAndGet() == 1) null else listOf(AgentInstallStatus(kind = "claude", installed = true, authed = true))
                    }),
                )
            }
        }
        awaitTag("agent_settings_error")
        onNodeWithTag("agent_settings_retry").performClick()
        awaitTag("providers_claude")
        assertTrue(loads.get() >= 2)
    }

    @Test fun auto_retry_recovers_after_reconnect_without_manual_retry() = runComposeUiTest {
        val loads = AtomicInteger(0)
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProvidersListHarness(
                    AgentSettingsActions(agentStatuses = {
                        if (loads.incrementAndGet() == 1) null else listOf(AgentInstallStatus(kind = "codex", installed = true, authed = true))
                    }),
                )
            }
        }
        awaitTag("agent_settings_error")
        awaitTag("providers_codex", timeout = 8_000)
        assertTrue(loads.get() >= 2)
    }

    @Test fun an_offline_machine_shows_its_offline_line_and_loads_nothing() = runComposeUiTest {
        val off = KitMachine("m2", "studio-mini", local = false, online = false, status = "Offline", lastSeen = "2h ago")
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(off), "m2") {}) {
                    ProvidersListHarness(AgentSettingsActions(agentStatuses = { error("must not load for an offline machine") }))
                }
            }
        }
        awaitTag("settings_offline")
        onNodeWithText("studio-mini is offline · last seen 2h ago.").assertExists()
        onNodeWithText("The coding agents studio-mini can run, and the logins they use.").assertExists()
        onNodeWithTag("agent_settings_error").assertDoesNotExist()
    }

    // ── switching at a limit (#92 under it) ─────────────────────────────────────────────────────

    @Test fun switch_accounts_at_a_limit_puts_the_setting() = runComposeUiTest {
        val calls = mutableListOf<Boolean>()
        val auto = MutableStateFlow<Boolean?>(false)
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProvidersListHarness(
                    AgentSettingsActions(agentStatuses = { statuses() }),
                    AccountsActions(autoSwitch = auto, setAutoSwitch = { calls += it; auto.value = it; AccountResult.Ok(it) }),
                )
            }
        }
        awaitTag("accounts-autoswitch")
        onNodeWithText("Switch accounts at a limit").assertExists()
        onNodeWithText("With two logins for a provider, a chat moves to the other one when the first runs out. Not for Cursor or logins kept apart.")
            .assertExists()
        onNode(anchored("auto-switch")).assertExists()
        onNodeWithTag("accounts-autoswitch").assertIsOff()
        onNodeWithTag("accounts-autoswitch").performClick()
        waitForIdle()
        assertEquals(listOf(true), calls)
        onNodeWithTag("accounts-autoswitch").assertIsOn()
    }

    @Test fun a_failed_switch_setting_says_so() = runComposeUiTest {
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProvidersListHarness(
                    AgentSettingsActions(agentStatuses = { statuses() }),
                    AccountsActions(autoSwitch = flowOf(false), setAutoSwitch = { AccountResult.Failed(null, null, null) }),
                )
            }
        }
        awaitTag("accounts-autoswitch")
        onNodeWithTag("accounts-autoswitch").performClick()
        awaitTag("accounts-autoswitch-error")
        onNodeWithText("Couldn't save the setting.").assertExists()
        onNodeWithTag("accounts-autoswitch").assertIsOff()
    }

    @Test fun resume_after_a_limit_reset_sits_under_auto_switch_on_unless_turned_off_and_writes_only_itself() = runComposeUiTest {
        val calls = mutableListOf<Boolean>()
        val resume = MutableStateFlow<Boolean?>(null)
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProvidersListHarness(
                    AgentSettingsActions(agentStatuses = { statuses() }),
                    AccountsActions(
                        autoResume = resume,
                        setAutoResume = { calls += it; resume.value = it; AccountResult.Ok(it) },
                        setAutoSwitch = { error("auto-switch must not be written") },
                    ),
                )
            }
        }
        awaitTag("accounts-autoresume")
        onNodeWithText("Resume after a limit reset").assertExists()
        onNodeWithText("When every login has run out, the chat waits for the first reset and then carries on.").assertExists()
        onNode(anchored("limit-resume")).assertExists()
        assertTrue(onNodeWithTag("accounts-autoresume").getBoundsInRoot().top >= onNodeWithTag("accounts-autoswitch").getBoundsInRoot().bottom)
        onNodeWithTag("accounts-autoresume").assertIsOn()
        onNodeWithTag("accounts-autoresume").performClick()
        waitForIdle()
        assertEquals(listOf(false), calls)
        onNodeWithTag("accounts-autoresume").assertIsOff()
    }

    // ── list ↔ provider page share the holders ──────────────────────────────────────────────────

    @Test fun an_install_started_on_the_list_is_still_running_on_the_provider_page() = runComposeUiTest {
        val starts = AtomicInteger(0)
        var sub by mutableStateOf<String?>(null)
        val agents = AgentSettingsActions(
            agentStatuses = { listOf(AgentInstallStatus(kind = "cursor", installed = false, authed = false)) },
            agentStartInstall = { starts.incrementAndGet(); AgentInstallJob(state = "running", log = "…") },
            agentPollInstall = { if (starts.get() == 0) null else AgentInstallJob(state = "running", log = "…") },
        )
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                val providers = rememberProvidersState(agents)
                val open = sub
                if (open == null) ProvidersPage(providers, AccountsActions(), onOpenProvider = { sub = it })
                else ProviderDetailPage(open, providers, agents, AccountsActions(), onBack = { sub = null })
            }
        }
        awaitTag("agent_install_start_cursor")
        onNodeWithTag("agent_install_start_cursor").performClick()
        awaitTag("agent_install_running_cursor")
        onNodeWithTag("providers_cursor").performClick()
        waitForIdle()
        assertEquals("cursor", sub)
        awaitTag("agent_install_cancel_cursor")
        onNodeWithTag("agent_install_running_cursor").assertExists()
        assertEquals(1, starts.get())
    }

    // ── the wizard's screen: the list, a provider in place ──────────────────────────────────────

    @Test fun the_wizards_screen_opens_a_provider_in_place_and_comes_back() = runComposeUiTest {
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) { AgentSettingsScreen(AgentSettingsActions(agentStatuses = { statuses() }), topBarShown = true) }
        }
        awaitTag("providers_codex")
        onNodeWithTag("providers_codex").performClick()
        awaitTag("agent_login_start_codex")
        onNodeWithTag("providers_codex").assertDoesNotExist()
        onNodeWithTag("settings_page_back").performClick()
        awaitTag("providers_codex")
    }

    @Test fun on_a_phone_the_wizards_provider_has_its_own_way_back() = runComposeUiTest {
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { AgentSettingsScreen(AgentSettingsActions(agentStatuses = { statuses() })) }
        }
        awaitTag("providers_codex")
        // The screen never paints a stock top bar of its own: Settings' hub (or the wizard) owns the chrome.
        onNodeWithTag("agent_settings_back").assertDoesNotExist()
        onNodeWithTag("providers_codex").performClick()
        awaitTag("settings_page_back")
        onNodeWithTag("settings_page_back").performClick()
        awaitTag("providers_codex")
    }

    @Test fun phone_items_are_touch_sized() = runComposeUiTest {
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { ProvidersListHarness(AgentSettingsActions(agentStatuses = { statuses() })) }
        }
        awaitTag("providers_claude")
        val b = onNodeWithTag("providers_claude").getBoundsInRoot()
        assertTrue(b.bottom - b.top >= 48.dp, "a provider item is ${b.bottom - b.top} tall")
    }

    // ── HostStore + mocked broker ───────────────────────────────────────────────────────────────

    @Test fun desktop_app_state_agent_statuses_decodes_mock_broker() = runComposeUiTest {
        val app = appForAgents()
        var listed: List<AgentInstallStatus>? = emptyList()
        val actions = hostActions(app)
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AgentSettingsScreen(
                    actions = AgentSettingsActions(agentStatuses = { actions.agentStatuses().also { listed = it } }),
                    topBarShown = true,
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { listed?.isNotEmpty() == true }
        assertEquals(2, listed!!.size)
        assertEquals("claude", listed!![0].kind)
        assertTrue(listed!![0].authed)
        awaitTag("agent_row_claude")
        onNodeWithTag("agent_row_claude").assertIsDisplayed()
    }

    @Test fun desktop_app_state_agent_statuses_null_on_broker_error() = runComposeUiTest {
        val app = appForAgents(statusJson = null)
        var result: List<AgentInstallStatus>? = emptyList()
        var called = false
        setPlatformContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AgentSettingsScreen(
                    actions = AgentSettingsActions(agentStatuses = { app.agentStatuses().also { result = it; called = true } }),
                    topBarShown = true,
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { called }
        assertNull(result)
        awaitTag("agent_settings_error")
    }

    @Test fun desktop_app_state_mutation_non2xx_is_failure() = runBlocking {
        val app = appForAgents(statusJson = """[{"kind":"codex","installed":true,"authed":false}]""", mutationStatus = HttpStatusCode.InternalServerError)
        assertFalse(app.saveAgentSecret("codex", "sk-should-fail"), "saveAgentSecret must fail on HTTP 500")
        assertFalse(app.setOpenCodeKey("opencode", "k"), "setOpenCodeKey must fail on HTTP 500")
        assertFalse(app.finishOpenCodeOAuth("google", 0, "code"), "finishOpenCodeOAuth must fail on HTTP 500")
    }

    @Test fun desktop_app_state_mutation_2xx_is_success() = runBlocking {
        val app = appForAgents(mutationStatus = HttpStatusCode.OK)
        assertTrue(app.saveAgentSecret("codex", "sk-ok"))
        assertTrue(app.setOpenCodeKey("opencode", "k"))
        assertTrue(app.finishOpenCodeOAuth("google", 0, "code"))
    }
}
