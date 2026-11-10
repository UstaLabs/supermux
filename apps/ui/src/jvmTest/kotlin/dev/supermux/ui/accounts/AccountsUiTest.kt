package dev.supermux.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountRemovalPlanDto
import dev.supermux.net.AccountIdentityDto
import dev.supermux.net.AccountLoginStateDto
import dev.supermux.net.AccountUnavailableDto
import dev.supermux.net.AccountUsageWindowDto
import dev.supermux.state.AccountResult
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.net.AgentInstallStatus
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.pages.ProviderPageHarness
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The accounts UI (slice A3b): a provider page's logins (#97) and the guided-login sheet's state machine (phases →
 * what it shows; submit and cancel reach the broker). A chat's account switch lives in the
 * run-settings panel now: `dev.supermux.ui.chat.RunSettingsAccountTest`.
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

    // ── a provider page's logins (#97: Settings ▸ Accounts moved onto each provider's page) ───────

    private fun ComposeUiTest.providerPage(agent: String, accounts: AccountsActions, agents: AgentSettingsActions = AgentSettingsActions(
        agentStatuses = { listOf(AgentInstallStatus(kind = agent, installed = true, authed = true)) },
    )) = content { ProviderPageHarness(agent, agents, accounts) }

    @Test fun lists_your_login_first_then_added_with_methods_meters_and_kept_apart() = runComposeUiTest {
        val accounts = MutableStateFlow<List<AccountDto>?>(listOf(work, codexSystem, ciToken, claudeSystem))
        var ensured = 0
        providerPage("claude", AccountsActions(accounts = accounts, ensureAccounts = { ensured++ }))
        waitForIdle()
        assertTrue(ensured >= 1)
        onNodeWithTag("provider_login_claude:system").assertIsDisplayed()
        onNodeWithText("me@x.dev").assertIsDisplayed()
        onNodeWithTag("provider_login_claude-work").assertIsDisplayed()
        onNodeWithText("Subscription · me@acme.dev").assertIsDisplayed()
        onNodeWithText("Token").assertIsDisplayed()
        onNodeWithText("Doesn't switch automatically").assertIsDisplayed()
        onNodeWithText("62%", substring = true).assertIsDisplayed()
        onNodeWithText("18%", substring = true).assertIsDisplayed()
        // Another agent's logins are on its own page.
        onNodeWithTag("provider_login_codex:system").assertDoesNotExist()
        // Your login cannot be removed.
        onNodeWithTag("provider_login_claude:system").performClick()
        waitForIdle()
        onNodeWithTag("provider_login_claude:system_remove").assertDoesNotExist()
    }

    @Test fun with_only_your_login_add_a_login_is_offered() = runComposeUiTest {
        providerPage("claude", AccountsActions(accounts = MutableStateFlow(listOf(claudeSystem, codexSystem))))
        waitForIdle()
        onNodeWithTag("provider_login_add").assertIsDisplayed()
    }

    @Test fun remove_confirms_and_can_delete_a_subscription_folder() = runComposeUiTest {
        val removed = mutableListOf<Pair<String, Boolean>>()
        providerPage(
            "claude",
            AccountsActions(
                accounts = MutableStateFlow(listOf(claudeSystem, work)),
                removeAccount = { id, home -> removed += id to home; AccountResult.Ok(Unit) },
            ),
        )
        waitForIdle()
        onNodeWithTag("provider_login_claude-work").performClick()
        waitForIdle()
        onNodeWithTag("provider_login_claude-work_remove").performClick()
        waitForIdle()
        onNodeWithTag("provider_login_remove_sheet").assertExists()
        onNodeWithTag("account-remove-delete-home").performClick()
        onNodeWithTag("provider_login_remove_confirm").performClick()
        waitForIdle()
        assertEquals(listOf("claude-work" to true), removed)
        onNodeWithTag("provider_login_remove_sheet").assertDoesNotExist()
    }

    @Test fun a_token_login_has_no_sign_in_folder_to_delete() = runComposeUiTest {
        val removed = mutableListOf<Pair<String, Boolean>>()
        providerPage(
            "claude",
            AccountsActions(
                accounts = MutableStateFlow(listOf(claudeSystem, ciToken)),
                removeAccount = { id, home -> removed += id to home; AccountResult.Ok(Unit) },
            ),
        )
        waitForIdle()
        onNodeWithTag("provider_login_claude-ci").performClick()
        waitForIdle()
        onNodeWithTag("provider_login_claude-ci_remove").performClick()
        waitForIdle()
        onNodeWithTag("account-remove-delete-home").assertDoesNotExist()
        onNodeWithTag("provider_login_remove_confirm").performClick()
        waitForIdle()
        assertEquals(listOf("claude-ci" to false), removed)
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
                    startLogin = { agent, _ -> started += agent; AccountResult.Ok(login("starting")) },
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
                    startLogin = { _, _ -> starts++; AccountResult.Ok(login("starting")) },
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

    // ── unavailable (refused) accounts ─────────────────────────────────────────────────────────

    private val refusedSystem = claudeSystem.copy(unavailable = AccountUnavailableDto(
        reason = "Your organization has disabled Claude subscription access for Claude Code", since = "2026-10-11T09:08:27.000Z"))

    @Test fun a_refused_login_says_why() = runComposeUiTest {
        providerPage("claude", AccountsActions(accounts = MutableStateFlow(listOf(refusedSystem, work))))
        waitForIdle()
        onNodeWithText("Refused: Your organization has disabled", substring = true).assertIsDisplayed()
        onNodeWithText("Subscription · me@acme.dev").assertIsDisplayed()
    }

    // ── an expired sign-in (#63) ───────────────────────────────────────────────────────────────

    private val cursorExpired = AccountUnavailableDto(
        reason = "Cursor sign-in expired — sign in to Cursor again.", since = "2026-11-04T10:40:58.000Z", code = "account_expired")
    private val cursorSystem = AccountDto(id = "cursor:system", agent = "cursor", method = "system", label = "System login", system = true,
        unavailable = cursorExpired)
    private val cursorWork = AccountDto(id = "cursor-work", agent = "cursor", method = "subscription", label = "Work",
        createdAt = "2026-10-03T00:00:00.000Z", unavailable = cursorExpired)

    @Test fun an_expired_own_sign_in_says_so_and_signs_in_through_the_providers_flow() = runComposeUiTest {
        val starts = mutableListOf<String>()
        providerPage(
            "cursor",
            AccountsActions(accounts = MutableStateFlow(listOf(cursorSystem))),
            AgentSettingsActions(
                agentStatuses = { listOf(AgentInstallStatus(kind = "cursor", installed = true, authed = false)) },
                agentStartLogin = { starts += it; null },
            ),
        )
        waitForIdle()
        onNodeWithText("Sign-in expired").assertIsDisplayed()
        onNodeWithText("Refused:", substring = true).assertDoesNotExist()
        onNodeWithTag("agent_login_start_cursor").performClick()
        waitUntil(timeoutMillis = 5_000) { starts.isNotEmpty() }
        assertEquals(listOf("cursor"), starts)
    }

    @Test fun an_added_account_whose_sign_in_expired_signs_in_again_in_place() = runComposeUiTest {
        val started = mutableListOf<Pair<String, String?>>()
        providerPage(
            "cursor",
            AccountsActions(
                accounts = MutableStateFlow(listOf(cursorWork)),
                startLogin = { agent, email -> started += agent to email; AccountResult.Ok(AccountLoginStateDto(loginId = "l1", agent = agent, phase = "starting")) },
            ),
        )
        waitForIdle()
        onNodeWithText("Sign-in expired").assertIsDisplayed()
        onNodeWithText("Remove it, then add it again", substring = true).assertDoesNotExist()
        // The login repairs this account (#106): same id, its chats stay on it.
        onNodeWithTag("provider_login_cursor-work_sign_in").performClick()
        waitForIdle()
        assertEquals(listOf<Pair<String, String?>>("cursor" to null), started)
        onNodeWithTag("account-add-sheet").assertExists()
        onNodeWithText("Sign in to Work again").assertExists()
    }

    @Test fun removing_an_account_says_where_its_chats_go() = runComposeUiTest {
        providerPage(
            "cursor",
            AccountsActions(
                accounts = MutableStateFlow(listOf(cursorSystem, cursorWork)),
                removalPlan = { AccountRemovalPlanDto(it, 3, "cursor:system", "System login") },
            ),
        )
        waitForIdle()
        onNodeWithTag("provider_login_cursor-work").performClick()
        waitForIdle()
        onNodeWithTag("provider_login_cursor-work_remove").performClick()
        waitForIdle()
        onNodeWithText("Its 3 chats move to System login.", substring = true).assertExists()
    }
}
