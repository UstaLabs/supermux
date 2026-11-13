package dev.supermux.ui.accounts

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountIdentityDto
import dev.supermux.net.AccountLoginStateDto
import dev.supermux.net.AccountRemovalPlanDto
import dev.supermux.net.AccountUnavailableDto
import dev.supermux.state.AccountResult
import dev.supermux.ui.chat.setPlatformContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A provider page's logins (#97's layout, #106's signed-out state): the provider's own login (#97's slot), the added ones, "+ Add a login". */
@OptIn(ExperimentalTestApi::class)
class ProviderLoginsTest {
    private val expired = AccountUnavailableDto("OAuth session expired and could not be refreshed", "2026-11-07T20:44:00.000Z", code = "account_expired")
    private val system = AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true,
        identity = AccountIdentityDto(email = "ahmet@supermux.dev"))
    private val four = AccountDto(id = "claude-4", agent = "claude", method = "subscription", identity = AccountIdentityDto(email = "4@supermux.dev"))
    private val support = AccountDto(id = "claude-s", agent = "claude", method = "subscription", identity = AccountIdentityDto(email = "support@supermux.dev"),
        unavailable = expired)

    @Test fun your_login_comes_first_with_its_extra_then_the_added_ones_then_add() = runComposeUiTest {
        setPlatformContent { ProviderLogins("claude", listOf(system, four), AccountsActions(), onSignInSystem = {}, systemLoginExtra = { Text("Use a token instead") }) }
        waitForIdle()
        onNodeWithText("Your login").assertExists()
        onNodeWithText("ahmet@supermux.dev").assertExists()
        assertTrue(onNodeWithText("Use a token instead").getBoundsInRoot().top >= onNodeWithText("Your login").getBoundsInRoot().bottom)
        assertTrue(onNodeWithText("4@supermux.dev").getBoundsInRoot().top >= onNodeWithText("Use a token instead").getBoundsInRoot().bottom)
        onNodeWithText("4@supermux.dev").assertExists()
        onNodeWithText("Add a login").assertExists()
        onNodeWithText("Sign-in expired").assertDoesNotExist()
        // The provider's own login is never removable.
        onNodeWithTag("provider_login_claude:system").performClick()
        waitForIdle()
        onNodeWithTag("provider_login_claude:system_remove").assertDoesNotExist()
    }

    @Test fun opencode_has_no_add_a_login() = runComposeUiTest {
        setPlatformContent { ProviderLogins("opencode", emptyList(), AccountsActions(), onSignInSystem = {}) }
        waitForIdle()
        onNodeWithText("Add a login").assertDoesNotExist()
    }

    @Test fun an_expired_login_says_so_and_signs_in_again_as_its_email() = runComposeUiTest {
        val started = mutableListOf<Pair<String, String?>>()
        val actions = AccountsActions(
            startLogin = { agent, email -> started += agent to email; AccountResult.Ok(AccountLoginStateDto(loginId = "l1", agent = agent, phase = "starting")) },
        )
        setPlatformContent { ProviderLogins("claude", listOf(system, support), actions, onSignInSystem = {}) }
        waitForIdle()
        onNodeWithText("Sign-in expired").assertExists()
        onNodeWithTag("provider_login_claude-s_sign_in").performClick()
        waitForIdle()
        assertEquals(listOf<Pair<String, String?>>("claude" to "support@supermux.dev"), started)
        onNodeWithText("Sign in to support@supermux.dev again").assertExists()
    }

    @Test fun an_expired_own_login_signs_in_through_the_providers_flow() = runComposeUiTest {
        var signIns = 0
        setPlatformContent { ProviderLogins("claude", listOf(system.copy(unavailable = expired)), AccountsActions(), onSignInSystem = { signIns++ }) }
        waitForIdle()
        onNodeWithText("Sign-in expired").assertExists()
        onNodeWithTag("provider_login_claude:system_sign_in").performClick()
        assertEquals(1, signIns)
    }

    @Test fun removing_a_login_says_where_its_chats_go_then_removes_it() = runComposeUiTest {
        val removed = mutableListOf<String>()
        val actions = AccountsActions(
            removalPlan = { AccountRemovalPlanDto(it, 3, "claude-4", "4@supermux.dev") },
            removeAccount = { id, _ -> removed += id; AccountResult.Ok(Unit) },
        )
        setPlatformContent { ProviderLogins("claude", listOf(system, four, support), actions, onSignInSystem = {}) }
        waitForIdle()
        onNodeWithTag("provider_login_claude-s").performClick()
        waitForIdle()
        onNodeWithTag("provider_login_claude-s_remove").performClick()
        waitForIdle()
        onNodeWithText("Its 3 chats move to 4@supermux.dev.", substring = true).assertExists()
        onNodeWithTag("provider_login_remove_confirm").performClick()
        waitForIdle()
        assertEquals(listOf("claude-s"), removed)
    }
}
