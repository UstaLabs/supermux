// Accounts in the app UI (slice A3b): the pure half — what each agent offers, how an account and a
// usage window read, and how a guided login's phase maps to what the sheet shows. No Compose here,
// so all of it is unit-tested directly. Its words are in the app's language (English in the tests).
package dev.supermux.ui.accounts

import dev.supermux.net.AccountDto
import dev.supermux.net.AccountRemovalPlanDto
import dev.supermux.net.AccountLoginStateDto
import dev.supermux.net.AccountUsageWindowDto
import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.usage.formatResetIso

/** The agents the broker keeps accounts for, in the order Settings lists them. */
val ACCOUNT_AGENTS = listOf("claude", "codex", "cursor", "grok", "opencode", "antigravity")

fun agentDisplayName(agent: String): String = when (agent) {
    "opencode" -> "OpenCode"
    else -> agent.replaceFirstChar { it.uppercase() }
}

/** The ways to add an account. */
enum class AddAccountOption { Login, Token, ApiKey }

/**
 * What [agent] supports (core adapters: claude api_key/token/subscription, codex api_key/token
 * (its login becomes a token account), cursor + grok + antigravity api_key/subscription,
 * opencode api_key).
 */
fun addAccountOptions(agent: String): List<AddAccountOption> = when (agent) {
    "claude" -> listOf(AddAccountOption.Login, AddAccountOption.Token, AddAccountOption.ApiKey)
    "codex", "cursor", "grok", "antigravity" -> listOf(AddAccountOption.Login, AddAccountOption.ApiKey)
    else -> listOf(AddAccountOption.ApiKey)
}

fun addOptionTitle(agent: String, option: AddAccountOption): String = when (option) {
    AddAccountOption.Login -> localized(
        Str.accounts_sign_in_with,
        when (agent) {
            "codex" -> "ChatGPT"
            "antigravity" -> "Google"
            else -> agentDisplayName(agent)
        },
    )
    AddAccountOption.Token -> localized(Str.accounts_option_token)
    AddAccountOption.ApiKey -> localized(Str.accounts_option_api_key)
}

/** One line under each option. Codex's is the only explanation its sign-in gets. */
fun addOptionHint(agent: String, option: AddAccountOption): String = localized(
    when (option) {
        AddAccountOption.Login -> when (agent) {
            "codex" -> Str.accounts_hint_login_codex
            "claude" -> Str.accounts_hint_login_claude
            "antigravity" -> Str.accounts_hint_login_antigravity
            else -> Str.accounts_hint_login_other
        }
        // The command itself is the next step's copyable line, the only command Settings shows (#97 spec §6).
        AddAccountOption.Token -> Str.accounts_hint_token
        AddAccountOption.ApiKey -> when (agent) {
            "claude" -> Str.accounts_hint_key_claude
            "codex" -> Str.accounts_hint_key_codex
            "opencode" -> Str.accounts_hint_key_opencode
            "antigravity" -> Str.accounts_hint_key_antigravity
            else -> Str.accounts_hint_key_other
        }
    },
)

/** The method chip: Subscription / Token / API key (the system login gets none). */
fun methodLabel(method: String): String? = when (method) {
    "subscription" -> localized(Str.accounts_method_subscription)
    "token" -> localized(Str.accounts_method_token)
    "api_key" -> localized(Str.accounts_option_api_key)
    "helper" -> localized(Str.accounts_method_helper)
    else -> null
}

/** The account's name in a picker or pill. The system login reads as "This machine". */
fun accountShortLabel(account: AccountDto): String =
    if (account.system) localized(Str.accounts_system_login) else account.label.ifBlank { account.id }

/**
 * A usage window's name before it is worded: weekly / daily, [count] hours or minutes, or the broker's [raw] name; with
 * Claude's [model] ("Opus") when the window is a model's own. [usageWindowName] words it.
 */
internal data class UsageWindowLabel(val kind: Kind, val count: Int = 0, val raw: String = "", val model: String? = null) {
    enum class Kind { Weekly, Daily, Hours, Minutes, Raw }
}

internal fun parseUsageWindowName(name: String): UsageWindowLabel {
    val n = name.lowercase()
    n.removeSuffix("m").toIntOrNull()?.takeIf { n.endsWith("m") }?.let { mins ->
        return when {
            mins == 10_080 -> UsageWindowLabel(UsageWindowLabel.Kind.Weekly)
            mins == 1_440 -> UsageWindowLabel(UsageWindowLabel.Kind.Daily)
            mins % 60 == 0 -> UsageWindowLabel(UsageWindowLabel.Kind.Hours, mins / 60)
            else -> UsageWindowLabel(UsageWindowLabel.Kind.Minutes, mins)
        }
    }
    val model = n.substringAfter('_').substringAfter('_', "").takeIf { it.isNotBlank() }
        ?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
    return when {
        n.startsWith("five_hour") -> UsageWindowLabel(UsageWindowLabel.Kind.Hours, 5, model = model)
        n.startsWith("seven_day") -> UsageWindowLabel(UsageWindowLabel.Kind.Weekly, model = model)
        n.startsWith("one_day") || n.startsWith("daily") -> UsageWindowLabel(UsageWindowLabel.Kind.Daily, model = model)
        else -> UsageWindowLabel(UsageWindowLabel.Kind.Raw, raw = n.replace('_', ' '))
    }
}

/** `five_hour` → `5h`, `seven_day` → `weekly`, Codex's `300m` / `10080m` likewise. */
fun usageWindowName(name: String): String {
    val w = parseUsageWindowName(name)
    val base = when (w.kind) {
        UsageWindowLabel.Kind.Weekly -> localized(Str.usage_window_weekly)
        UsageWindowLabel.Kind.Daily -> localized(Str.usage_window_daily)
        UsageWindowLabel.Kind.Hours -> localized(Str.usage_window_hours, w.count)
        UsageWindowLabel.Kind.Minutes -> localized(Str.usage_window_minutes, w.count)
        UsageWindowLabel.Kind.Raw -> w.raw
    }
    return if (w.model != null) "$base ${w.model}" else base
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

/** "Subscription · 5h 62%" — the second line of an account in a picker (the busiest window only). */
internal fun accountPickerDetail(account: AccountDto): String {
    val parts = mutableListOf<String>()
    if (account.system) {
        parts += account.identity?.email?.takeIf { it.isNotBlank() } ?: localized(Str.accounts_this_machines_login)
    } else {
        methodLabel(account.method)?.let { parts += it }
        account.identity?.email?.takeIf { it.isNotBlank() && it != account.label }?.let { parts += it }
    }
    account.usage.maxByOrNull { it.usedPercent }?.let {
        parts += localized(Str.usage_window_percent, usageWindowName(it.name), kotlin.math.round(it.usedPercent.coerceIn(0.0, 100.0)).toInt())
    }
    return parts.joinToString(" · ")
}

/** An agent's accounts in Settings order: the system login first, then by creation. */
fun accountsFor(all: List<AccountDto>, agent: String): List<AccountDto> =
    all.filter { it.agent == agent }.sortedWith(compareByDescending<AccountDto> { it.system }.thenBy { it.createdAt })

/** True when the account picker should be offered for [agent] (more than its system login). */
fun hasAccountChoice(all: List<AccountDto>?, agent: String): Boolean =
    (all ?: emptyList()).count { it.agent == agent } > 1

fun isSystemAccountId(id: String?): Boolean = id.isNullOrBlank() || id.endsWith(":system")

/** The code of a mark (and of a usage error) the broker gives a login its provider refused: sign in again. */
const val SIGN_IN_EXPIRED = "account_expired"

/** Whether [account] is marked unavailable because its sign-in expired (or was revoked). */
fun signInExpired(account: AccountDto): Boolean = account.unavailable?.code == SIGN_IN_EXPIRED

/** Why an account is marked unavailable (null when it is not): "Sign-in expired", or "Refused: <the API's words>". */
fun accountUnavailableText(account: AccountDto): String? {
    val u = account.unavailable ?: return null
    val reason = u.reason.trim()
    return when {
        u.code == SIGN_IN_EXPIRED -> localized(Str.accounts_sign_in_expired)
        reason.isNotBlank() -> localized(Str.accounts_refused, reason)
        else -> localized(Str.accounts_refused_by_api)
    }
}

/** The email a "Sign in again" login asks for (#106): Claude's login takes one, so it signs in as this account. */
fun signInAgainEmail(account: AccountDto): String? =
    account.identity?.email?.takeIf { account.agent == "claude" && it.isNotBlank() }

/** What a removal does to the account's chats (#106), from GET /accounts/<id>/removal; null when it moves none. */
fun removalSentence(plan: AccountRemovalPlanDto?): String? =
    if (plan == null || plan.sessions <= 0) null
    else localizedPlural(Plurals.accounts_removal_chats, plan.sessions, plan.sessions, plan.toLabel)

/** An account's name in a sentence: the user's label, else the signed-in email, else its id. */
fun accountDisplayName(account: AccountDto): String =
    if (account.system) localized(Str.accounts_system_login) else account.label.ifBlank { account.identity?.email?.takeIf { it.isNotBlank() } ?: account.id }

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
        "done" -> LoginView.Done(state.account?.let { accountShortLabel(it) } ?: localized(Str.accounts_the_account))
        "cancelled" -> LoginView.Cancelled
        "failed" -> LoginView.Failed(loginFailureText(state, agent, existing))
        else -> LoginView.Starting
    }

internal val COLLIDING_ID = Regex("""Account (\S+) is already""")

/** The failure in plain words (the CLI's own message for login_failed). */
fun loginFailureText(state: AccountLoginStateDto, agent: String, existing: List<AccountDto>): String {
    val error = state.error?.takeIf { it.isNotBlank() }
    return when (state.errorCode) {
        "account_exists" -> {
            val id = state.error?.let { COLLIDING_ID.find(it)?.groupValues?.get(1) }
            val other = existing.firstOrNull { it.id == id }
            when {
                id != null && isSystemAccountId(id) -> localized(Str.accounts_failure_already_yours, agentDisplayName(agent))
                other != null -> localized(Str.accounts_failure_already_added_as, accountShortLabel(other))
                else -> localized(Str.accounts_failure_already_added)
            }
        }
        "login_timeout" -> localized(Str.accounts_failure_timeout)
        "login_cancelled" -> localized(Str.accounts_failure_cancelled)
        "login_failed" -> if (error != null) localized(Str.accounts_sign_in_failed_reason, error) else localized(Str.accounts_sign_in_failed)
        else -> error ?: localized(Str.accounts_sign_in_failed)
    }
}

/** The text for a refused session switch (POST /sessions/<id>/account). */
fun sessionSwitchFailureText(code: String?, message: String?): String = when (code) {
    "session_busy" -> localized(Str.accounts_switch_busy)
    "session_not_running" -> localized(Str.accounts_switch_not_running)
    "unknown_account" -> localized(Str.accounts_switch_unknown)
    "account_unsupported" -> message ?: localized(Str.accounts_switch_unsupported)
    else -> message?.takeIf { it.isNotBlank() } ?: localized(Str.accounts_switch_failed)
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
