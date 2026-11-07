package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountIdentityDto
import dev.supermux.net.AccountUnavailableDto
import dev.supermux.net.AccountUsageWindowDto
import dev.supermux.net.ModelInfo
import dev.supermux.net.ModelsResponse
import dev.supermux.net.SessionAccountResult
import dev.supermux.proto.SessionInfo
import dev.supermux.state.AccountResult
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A chat's account in the run-settings panel (the model pill): which account a running chat runs
 * on, and the switch to another — the job of the header's account pill, which this replaced. The
 * pure rules ([RunSettingsAccount.forChat], [SessionAccountSwitch]), then the panel through the real
 * [Composer] under a pointer and under touch, then [ChatPanel]: no account in the header, the
 * account behind the pill, with or without a header.
 */
@OptIn(ExperimentalTestApi::class)
class RunSettingsAccountTest {
    private val system = AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true,
        identity = AccountIdentityDto(email = "me@x.dev"), createdAt = "1970-01-01T00:00:00.000Z")
    private val work = AccountDto(id = "claude-work", agent = "claude", method = "subscription", label = "Work (Max)",
        createdAt = "2026-10-01T00:00:00.000Z", usage = listOf(AccountUsageWindowDto("five_hour", 62.0), AccountUsageWindowDto("seven_day", 18.0)))
    private val ci = AccountDto(id = "claude-ci", agent = "claude", method = "token", label = "CI token", isolated = true,
        createdAt = "2026-10-02T00:00:00.000Z")
    private val codexSystem = AccountDto(id = "codex:system", agent = "codex", method = "system", label = "System login", system = true)
    private val refusedSystem = system.copy(unavailable = AccountUnavailableDto(
        reason = "Your organization has disabled Claude subscription access for Claude Code", since = "2026-10-11T09:08:27.000Z"))
    private val models = ModelsResponse(agent = "claude", current = "opus", models = listOf(ModelInfo("opus", "Opus"), ModelInfo("sonnet", "Sonnet")))

    /** A switch nobody calls: the pure rules only read it. */
    private val idle = SessionAccountSwitch(CoroutineScope(Dispatchers.Unconfined)) { AccountResult.Failed(null, null, null) }

    private fun ok(id: String) = AccountResult.Ok(SessionAccountResult(ok = true, session = "s1", account = id))

    // ── which chats get an Account row ──────────────────────────────────────────────────────────

    @Test fun a_chat_on_the_only_system_login_has_no_account_row() {
        assertNull(RunSettingsAccount.forChat("codex", null, null, listOf(system, work, codexSystem), idle))
        assertNull(RunSettingsAccount.forChat("codex", "codex:system", "System login", listOf(system, work, codexSystem), idle))
    }

    @Test fun a_chat_on_an_added_account_names_it_before_the_list_has_loaded() {
        val a = assertNotNull(RunSettingsAccount.forChat("claude", "claude-work", "Work (Max)", null, idle))
        assertEquals("Work (Max)", a.label)
        assertTrue(a.onAdded)
        assertFalse(a.offersAuto)
        assertTrue(a.accounts.isEmpty())
    }

    @Test fun a_chat_with_a_choice_runs_on_the_system_login_by_its_id() {
        val a = assertNotNull(RunSettingsAccount.forChat("claude", null, null, listOf(work, system), idle))
        assertEquals("claude:system", a.selectedId)
        assertEquals(system, a.current)
        assertEquals("System login", a.label)
        assertFalse(a.onAdded)
        // The agent's accounts only, system login first.
        assertEquals(listOf("claude:system", "claude-work"), a.accounts.map { it.id })
    }

    @Test fun an_unavailable_system_login_is_shown_even_without_a_choice() {
        val a = assertNotNull(RunSettingsAccount.forChat("claude", "claude:system", null, listOf(refusedSystem), idle))
        assertTrue(a.unavailable)
    }

    @Test fun a_new_chat_reads_auto_and_falls_back_to_it_when_its_pick_goes_away() {
        val listed = listOf(system, work.copy(auto = true))
        assertEquals("Auto · Work (Max)", RunSettingsAccount(listed, null, {}).label)
        val gone = RunSettingsAccount(listed, "claude-gone", {})
        assertEquals("Auto · Work (Max)", gone.label)
        assertFalse(gone.onAdded)
        val picked = RunSettingsAccount(listed, "claude-work", {})
        assertEquals("Work (Max)", picked.label)
        assertTrue(picked.onAdded)
    }

    // ── the switch ──────────────────────────────────────────────────────────────────────────────

    @Test fun a_switch_runs_one_at_a_time_and_keeps_its_refusal() = runTest {
        val answer = CompletableDeferred<AccountResult<SessionAccountResult>>()
        val asked = mutableListOf<String>()
        val switch = SessionAccountSwitch(this) { id -> asked += id; answer.await() }
        switch.start("claude-ci")
        switch.start("claude:system") // a second pick while one is in flight is not taken
        runCurrent()
        assertEquals(listOf("claude-ci"), asked)
        assertEquals("claude-ci", switch.pendingId)
        answer.complete(AccountResult.Failed(409, "session_busy", "Session has an outstanding lifecycle operation"))
        runCurrent()
        assertNull(switch.pendingId)
        assertEquals("Busy right now — try again after this turn.", switch.refusal)
        switch.clearRefusal()
        assertNull(switch.refusal)
    }

    // ── the panel ───────────────────────────────────────────────────────────────────────────────

    /**
     * The composer of a running claude chat. [onSwitch] answers POST /sessions/<id>/account; a switch
     * that lands moves the session (the broker's `session_state`) before it answers.
     */
    @Composable
    private fun ChatComposer(
        accounts: List<AccountDto>?,
        sessionAccount: () -> String?,
        onSwitch: suspend (String) -> AccountResult<SessionAccountResult>,
        onOpenAccounts: (() -> Unit)? = null,
        onSignIn: (() -> Unit)? = null,
    ) {
        val scope = rememberCoroutineScope()
        val switch = remember { SessionAccountSwitch(scope, onSwitch) }
        Composer(
            draft = "", onDraftChange = {}, sending = false, agentWorking = false,
            onSend = { _, _ -> }, onInterrupt = {},
            models = models,
            sessionAgent = "claude",
            account = RunSettingsAccount.forChat("claude", sessionAccount(), null, accounts, switch, onOpenAccounts, onSignIn),
        )
    }

    private fun ComposeUiTest.openAccountList() {
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").performClick()
        waitForIdle()
    }

    @Test fun the_panel_names_the_account_and_switches_it_and_the_pill_has_no_account_icon() = runComposeUiTest {
        var sessionAccount by mutableStateOf<String?>("claude-work")
        val asked = mutableListOf<String>()
        setPlatformContent {
            ChatComposer(listOf(system, work, ci), { sessionAccount }, onSwitch = { id -> asked += id; sessionAccount = id; ok(id) })
        }
        waitForIdle()
        // No account icon on the pill: the account is named in the panel only.
        onNodeWithContentDescription("Work (Max)", substring = true).assertDoesNotExist()
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").assertTextContains("Work (Max)", substring = true)
        onNodeWithTag("run_settings_account").performClick()
        waitForIdle()
        onNodeWithText("Run this chat on").assertIsDisplayed()
        // A running chat is on one account: no Auto. Each row says how it signs in and how full it is.
        onNodeWithTag("account-picker-item:auto").assertDoesNotExist()
        onNodeWithTag("account-picker-item:claude-work").assertTextContains("Subscription · 5h 62%", substring = true)
        onNodeWithTag("account-picker-item:claude-ci").assertTextContains("Isolated", substring = true)
        onNodeWithTag("account-picker-item:claude:system").performClick()
        waitForIdle()
        assertEquals(listOf("claude:system"), asked)
        // It landed: the panel is back, its Account row on the new account.
        onNodeWithTag("run_settings_account").assertTextContains("System login", substring = true)
    }

    @Test fun picking_the_account_it_runs_on_just_steps_back() = runComposeUiTest {
        val asked = mutableListOf<String>()
        setPlatformContent { ChatComposer(listOf(system, work), { "claude-work" }, onSwitch = { id -> asked += id; ok(id) }) }
        waitForIdle()
        openAccountList()
        onNodeWithTag("account-picker-item:claude-work").performClick()
        waitForIdle()
        assertEquals(emptyList<String>(), asked)
        onNodeWithTag("run_settings_account").assertIsDisplayed()
    }

    @Test fun a_refused_switch_says_why_and_keeps_the_list_open() = runComposeUiTest {
        val asked = mutableListOf<String>()
        setPlatformContent {
            ChatComposer(listOf(system, work, ci), { "claude-work" }, onSwitch = { id ->
                asked += id
                AccountResult.Failed(409, "session_busy", "Session has an outstanding lifecycle operation")
            })
        }
        waitForIdle()
        openAccountList()
        onNodeWithTag("account-picker-item:claude-ci").performClick()
        waitForIdle()
        assertEquals(listOf("claude-ci"), asked)
        onNode(hasText("Busy right now — try again after this turn.") and hasParent(hasTestTag("run_settings_account_refusal")))
            .assertIsDisplayed()
        onNodeWithText("Run this chat on").assertIsDisplayed()
        // Still on Work: back on the panel, its Account row says so.
        onNodeWithTag("run_settings_back").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").assertTextContains("Work (Max)", substring = true)
    }

    @Test fun a_switch_in_flight_spins_takes_no_second_pick_and_outlives_the_panel() = runComposeUiTest {
        var sessionAccount by mutableStateOf<String?>("claude-work")
        val answer = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()
        var landed = 0
        setPlatformContent {
            ChatComposer(listOf(system, work, ci), { sessionAccount }, onSwitch = { id ->
                asked += id
                answer.await()
                landed++
                sessionAccount = id
                ok(id)
            })
        }
        waitForIdle()
        openAccountList()
        onNodeWithTag("account-picker-item:claude-ci").performClick()
        waitForIdle()
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        onNodeWithTag("account-picker-item:claude:system").performClick()
        waitForIdle()
        assertEquals(listOf("claude-ci"), asked)
        // Back to the panel (its Account row still spins) and close it the way a user would: pick a model.
        onNodeWithTag("run_settings_back").performClick()
        waitForIdle()
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        onNodeWithTag("composer-model-sonnet").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_panel").assertDoesNotExist()
        // The switch was not cancelled with the panel: it lands, and the panel shows the new account.
        answer.complete(Unit)
        waitForIdle()
        assertEquals(1, landed)
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").assertTextContains("CI token", substring = true)
    }

    @Test fun an_unavailable_account_is_flagged_in_the_panel_links_to_accounts_and_still_switches() = runComposeUiTest {
        val asked = mutableListOf<String>()
        var opened = 0
        setPlatformContent {
            ChatComposer(listOf(refusedSystem, work), { null }, onSwitch = { id -> asked += id; ok(id) }, onOpenAccounts = { opened++ })
        }
        waitForIdle()
        // The pill stays as it is; the panel's Account row and list carry the warning.
        onNodeWithContentDescription("unavailable", substring = true).assertDoesNotExist()
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").assertTextContains("System login", substring = true)
        onNodeWithTag("run_settings_account").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account_unavailable").assertExists()
        onNodeWithText("Refused: Your organization has disabled", substring = true).assertIsDisplayed()
        onNodeWithTag("account-unavailable-tag:claude:system", useUnmergedTree = true).assertExists()
        onNodeWithTag("run_settings_open_accounts").performClick()
        waitForIdle()
        assertEquals(1, opened)
        onNodeWithTag("run_settings_panel").assertDoesNotExist()
        // Marked, never blocked: another account is one pick away.
        openAccountList()
        onNodeWithTag("account-picker-item:claude-work").performClick()
        waitForIdle()
        assertEquals(listOf("claude-work"), asked)
    }

    // A system login whose sign-in expired (#63): the panel says so and its link is the sign-in itself.
    @Test fun an_expired_system_sign_in_says_so_and_links_to_signing_in_again() = runComposeUiTest {
        val expired = system.copy(unavailable = AccountUnavailableDto(
            reason = "Cursor sign-in expired — sign in to Cursor again.", since = "2026-11-04T10:40:58.000Z", code = "account_expired"))
        var signIns = 0
        var opened = 0
        setPlatformContent {
            ChatComposer(listOf(expired, work), { null }, onSwitch = { id -> ok(id) }, onOpenAccounts = { opened++ }, onSignIn = { signIns++ })
        }
        waitForIdle()
        openAccountList()
        onNodeWithTag("run_settings_account_unavailable").assertExists()
        onNodeWithText("Sign-in expired").assertIsDisplayed()
        onNodeWithTag("run_settings_open_accounts").assertDoesNotExist()
        onNodeWithTag("run_settings_sign_in").performClick()
        waitForIdle()
        assertEquals(1, signIns)
        assertEquals(0, opened)
        onNodeWithTag("run_settings_panel").assertDoesNotExist()
    }

    @Test fun under_touch_the_account_switches_from_the_sheet() = runComposeUiTest {
        var sessionAccount by mutableStateOf<String?>("claude-work")
        val asked = mutableListOf<String>()
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            ChatComposer(listOf(system, work), { sessionAccount }, onSwitch = { id -> asked += id; sessionAccount = id; ok(id) })
        }
        waitForIdle()
        openAccountList()
        onNodeWithText("Run this chat on").assertIsDisplayed()
        onNodeWithTag("account-picker-item:claude:system").performClick()
        waitForIdle()
        assertEquals(listOf("claude:system"), asked)
        onNodeWithTag("run_settings_account").assertTextContains("System login", substring = true)
    }

    // ── the chat ────────────────────────────────────────────────────────────────────────────────

    private val session = SessionInfo(id = "s1", name = "demo", workdir = "/home/u/proj", agent = "claude",
        account = "claude-work", accountLabel = "Work (Max)")

    @Test fun the_chat_header_carries_no_account_and_the_model_pill_does() = runComposeUiTest {
        var ensured = 0
        val asked = mutableListOf<String>()
        setPlatformContent {
            ChatPanel(
                session = session,
                state = ChatState(),
                actions = ChatActions(
                    accounts = flowOf(listOf(system, work)),
                    ensureAccounts = { ensured++ },
                    setAccount = { id -> asked += id; ok(id) },
                ),
                draft = "",
                onDraftChange = {},
                showHeader = true,
            )
        }
        waitForIdle()
        assertEquals(1, ensured)
        // The header names the session; nothing in it names the account any more.
        onNodeWithText("demo").assertIsDisplayed()
        onNodeWithText("Work (Max)").assertDoesNotExist()
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        onNodeWithTag("run_settings_account").assertTextContains("Work (Max)", substring = true)
        onNodeWithTag("run_settings_account").performClick()
        waitForIdle()
        onNodeWithTag("account-picker-item:claude:system").performClick()
        waitForIdle()
        assertEquals(listOf("claude:system"), asked)
    }

    @Test fun a_headerless_chat_loads_and_offers_the_account_too() = runComposeUiTest {
        var ensured = 0
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            ChatPanel(
                session = session,
                state = ChatState(),
                actions = ChatActions(accounts = flowOf(listOf(system, work)), ensureAccounts = { ensured++ }),
                draft = "",
                onDraftChange = {},
                showHeader = false,
            )
        }
        waitForIdle()
        assertEquals(1, ensured)
        openAccountList()
        onNodeWithText("Run this chat on").assertIsDisplayed()
        onNodeWithTag("account-picker-item:claude:system").assertExists()
    }
}
