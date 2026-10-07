package dev.supermux.ui.accounts

import dev.supermux.net.AccountDto
import dev.supermux.net.AccountIdentityDto
import dev.supermux.net.AccountLoginStateDto
import dev.supermux.net.AccountUsageWindowDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the accounts UI (slice A3b). */
class AccountsModelTest {
    private val system = AccountDto(id = "claude:system", agent = "claude", method = "system", label = "System login", system = true,
        identity = AccountIdentityDto(email = "me@x.dev"), createdAt = "1970-01-01T00:00:00.000Z")
    private val work = AccountDto(id = "claude-work", agent = "claude", method = "subscription", label = "Work",
        identity = AccountIdentityDto(email = "me@acme.dev"), createdAt = "2026-10-01T00:00:00.000Z")
    private val codexSystem = AccountDto(id = "codex:system", agent = "codex", method = "system", label = "System login", system = true)

    @Test fun options_follow_what_each_agent_supports() {
        assertEquals(listOf(AddAccountOption.Login, AddAccountOption.Token, AddAccountOption.ApiKey), addAccountOptions("claude"))
        assertEquals(listOf(AddAccountOption.Login, AddAccountOption.ApiKey), addAccountOptions("codex"))
        assertEquals(listOf(AddAccountOption.ApiKey), addAccountOptions("opencode"))
        assertEquals("Sign in with ChatGPT", addOptionTitle("codex", AddAccountOption.Login))
        assertTrue(addOptionHint("codex", AddAccountOption.Login).contains("token account"))
    }

    @Test fun identity_lines() {
        assertEquals("Signed in as me@x.dev · this machine's login", accountIdentityLine(system))
        assertEquals("This machine's login", accountIdentityLine(codexSystem))
        assertEquals("me@acme.dev", accountIdentityLine(work))
        // A label that already is the email is not repeated.
        assertNull(accountIdentityLine(work.copy(label = "me@acme.dev")))
    }

    @Test fun usage_windows_read_short() {
        assertEquals("5h", usageWindowName("five_hour"))
        assertEquals("weekly", usageWindowName("seven_day"))
        assertEquals("weekly Opus", usageWindowName("seven_day_opus"))
        assertEquals("5h", usageWindowName("300m"))
        assertEquals("weekly", usageWindowName("10080m"))
        val now = 1_000_000_000_000L
        val w = AccountUsageWindowDto("five_hour", 61.6, resetsAt = java.time.Instant.ofEpochMilli(now + 84 * 60_000L).toString())
        assertEquals("5h 62% · resets in 1h 24m", usageWindowText(w, now))
        assertEquals("weekly 18%", usageWindowText(AccountUsageWindowDto("seven_day", 18.0), now))
    }

    @Test fun ordering_and_choice() {
        val all = listOf(work, codexSystem, system)
        assertEquals(listOf("claude:system", "claude-work"), accountsFor(all, "claude").map { it.id })
        assertTrue(hasAccountChoice(all, "claude"))
        assertFalse(hasAccountChoice(all, "codex"))
        assertFalse(hasAccountChoice(null, "claude"))
    }

    @Test fun login_phases_map_to_views() {
        fun st(phase: String, vararg extra: Pair<String, Any?>) = AccountLoginStateDto(
            loginId = "l", agent = "claude", phase = phase,
            url = extra.toMap()["url"] as String?, code = extra.toMap()["code"] as String?,
            needsCode = extra.toMap()["needsCode"] as Boolean? ?: false,
            error = extra.toMap()["error"] as String?, errorCode = extra.toMap()["errorCode"] as String?,
            account = extra.toMap()["account"] as AccountDto?,
        )
        assertEquals(LoginView.Starting, loginView(null, "claude"))
        assertEquals(LoginView.Starting, loginView(st("starting"), "claude"))
        assertEquals(LoginView.AwaitingUser("https://u", null, true), loginView(st("awaiting_user", "url" to "https://u", "needsCode" to true), "claude"))
        assertEquals(LoginView.AwaitingUser("https://d", "AB-12", false), loginView(st("awaiting_user", "url" to "https://d", "code" to "AB-12"), "codex"))
        assertEquals(LoginView.Verifying, loginView(st("verifying"), "claude"))
        assertEquals(LoginView.Done("Work"), loginView(st("done", "account" to work), "claude"))
        assertEquals(
            LoginView.Failed("This is already your Claude login on this machine."),
            loginView(st("failed", "errorCode" to "account_exists", "error" to "Account claude:system is already logged in as this identity"), "claude"),
        )
        assertEquals(
            LoginView.Failed("This login is already added as Work."),
            loginView(st("failed", "errorCode" to "account_exists", "error" to "Account claude-work is already logged in as this identity"), "claude", listOf(system, work)),
        )
        assertEquals(LoginView.Failed("Sign-in failed: Login exited with code 1: boom"),
            loginView(st("failed", "errorCode" to "login_failed", "error" to "Login exited with code 1: boom"), "claude"))
        assertTrue((loginView(st("failed", "errorCode" to "login_timeout"), "claude") as LoginView.Failed).message.contains("in time"))
    }

    @Test fun switch_refusals_in_plain_words() {
        assertEquals("Busy right now — try again after this turn.", sessionSwitchFailureText("session_busy", "x"))
        assertEquals("Start the session first, then switch.", sessionSwitchFailureText("session_not_running", null))
        assertEquals("boom", sessionSwitchFailureText(null, "boom"))
    }

    @Test fun account_notices_are_recognised_by_their_exact_shape() {
        assertTrue(isAccountNotice("Switched to Work (Max) — usage limit reached"))
        assertTrue(isAccountNotice("Switched to System login — switched manually"))
        assertTrue(isAccountNotice("Usage limit reached on Work — no other claude account is available"))
        assertFalse(isAccountNotice("Switched to the new branch — done"))
        assertFalse(isAccountNotice("I switched to Work — usage limit reached"))
        assertFalse(isAccountNotice(null))
    }

    @Test fun picker_detail() {
        assertEquals("me@x.dev", accountPickerDetail(system))
        assertEquals("Subscription · me@acme.dev · 5h 62%", accountPickerDetail(work.copy(usage = listOf(AccountUsageWindowDto("five_hour", 62.0), AccountUsageWindowDto("seven_day", 10.0)))))
    }
}
