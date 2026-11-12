// Small pieces the accounts surfaces share (a provider's logins, the add-a-login sheet, the run-settings picker and
// the transcript): the method and tag words, the usage meter and the account notice. Drawn the Settings kit's way
// (#97): words in a tone, hairlines, no chips or pills.
package dev.supermux.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountLoginStateDto
import dev.supermux.net.AccountRemovalPlanDto
import dev.supermux.net.AccountUsageWindowDto
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.usage.localizedResetIso
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.accounts_failure_already_added
import dev.supermux.ui.resources.accounts_failure_already_added_as
import dev.supermux.ui.resources.accounts_failure_already_yours
import dev.supermux.ui.resources.accounts_failure_cancelled
import dev.supermux.ui.resources.accounts_failure_timeout
import dev.supermux.ui.resources.accounts_hint_key_antigravity
import dev.supermux.ui.resources.accounts_hint_key_claude
import dev.supermux.ui.resources.accounts_hint_key_codex
import dev.supermux.ui.resources.accounts_hint_key_opencode
import dev.supermux.ui.resources.accounts_hint_key_other
import dev.supermux.ui.resources.accounts_hint_login_antigravity
import dev.supermux.ui.resources.accounts_hint_login_claude
import dev.supermux.ui.resources.accounts_hint_login_codex
import dev.supermux.ui.resources.accounts_hint_login_other
import dev.supermux.ui.resources.accounts_hint_token
import dev.supermux.ui.resources.accounts_method_helper
import dev.supermux.ui.resources.accounts_method_subscription
import dev.supermux.ui.resources.accounts_method_token
import dev.supermux.ui.resources.accounts_notice_exhausted
import dev.supermux.ui.resources.accounts_notice_reason_limit
import dev.supermux.ui.resources.accounts_notice_reason_manual
import dev.supermux.ui.resources.accounts_notice_reason_none
import dev.supermux.ui.resources.accounts_notice_switched
import dev.supermux.ui.resources.accounts_option_api_key
import dev.supermux.ui.resources.accounts_option_token
import dev.supermux.ui.resources.accounts_refused
import dev.supermux.ui.resources.accounts_refused_by_api
import dev.supermux.ui.resources.accounts_removal_chats
import dev.supermux.ui.resources.accounts_sign_in_expired
import dev.supermux.ui.resources.accounts_sign_in_failed
import dev.supermux.ui.resources.accounts_sign_in_failed_reason
import dev.supermux.ui.resources.accounts_sign_in_with
import dev.supermux.ui.resources.accounts_switch_busy
import dev.supermux.ui.resources.accounts_switch_failed
import dev.supermux.ui.resources.accounts_switch_not_running
import dev.supermux.ui.resources.accounts_switch_unknown
import dev.supermux.ui.resources.accounts_switch_unsupported
import dev.supermux.ui.resources.accounts_system_login
import dev.supermux.ui.resources.accounts_this_machines_login
import dev.supermux.ui.resources.usage_meter_percent
import dev.supermux.ui.resources.usage_window_daily
import dev.supermux.ui.resources.usage_window_hours
import dev.supermux.ui.resources.usage_window_minutes
import dev.supermux.ui.resources.usage_window_percent
import dev.supermux.ui.resources.usage_window_weekly

/** A tag ("Isolated", "Unavailable") as a word in its [tint], no pill. */
@Composable
internal fun AccountTag(text: String, tint: Color, modifier: Modifier = Modifier) {
    Text(text, fontSize = 11.5.sp, color = tint, maxLines = 1, modifier = modifier)
}

/** The method ("Subscription", "Token", "API key") as a dim word. */
@Composable
internal fun MethodChip(method: String, modifier: Modifier = Modifier) {
    val label = localizedMethodLabel(method) ?: return
    Text(label, fontSize = 11.5.sp, color = kitColors().dim, maxLines = 1, modifier = modifier)
}

/** Meter tint: the accent until the window is nearly spent. */
@Composable
internal fun usageTint(usedPercent: Double): Color {
    val c = kitColors()
    return when {
        usedPercent >= 95.0 -> c.danger
        usedPercent >= 80.0 -> c.warn
        else -> c.accent
    }
}

/** One window: "5h" · a 90×3 dp hairline meter · "62% · resets in 1h 24m". Fixed widths, so a list's meters line up. */
@Composable
internal fun UsageMeter(window: AccountUsageWindowDto, modifier: Modifier = Modifier) {
    val c = kitColors()
    val pct = window.usedPercent.coerceIn(0.0, 100.0)
    val tint = usageTint(pct)
    val name = localizedUsageWindowName(window.name)
    val reset = localizedResetIso(window.resetsAt)
    val percent = localized(Res.string.usage_meter_percent, kotlin.math.round(pct).toInt())
    val detail = if (reset.isNotBlank()) "$percent · $reset" else percent
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, fontSize = 11.5.sp, color = c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(52.dp))
        Box(Modifier.width(90.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((pct / 100.0).toFloat().coerceIn(0.02f, 1f)).background(tint))
        }
        Text(
            detail,
            fontSize = 11.5.sp,
            color = if (pct >= 80.0) tint else c.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The broker's account notice ("Switched to CI token — usage limit reached") as a quiet, centred
 * system row in the transcript — the closed-request receipt's tone, not an agent bubble.
 */
@Composable
fun AccountNoticeRow(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    // The broker's notice is English (isAccountNotice matches its exact shape): it is reworded here.
    val exhausted = text.startsWith("Usage limit reached") // i18n-ok: the broker's wire text
    val (lead, reason) = text.split(" — ", limit = 2).let { it[0] to it.getOrNull(1) }
    val name = lead.removePrefix(if (exhausted) "Usage limit reached on " else "Switched to ") // i18n-ok: the broker's wire text
    val sentence = localized(if (exhausted) Res.string.accounts_notice_exhausted else Res.string.accounts_notice_switched, name)
    val at = sentence.indexOf(name).takeIf { it >= 0 } ?: sentence.length
    val why = reason?.let { localizedNoticeReason(it) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        val pill = RoundedCornerShape(Radii.pill)
        Box(
            Modifier
                .widthIn(max = 520.dp)
                .clip(pill)
                .background(cs.surfaceContainerHigh.copy(alpha = 0.6f))
                .border(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.5f), pill)
                .testTag("account-notice"),
        ) {
            Row(
                Modifier.padding(start = Space.sm, end = Space.md, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (exhausted) Icons.Outlined.HourglassBottom else Icons.Filled.SwapHoriz,
                    contentDescription = null,
                    tint = if (exhausted) sem.warning else cs.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    buildAnnotatedString {
                        // The name in the sentence stands out; the words around it, wherever the language puts them, stay quiet.
                        withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(sentence.substring(0, at)) }
                        if (at < sentence.length) {
                            withStyle(SpanStyle(color = cs.onSurface, fontWeight = FontWeight.Medium)) { append(name) }
                            withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(sentence.substring(at + name.length)) }
                        }
                        if (!why.isNullOrBlank()) withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(" · $why") }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val NO_OTHER_ACCOUNT = Regex("""^no other (\S+) account is available$""")

/** The notice's reason, from the broker's English words to the app's language (unknown words kept as they came). */
@Composable
private fun localizedNoticeReason(reason: String): String {
    val other = NO_OTHER_ACCOUNT.find(reason)?.groupValues?.get(1)
    return when {
        reason == "usage limit reached" -> localized(Res.string.accounts_notice_reason_limit)
        reason == "switched manually" -> localized(Res.string.accounts_notice_reason_manual)
        other != null -> localized(Res.string.accounts_notice_reason_none, other)
        else -> reason
    }
}

// ── The model's words in the app's language (#123) ─────────────────────────────────────────────
// AccountsModel.kt keeps the English originals as the unit tests' twins; the UI reads these.

/** [addOptionTitle] in the app's language. */
@Composable
fun localizedAddOptionTitle(agent: String, option: AddAccountOption): String = when (option) {
    AddAccountOption.Login -> localized(
        Res.string.accounts_sign_in_with,
        when (agent) {
            "codex" -> "ChatGPT"
            "antigravity" -> "Google"
            else -> agentDisplayName(agent)
        },
    )
    AddAccountOption.Token -> localized(Res.string.accounts_option_token)
    AddAccountOption.ApiKey -> localized(Res.string.accounts_option_api_key)
}

/** [addOptionHint] in the app's language. */
@Composable
fun localizedAddOptionHint(agent: String, option: AddAccountOption): String = localized(
    when (option) {
        AddAccountOption.Login -> when (agent) {
            "codex" -> Res.string.accounts_hint_login_codex
            "claude" -> Res.string.accounts_hint_login_claude
            "antigravity" -> Res.string.accounts_hint_login_antigravity
            else -> Res.string.accounts_hint_login_other
        }
        AddAccountOption.Token -> Res.string.accounts_hint_token
        AddAccountOption.ApiKey -> when (agent) {
            "claude" -> Res.string.accounts_hint_key_claude
            "codex" -> Res.string.accounts_hint_key_codex
            "opencode" -> Res.string.accounts_hint_key_opencode
            "antigravity" -> Res.string.accounts_hint_key_antigravity
            else -> Res.string.accounts_hint_key_other
        }
    },
)

/** [methodLabel] in the app's language. */
@Composable
fun localizedMethodLabel(method: String): String? = when (method) {
    "subscription" -> localized(Res.string.accounts_method_subscription)
    "token" -> localized(Res.string.accounts_method_token)
    "api_key" -> localized(Res.string.accounts_option_api_key)
    "helper" -> localized(Res.string.accounts_method_helper)
    else -> null
}

/** [accountShortLabel] in the app's language. */
@Composable
fun localizedAccountShortLabel(account: AccountDto): String =
    if (account.system) localized(Res.string.accounts_system_login) else account.label.ifBlank { account.id }

/** [accountDisplayName] in the app's language. */
@Composable
fun localizedAccountDisplayName(account: AccountDto): String =
    if (account.system) localized(Res.string.accounts_system_login) else accountDisplayName(account)

/** [usageWindowName] in the app's language. */
@Composable
fun localizedUsageWindowName(name: String): String {
    val w = parseUsageWindowName(name)
    val base = when (w.kind) {
        UsageWindowLabel.Kind.Weekly -> localized(Res.string.usage_window_weekly)
        UsageWindowLabel.Kind.Daily -> localized(Res.string.usage_window_daily)
        UsageWindowLabel.Kind.Hours -> localized(Res.string.usage_window_hours, w.count)
        UsageWindowLabel.Kind.Minutes -> localized(Res.string.usage_window_minutes, w.count)
        UsageWindowLabel.Kind.Raw -> w.raw
    }
    return if (w.model != null) "$base ${w.model}" else base
}

/** [accountPickerDetail] in the app's language. */
@Composable
fun localizedAccountPickerDetail(account: AccountDto): String {
    val parts = mutableListOf<String>()
    if (account.system) {
        parts += account.identity?.email?.takeIf { it.isNotBlank() } ?: localized(Res.string.accounts_this_machines_login)
    } else {
        localizedMethodLabel(account.method)?.let { parts += it }
        account.identity?.email?.takeIf { it.isNotBlank() && it != account.label }?.let { parts += it }
    }
    account.usage.maxByOrNull { it.usedPercent }?.let {
        parts += localized(
            Res.string.usage_window_percent,
            localizedUsageWindowName(it.name),
            kotlin.math.round(it.usedPercent.coerceIn(0.0, 100.0)).toInt(),
        )
    }
    return parts.joinToString(" · ")
}

/** [accountUnavailableText] in the app's language (the API's own words stay as they came). */
@Composable
fun localizedAccountUnavailableText(account: AccountDto): String? {
    val u = account.unavailable ?: return null
    val reason = u.reason.trim()
    return when {
        u.code == SIGN_IN_EXPIRED -> localized(Res.string.accounts_sign_in_expired)
        reason.isNotBlank() -> localized(Res.string.accounts_refused, reason)
        else -> localized(Res.string.accounts_refused_by_api)
    }
}

/** [removalSentence] in the app's language. */
@Composable
fun localizedRemovalSentence(plan: AccountRemovalPlanDto?): String? =
    if (plan == null || plan.sessions <= 0) null
    else localizedPlural(Res.plurals.accounts_removal_chats, plan.sessions, plan.sessions, plan.toLabel)

/** [loginFailureText] in the app's language (the CLI's own message stays as it came). */
@Composable
fun localizedLoginFailureText(state: AccountLoginStateDto, agent: String, existing: List<AccountDto>): String {
    val error = state.error?.takeIf { it.isNotBlank() }
    return when (state.errorCode) {
        "account_exists" -> {
            val id = state.error?.let { COLLIDING_ID.find(it)?.groupValues?.get(1) }
            val other = existing.firstOrNull { it.id == id }
            when {
                id != null && isSystemAccountId(id) -> localized(Res.string.accounts_failure_already_yours, agentDisplayName(agent))
                other != null -> localized(Res.string.accounts_failure_already_added_as, localizedAccountShortLabel(other))
                else -> localized(Res.string.accounts_failure_already_added)
            }
        }
        "login_timeout" -> localized(Res.string.accounts_failure_timeout)
        "login_cancelled" -> localized(Res.string.accounts_failure_cancelled)
        "login_failed" -> if (error != null) localized(Res.string.accounts_sign_in_failed_reason, error) else localized(Res.string.accounts_sign_in_failed)
        else -> error ?: localized(Res.string.accounts_sign_in_failed)
    }
}

/** [sessionSwitchFailureText] in the app's language (the broker's own message stays as it came). */
@Composable
fun localizedSessionSwitchFailureText(code: String?, message: String?): String = when (code) {
    "session_busy" -> localized(Res.string.accounts_switch_busy)
    "session_not_running" -> localized(Res.string.accounts_switch_not_running)
    "unknown_account" -> localized(Res.string.accounts_switch_unknown)
    "account_unsupported" -> message ?: localized(Res.string.accounts_switch_unsupported)
    else -> message?.takeIf { it.isNotBlank() } ?: localized(Res.string.accounts_switch_failed)
}
