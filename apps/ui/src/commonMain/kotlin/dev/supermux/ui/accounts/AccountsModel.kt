// Accounts in the app UI (slice A3b): the pure half — what each agent offers, how an account and a
// usage window read, and how a guided login's phase maps to what the sheet shows. No Compose here,
// so all of it is unit-tested directly.
package dev.supermux.ui.accounts

import dev.supermux.net.AccountDto
import dev.supermux.net.AccountLoginStateDto
import dev.supermux.net.AccountUsageWindowDto
import dev.supermux.ui.usage.formatResetIso

/** The agents the broker keeps accounts for, in the order Settings lists them. */
val ACCOUNT_AGENTS = listOf("claude", "codex", "cursor", "grok", "opencode")

fun agentDisplayName(agent: String): String = when (agent) {
    "opencode" -> "OpenCode"
    else -> agent.replaceFirstChar { it.uppercase() }
}

/** The ways to add an account. */
enum class AddAccountOption { Login, Token, ApiKey }

/**
 * What [agent] supports (core adapters: claude api_key/token/subscription, codex api_key/token
 * (its login becomes a token account), cursor + grok api_key/subscription, opencode api_key).
 */
fun addAccountOptions(agent: String): List<AddAccountOption> = when (agent) {
    "claude" -> listOf(AddAccountOption.Login, AddAccountOption.Token, AddAccountOption.ApiKey)
    "codex", "cursor", "grok" -> listOf(AddAccountOption.Login, AddAccountOption.ApiKey)
    else -> listOf(AddAccountOption.ApiKey)
}

fun addOptionTitle(agent: String, option: AddAccountOption): String = when (option) {
    AddAccountOption.Login -> "Sign in with ${if (agent == "codex") "ChatGPT" else agentDisplayName(agent)}"
    AddAccountOption.Token -> "Paste a token"
    AddAccountOption.ApiKey -> "API key"
}

/** One line under each option. Codex's is the only explanation its sign-in gets. */
fun addOptionHint(agent: String, option: AddAccountOption): String = when (option) {
    AddAccountOption.Login -> when (agent) {
        "codex" -> "Becomes a token account that supermux keeps signed in."
        "claude" -> "Your Pro or Max subscription, signed in on this machine."
        else -> "A subscription login, kept on this machine."
    }
    AddAccountOption.Token -> "A long-lived token from claude setup-token."
    AddAccountOption.ApiKey -> when (agent) {
        "claude" -> "An Anthropic API key, billed per token."
        "codex" -> "An OpenAI API key, billed per token."
        "opencode" -> "A provider key for OpenCode."
        else -> "Billed per token."
    }
}

/** The method chip: Subscription / Token / API key (the system login gets none). */
fun methodLabel(method: String): String? = when (method) {
    "subscription" -> "Subscription"
    "token" -> "Token"
    "api_key" -> "API key"
    "helper" -> "Helper"
    else -> null
}

/** The account's name in a picker or pill. The system login reads as "This machine". */
fun accountShortLabel(account: AccountDto): String =
    if (account.system) "System login" else account.label.ifBlank { account.id }

/**
 * The second line of an account row: who it is signed in as. The system login says where it
 * lives; an added account shows its identity unless the label already is that identity.
 */
fun accountIdentityLine(account: AccountDto): String? {
    val email = account.identity?.email?.takeIf { it.isNotBlank() }
    if (account.system) {
        return if (email != null) "Signed in as $email · this machine's login" else "This machine's login"
    }
    val who = email ?: account.identity?.org?.takeIf { it.isNotBlank() }
    return who?.takeIf { it != account.label }
}

/** `five_hour` → `5h`, `seven_day` → `weekly`, Codex's `300m` / `10080m` likewise. */
fun usageWindowName(name: String): String {
    val n = name.lowercase()
    n.removeSuffix("m").toIntOrNull()?.takeIf { n.endsWith("m") }?.let { mins ->
        return when {
            mins == 10_080 -> "weekly"
            mins == 1_440 -> "daily"
            mins % 60 == 0 -> "${mins / 60}h"
            else -> "${mins}m"
        }
    }
    val base = when {
        n.startsWith("five_hour") -> "5h"
        n.startsWith("seven_day") -> "weekly"
        n.startsWith("one_day") || n.startsWith("daily") -> "daily"
        else -> return n.replace('_', ' ')
    }
    val model = n.substringAfter('_').substringAfter('_', "").takeIf { it.isNotBlank() }
    return if (model != null) "$base ${model.replace('_', ' ').replaceFirstChar { it.uppercase() }}" else base
}

/** "5h 62% · resets in 1h 24m". [now] is epoch millis (injected by tests). */
fun usageWindowText(window: AccountUsageWindowDto, now: Long? = null): String {
    val pct = window.usedPercent.coerceIn(0.0, 100.0).let { kotlin.math.round(it).toInt() }
    val reset = if (now != null) formatResetIso(window.resetsAt, now) else formatResetIso(window.resetsAt)
    return buildString {
        append(usageWindowName(window.name)).append(' ').append(pct).append('%')
        if (reset.isNotBlank()) append(" · ").append(reset)
    }
}

/** An agent's accounts in Settings order: the system login first, then by creation. */
fun accountsFor(all: List<AccountDto>, agent: String): List<AccountDto> =
    all.filter { it.agent == agent }.sortedWith(compareByDescending<AccountDto> { it.system }.thenBy { it.createdAt })

/** True when the account picker should be offered for [agent] (more than its system login). */
fun hasAccountChoice(all: List<AccountDto>?, agent: String): Boolean =
    (all ?: emptyList()).count { it.agent == agent } > 1

fun isSystemAccountId(id: String?): Boolean = id.isNullOrBlank() || id.endsWith(":system")

// ── guided login ───────────────────────────────────────────────────────────────────────────────

/** What the login sheet shows for one `account_login_state`. */
sealed interface LoginView {
    data object Starting : LoginView
    /** The user's part: open [url]; type [code] there (device logins); paste one back when [needsCode]. */
    data class AwaitingUser(val url: String?, val code: String?, val needsCode: Boolean) : LoginView
    data object Verifying : LoginView
    data class Done(val label: String) : LoginView
    data class Failed(val message: String) : LoginView
    data object Cancelled : LoginView
}

/**
 * Maps a login state to the sheet. [existing] are the agent's accounts, so an `account_exists`
 * failure can name the account it collides with.
 */
fun loginView(state: AccountLoginStateDto?, agent: String, existing: List<AccountDto> = emptyList()): LoginView =
    when (state?.phase) {
        null, "", "starting" -> LoginView.Starting
        "awaiting_user" -> LoginView.AwaitingUser(state.url, state.code?.takeIf { it.isNotBlank() }, state.needsCode)
        "verifying" -> LoginView.Verifying
        "done" -> LoginView.Done(state.account?.let { accountShortLabel(it) } ?: "the account")
        "cancelled" -> LoginView.Cancelled
        "failed" -> LoginView.Failed(loginFailureText(state, agent, existing))
        else -> LoginView.Starting
    }

private val COLLIDING_ID = Regex("""Account (\S+) is already""")

/** The failure in plain words (the CLI's own message for login_failed). */
fun loginFailureText(state: AccountLoginStateDto, agent: String, existing: List<AccountDto>): String {
    val name = agentDisplayName(agent)
    return when (state.errorCode) {
        "account_exists" -> {
            val id = state.error?.let { COLLIDING_ID.find(it)?.groupValues?.get(1) }
            val other = existing.firstOrNull { it.id == id }
            when {
                id != null && isSystemAccountId(id) -> "This is already your $name login on this machine."
                other != null -> "This login is already added as ${accountShortLabel(other)}."
                else -> "This login is already added."
            }
        }
        "login_timeout" -> "The sign-in wasn't finished in time. Try again when you're ready."
        "login_cancelled" -> "The sign-in was cancelled."
        "login_failed" -> state.error?.takeIf { it.isNotBlank() }?.let { "Sign-in failed: $it" } ?: "Sign-in failed."
        else -> state.error?.takeIf { it.isNotBlank() } ?: "Sign-in failed."
    }
}

/** The text for a refused session switch (POST /sessions/<id>/account). */
fun sessionSwitchFailureText(code: String?, message: String?): String = when (code) {
    "session_busy" -> "Busy right now — try again after this turn."
    "session_not_running" -> "Start the session first, then switch."
    "unknown_account" -> "That account no longer exists."
    "account_unsupported" -> message ?: "This account can't run sessions yet."
    else -> message?.takeIf { it.isNotBlank() } ?: "Couldn't switch account."
}

// ── chat notices ───────────────────────────────────────────────────────────────────────────────

private val SWITCH_NOTICE = Regex("""^Switched to .+ — (usage limit reached|switched manually)$""")
private val EXHAUSTED_NOTICE = Regex("""^Usage limit reached on .+ — no other \S+ account is available$""")

/**
 * The broker's account notices (A3a: `notifySession`). They arrive as ordinary outbound replies —
 * the entry carries no "system" flag — so the transcript recognises their exact shapes and draws
 * them as a quiet system row instead of an agent bubble.
 */
fun isAccountNotice(text: String?): Boolean {
    val t = text?.trim() ?: return false
    return SWITCH_NOTICE.matches(t) || EXHAUSTED_NOTICE.matches(t)
}
