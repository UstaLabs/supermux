package dev.supermux.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountIdentityDto
import dev.supermux.net.AccountLoginStateDto
import dev.supermux.net.AccountUsageWindowDto
import dev.supermux.net.SessionAccountResult
import dev.supermux.state.AccountResult
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The accounts UI (slice A3b): the Settings list, the guided-login sheet's state machine (phases →
 * what it shows; submit and cancel reach the broker) and the session pill's switch.
 */
@OptIn(ExperimentalTestApi::class)
class AccountsUiTest {
    private val claudeSystem = AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true,
        identity = AccountIdentityDto(email = "me@x.dev"), createdAt = "1970-01-01T00:00:00.000Z")
    private val work = AccountDto(id = "claude-work", agent = "claude", method = "subscription", label = "Work (Max)",
        identity = AccountIdentityDto(email = "me@acme.dev"), createdAt = "2026-10-01T00:00:00.000Z",
        usage = listOf(AccountUsageWindowDto("five_hour", 62.0), AccountUsageWindowDto("seven_day", 18.0)))
    private val ciToken = AccountDto(id = "claude-ci", agent = "claude", method = "token", label = "CI token", isolated = true,
        createdAt = "2026-10-02T00:00:00.000Z")
    private val codexSystem = AccountDto(id = "codex:system", agent = "codex", method = "system", label = "System login", system = true)

    private fun ComposeUiTest.content(body: @Composable () -> Unit) =
        setPlatformContent(widthClass = WindowWidthClass.Expanded) { SupermuxTheme(appearance = AppearanceMode.DARK) { body() } }

    // ── Settings → Accounts ────────────────────────────────────────────────────────────────────

    @Test fun lists_system_first_then_added_with_chips_meters_and_isolated() = runComposeUiTest {
        val accounts = MutableStateFlow<List<AccountDto>?>(listOf(work, codexSystem, ciToken, claudeSystem))
        var ensured = 0
        content {
            AccountsSettingsScreen(AccountsActions(accounts = accounts, ensureAccounts = { ensured++ }), topBarShown = true)
        }
        waitForIdle()
        assertEquals(1, ensured)
        onNodeWithTag("account-row:claude:system").assertIsDisplayed()
        onNodeWithText("Signed in as me@x.dev · this machine's login").assertIsDisplayed()
        onNodeWithTag("account-row:claude-work").assertIsDisplayed()
        onNodeWithText("Subscription").assertIsDisplayed()
        onNodeWithText("Token").assertIsDisplayed()
        onNodeWithTag("account-isolated:claude-ci").assertIsDisplayed()
        onNodeWithText("62%", substring = true).assertIsDisplayed()
        onNodeWithText("18%", substring = true).assertIsDisplayed()
        // Added accounts exist → no empty-state line.
        onNodeWithTag("accounts-empty").assertDoesNotExist()
        // The system login cannot be removed.
        onNodeWithTag("account-remove:claude:system").assertDoesNotExist()
        onNodeWithTag("account-remove:claude-work").assertExists()
    }

    @Test fun system_logins_only_shows_the_one_line_empty_state() = runComposeUiTest {
        content { AccountsSettingsScreen(AccountsActions(accounts = MutableStateFlow(listOf(claudeSystem, codexSystem))), topBarShown = true) }
        waitForIdle()
        onNodeWithTag("accounts-empty").assertIsDisplayed()
        onNodeWithTag("account-add:claude").assertIsDisplayed()
    }

    @Test fun autoswitch_toggle_puts_the_setting() = runComposeUiTest {
        val calls = mutableListOf<Boolean>()
        val auto = MutableStateFlow<Boolean?>(false)
        content {
            AccountsSettingsScreen(
                AccountsActions(
                    accounts = MutableStateFlow(listOf(claudeSystem)),
                    autoSwitch = auto,
                    setAutoSwitch = { calls += it; auto.value = it; AccountResult.Ok(it) },
                ),
                topBarShown = true,
            )
        }
        waitForIdle()
        onNodeWithTag("accounts-autoswitch").performClick()
        waitForIdle()
        assertEquals(listOf(true), calls)
        onNodeWithTag("accounts-autoswitch").assertIsOn()
    }

    @Test fun remove_confirms_and_can_delete_a_subscription_folder() = runComposeUiTest {
        val removed = mutableListOf<Pair<String, Boolean>>()
        content {
            AccountsSettingsScreen(
                AccountsActions(
                    accounts = MutableStateFlow(listOf(claudeSystem, work)),
                    removeAccount = { id, home -> removed += id to home; AccountResult.Ok(Unit) },
                ),
                topBarShown = true,
            )
        }
        waitForIdle()
        onNodeWithTag("account-remove:claude-work").performClick()
        waitForIdle()
        onNodeWithTag("account-remove-dialog").assertExists()
        onNodeWithTag("account-remove-delete-home").performClick()
        onNodeWithTag("account-remove-confirm").performClick()
        waitForIdle()
        assertEquals(listOf("claude-work" to true), removed)
        onNodeWithTag("account-remove-dialog").assertDoesNotExist()
    }

    // ── guided login ───────────────────────────────────────────────────────────────────────────

    private fun login(phase: String, url: String? = null, code: String? = null, needsCode: Boolean = false,
                      error: String? = null, errorCode: String? = null, account: AccountDto? = null, id: String = "L1") =
        AccountLoginStateDto(loginId = id, agent = "claude", phase = phase, url = url, code = code, needsCode = needsCode,
            error = error, errorCode = errorCode, account = account)

    @Test fun login_sheet_follows_frames_and_sends_code_and_cancel() = runComposeUiTest {
        val frames = MutableSharedFlow<AccountLoginStateDto>(extraBufferCapacity = 16)
        val started = mutableListOf<String>()
        val codes = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<String>()
        var closed = false
        content {
            AddAccountSheet(
                agent = "claude",
                actions = AccountsActions(
                    startLogin = { started += it; AccountResult.Ok(login("starting")) },
                    loginFrames = frames,
                    sendLoginCode = { id, c -> codes += id to c; AccountResult.Ok(Unit) },
                    cancelLogin = { cancelled += it; AccountResult.Ok(Unit) },
                ),
                existing = listOf(claudeSystem),
                onDismiss = { closed = true },
            )
        }
        waitForIdle()
        onNodeWithTag("account-add-option:login").performClick()
        waitForIdle()
        assertEquals(listOf("claude"), started)
        onNodeWithTag("account-login-starting").assertExists()

        // A frame for ANOTHER login is ignored.
        frames.tryEmit(login("verifying", id = "other"))
        waitForIdle()
        onNodeWithTag("account-login-starting").assertExists()

        frames.tryEmit(login("awaiting_user", url = "https://claude.ai/oauth", needsCode = true))
        waitForIdle()
        onNodeWithTag("account-login-open").assertExists()
        onNodeWithTag("account-login-code").performTextInput("PASTED#1")
        onNodeWithTag("account-login-submit").performClick()
        waitForIdle()
        assertEquals(listOf("L1" to "PASTED#1"), codes)

        frames.tryEmit(login("verifying"))
        waitForIdle()
        onNodeWithTag("account-login-verifying").assertExists()
        // A late "awaiting_user" (e.g. a poll answer) never moves the sheet backwards.
        frames.tryEmit(login("awaiting_user", url = "https://claude.ai/oauth", needsCode = true))
        waitForIdle()
        onNodeWithTag("account-login-verifying").assertExists()

        onNodeWithTag("account-login-cancel").performClick()
        waitForIdle()
        assertEquals(listOf("L1"), cancelled)
        assertTrue(closed)
    }

    @Test fun device_code_done_and_failure_states() = runComposeUiTest {
        val frames = MutableSharedFlow<AccountLoginStateDto>(extraBufferCapacity = 16)
        var starts = 0
        content {
            AddAccountSheet(
                agent = "claude",
                actions = AccountsActions(
                    startLogin = { starts++; AccountResult.Ok(login("starting")) },
                    loginFrames = frames,
                ),
                existing = listOf(claudeSystem, work),
                onDismiss = {},
            )
        }
        waitForIdle()
        onNodeWithTag("account-add-option:login").performClick()
        waitForIdle()
        frames.tryEmit(login("awaiting_user", url = "https://d", code = "GXQM-7PRT"))
        waitForIdle()
        onNodeWithTag("account-login-device-code").assertExists()
        onNodeWithText("GXQM-7PRT").assertIsDisplayed()

        frames.tryEmit(login("failed", errorCode = "account_exists", error = "Account claude-work is already logged in as this identity"))
        waitForIdle()
        onNodeWithText("This login is already added as Work (Max).").assertIsDisplayed()
        onNodeWithTag("account-login-retry").performClick()
        waitForIdle()
        assertEquals(2, starts)

        frames.tryEmit(login("done", account = work.copy(id = "claude-new", label = "me@new.dev")))
        waitForIdle()
        onNodeWithText("Added me@new.dev").assertIsDisplayed()
    }

    @Test fun paste_a_token_adds_a_token_account() = runComposeUiTest {
        val added = mutableListOf<List<String?>>()
        var closed = false
        content {
            AddAccountSheet(
                agent = "claude",
                actions = AccountsActions(addAccount = { a, m, s, l -> added += listOf(a, m, s, l); AccountResult.Ok(ciToken) }),
                existing = listOf(claudeSystem),
                onDismiss = { closed = true },
            )
        }
        waitForIdle()
        onNodeWithTag("account-add-option:token").performClick()
        waitForIdle()
        onNodeWithText("claude setup-token").assertIsDisplayed()
        onNodeWithTag("account-token-field").performTextInput("sk-ant-oat01-abc")
        onNodeWithTag("account-label-field").performTextInput("CI")
        onNodeWithTag("account-secret-save").performClick()
        waitForIdle()
        assertEquals(listOf(listOf<String?>("claude", "token", "sk-ant-oat01-abc", "CI")), added)
        assertTrue(closed)
    }

    // ── session pill ───────────────────────────────────────────────────────────────────────────

    @Test fun session_pill_switches_and_shows_a_busy_refusal() = runComposeUiTest {
        val switched = mutableListOf<String>()
        var busy = true
        content {
            SessionAccountPill(
                agent = "claude",
                accountId = "claude-work",
                accountLabel = "Work (Max)",
                accounts = listOf(claudeSystem, work, ciToken),
                onSwitch = { id ->
                    switched += id
                    if (busy) AccountResult.Failed(409, "session_busy", "Session has an outstanding lifecycle operation")
                    else AccountResult.Ok(SessionAccountResult(ok = true, session = "s", account = id, accountLabel = "CI token"))
                },
            )
        }
        waitForIdle()
        onNodeWithText("Work (Max)").assertIsDisplayed()
        onNodeWithTag("session-account-pill").performClick()
        waitForIdle()
        onNodeWithTag("session-account-item:claude-ci").performClick()
        waitForIdle()
        assertEquals(listOf("claude-ci"), switched)
        onNodeWithTag("session-account-error").assertExists()
        onNodeWithText("Busy right now — try again after this turn.").assertExists()
        busy = false
        onNodeWithTag("session-account-item:claude:system").performClick()
        waitForIdle()
        assertEquals(listOf("claude-ci", "claude:system"), switched)
        onNodeWithTag("session-account-item:claude:system").assertDoesNotExist()
    }

    @Test fun session_pill_hidden_on_the_only_system_login() = runComposeUiTest {
        content {
            SessionAccountPill(agent = "codex", accountId = null, accountLabel = null, accounts = listOf(codexSystem), onSwitch = { AccountResult.Failed(null, null, null) })
        }
        waitForIdle()
        onNodeWithTag("session-account-pill").assertDoesNotExist()
    }

    @Test fun launcher_picker_only_with_a_choice() = runComposeUiTest {
        var picked: String? = "unset"
        content {
            LauncherAccountPicker(agent = "claude", accounts = listOf(claudeSystem, work), selected = null, onSelect = { picked = it })
            LauncherAccountPicker(agent = "codex", accounts = listOf(claudeSystem, work, codexSystem), selected = null, onSelect = {})
        }
        waitForIdle()
        // The default (system login) is the bare glyph; the label appears once another is picked.
        onNodeWithTag("account-picker").assertIsDisplayed()
        onNodeWithText("System login").assertDoesNotExist()
        onNodeWithTag("account-picker").performClick()
        waitForIdle()
        onNodeWithTag("account-picker-item:claude-work").performClick()
        waitForIdle()
        assertEquals("claude-work", picked)
    }
}
