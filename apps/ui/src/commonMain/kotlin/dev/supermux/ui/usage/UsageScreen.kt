// The one Usage screen for both apps (cluster E6).
//
// Base = desktop's `usage/UsageScreen.kt`: the typed `UsageResponse` DTOs, the per-provider
// "as of …" caption, the per-provider refreshing spinner, the opencode card, the clamped
// percentage, and the redeem confirm whose note survives the card's numbers changing under it.
// Android's `MoreScreens.kt` Usage section contributes the Compact branch — its `TopAppBar` with
// Back + Refresh — and, with this task, LOSES its hand-written JSON parser: Android's `parseUsage`,
// its nine private data classes and the `usageRaw()` wrapper that fed them are gone; the shared
// typed DTOs already carry every field they had. Android's post-redeem re-fetch survives too: the
// stateful overload wires `onRedeemed` to [UsageActions.load], so the providers the in-place Codex
// swap does not touch still pick up their post-redeem numbers.
//
// Time: `java.time` cannot come into `:ui` commonMain, so the formatters take epoch millis and the
// only calendar step — "Jul 14" in the user's zone — is `:shared`'s [shortMonthDayLabel], which is
// the same civil-date maths chat timestamps already use. Number formatting is likewise pure
// (`String.format(Locale.US, …)` is a JVM call).
package dev.supermux.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.AccountUsage
import dev.supermux.net.AntigravityUsage
import dev.supermux.net.ClaudeResetResult
import dev.supermux.net.ClaudeResets
import dev.supermux.net.ClaudeUsage
import dev.supermux.net.CodexModelUsage
import dev.supermux.net.CodexResetResult
import dev.supermux.net.CodexUsage
import dev.supermux.net.CursorUsage
import dev.supermux.net.GrokUsage
import dev.supermux.chat.localUtcOffsetMs
import dev.supermux.net.OpenCodeGoMeter
import dev.supermux.net.OpenCodePlan
import dev.supermux.net.OpenCodeUsage
import dev.supermux.net.UsageResponse
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.chat.parseChatTs
import dev.supermux.ui.accounts.SIGN_IN_EXPIRED
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.util.shortMonthDayLabel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Clock
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.accounts_option_api_key
import dev.supermux.ui.resources.accounts_system_login
import dev.supermux.ui.resources.usage_activity
import dev.supermux.ui.resources.usage_activity_value
import dev.supermux.ui.resources.usage_antigravity_no_limits
import dev.supermux.ui.resources.usage_as_of_days
import dev.supermux.ui.resources.usage_as_of_hours
import dev.supermux.ui.resources.usage_as_of_minutes
import dev.supermux.ui.resources.usage_as_of_now
import dev.supermux.ui.resources.usage_back
import dev.supermux.ui.resources.usage_billed_per_token
import dev.supermux.ui.resources.usage_billing_cycle
import dev.supermux.ui.resources.usage_cache_read
import dev.supermux.ui.resources.usage_cache_write
import dev.supermux.ui.resources.usage_cancel
import dev.supermux.ui.resources.usage_claude_already_used
import dev.supermux.ui.resources.usage_claude_cooldown
import dev.supermux.ui.resources.usage_claude_no_reset
import dev.supermux.ui.resources.usage_claude_not_limited
import dev.supermux.ui.resources.usage_claude_redeem_text
import dev.supermux.ui.resources.usage_claude_reset_done
import dev.supermux.ui.resources.usage_claude_reset_done_left
import dev.supermux.ui.resources.usage_claude_unavailable
import dev.supermux.ui.resources.usage_clears_five_hour
import dev.supermux.ui.resources.usage_clears_many
import dev.supermux.ui.resources.usage_clears_none
import dev.supermux.ui.resources.usage_clears_one
import dev.supermux.ui.resources.usage_clears_seven_day
import dev.supermux.ui.resources.usage_clears_seven_day_opus
import dev.supermux.ui.resources.usage_clears_seven_day_sonnet
import dev.supermux.ui.resources.usage_close
import dev.supermux.ui.resources.usage_codex_already_redeemed
import dev.supermux.ui.resources.usage_codex_no_credit
import dev.supermux.ui.resources.usage_codex_nothing
import dev.supermux.ui.resources.usage_codex_redeem_text
import dev.supermux.ui.resources.usage_codex_reset_done
import dev.supermux.ui.resources.usage_credits
import dev.supermux.ui.resources.usage_credits_amount
import dev.supermux.ui.resources.usage_credits_balance
import dev.supermux.ui.resources.usage_daily_limit
import dev.supermux.ui.resources.usage_extra_usage
import dev.supermux.ui.resources.usage_five_hour_window
import dev.supermux.ui.resources.usage_five_hours
import dev.supermux.ui.resources.usage_free_models
import dev.supermux.ui.resources.usage_google_account
import dev.supermux.ui.resources.usage_grok_monthly_credits
import dev.supermux.ui.resources.usage_grok_weekly_credits
import dev.supermux.ui.resources.usage_input
import dev.supermux.ui.resources.usage_limit_reached
import dev.supermux.ui.resources.usage_limit_reached_badge
import dev.supermux.ui.resources.usage_load_failed
import dev.supermux.ui.resources.usage_local_all_time
import dev.supermux.ui.resources.usage_local_spend
import dev.supermux.ui.resources.usage_model_available
import dev.supermux.ui.resources.usage_model_back_in_hm
import dev.supermux.ui.resources.usage_model_back_in_m
import dev.supermux.ui.resources.usage_model_back_on
import dev.supermux.ui.resources.usage_model_back_soon
import dev.supermux.ui.resources.usage_model_locked
import dev.supermux.ui.resources.usage_model_locked_credits
import dev.supermux.ui.resources.usage_monthly
import dev.supermux.ui.resources.usage_no_limits
import dev.supermux.ui.resources.usage_not_available
import dev.supermux.ui.resources.usage_on_demand
import dev.supermux.ui.resources.usage_opencode_expired
import dev.supermux.ui.resources.usage_opencode_no_plan
import dev.supermux.ui.resources.usage_opencode_read_failed
import dev.supermux.ui.resources.usage_opencode_read_failed_reason
import dev.supermux.ui.resources.usage_opencode_signed_out
import dev.supermux.ui.resources.usage_output
import dev.supermux.ui.resources.usage_percent_used
import dev.supermux.ui.resources.usage_prepaid_balance
import dev.supermux.ui.resources.usage_pro_plan
import dev.supermux.ui.resources.usage_redeem_title
import dev.supermux.ui.resources.usage_redeeming
import dev.supermux.ui.resources.usage_refresh
import dev.supermux.ui.resources.usage_reset_completed
import dev.supermux.ui.resources.usage_reset_failed
import dev.supermux.ui.resources.usage_reset_needs_limit
import dev.supermux.ui.resources.usage_reset_not_now
import dev.supermux.ui.resources.usage_reset_paused
import dev.supermux.ui.resources.usage_reset_try_minute
import dev.supermux.ui.resources.usage_resets_banked
import dev.supermux.ui.resources.usage_resets_in_hm
import dev.supermux.ui.resources.usage_resets_in_m
import dev.supermux.ui.resources.usage_resets_on
import dev.supermux.ui.resources.usage_resets_soon
import dev.supermux.ui.resources.usage_resets_tomorrow
import dev.supermux.ui.resources.usage_resets_value_by
import dev.supermux.ui.resources.usage_seven_day_fable
import dev.supermux.ui.resources.usage_seven_day_sonnet
import dev.supermux.ui.resources.usage_seven_day_window
import dev.supermux.ui.resources.usage_sign_in
import dev.supermux.ui.resources.usage_sign_in_again
import dev.supermux.ui.resources.usage_sign_in_expired
import dev.supermux.ui.resources.usage_spend
import dev.supermux.ui.resources.usage_spend_included
import dev.supermux.ui.resources.usage_system_login_only
import dev.supermux.ui.resources.usage_title
import dev.supermux.ui.resources.usage_unknown
import dev.supermux.ui.resources.usage_usage
import dev.supermux.ui.resources.usage_use_a_reset
import dev.supermux.ui.resources.usage_use_reset
import dev.supermux.ui.resources.usage_weekly
import dev.supermux.ui.resources.usage_window
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural

// ─── Reset formatting (pure) ────────────────────────────────────────────────────────────────────

private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_HOUR = 3_600_000L
private const val MS_PER_DAY = 24 * MS_PER_HOUR

/** Material's minimum touch target, applied to the header actions when there is no pointer. */
private val TouchTargetMin = 48.dp

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

// The pure formatters below word in English: the tests' twins (#123). The cards read each through its composable twin
// (localizedResetIso, localizedCodexModelStatus …), which words the same parts in the app's language.

/**
 * Claude windows (`ClaudeWindow.resetsAt`) + Cursor's `billingCycleEnd`: an ISO-8601 string
 * (both apps also fall back to a numeric epoch-millis string first). [now] is epoch millis,
 * injected for deterministic tests.
 */
fun formatResetIso(resetsAt: String?, now: Long = nowMs()): String = englishReset(resetTimeIso(resetsAt, now))

/** Codex windows (`CodexWindow.resetsAt`): a Double of epoch SECONDS. */
fun formatResetEpochSeconds(resetsAt: Double?, now: Long = nowMs()): String = englishReset(resetTimeEpochSeconds(resetsAt, now))

/** [formatResetIso] in the app's language. */
@Composable
fun localizedResetIso(resetsAt: String?, now: Long = nowMs()): String = localizedReset(resetTimeIso(resetsAt, now))

/** [formatResetEpochSeconds] in the app's language. */
@Composable
fun localizedResetEpochSeconds(resetsAt: Double?, now: Long = nowMs()): String = localizedReset(resetTimeEpochSeconds(resetsAt, now))

/**
 * Status line for one Codex per-model gate. A locked model with a known unlock time reads
 * "locked · back in 3h 12m"; without one, whether buying credits would lift the gate.
 */
fun codexModelStatus(model: CodexModelUsage, now: Long = nowMs()): String {
    if (model.available) return "available"
    val back = formatResetEpochSeconds(model.availableAt, now).removePrefix("resets ")
    return when {
        back.isNotEmpty() -> "locked · back $back"
        model.creditsWouldEnable -> "locked · credits would unlock" // i18n-ok: test twin
        else -> "locked"
    }
}

/** [codexModelStatus] in the app's language. */
@Composable
fun localizedCodexModelStatus(model: CodexModelUsage, now: Long = nowMs()): String = when {
    model.available -> localized(Res.string.usage_model_available)
    else -> when (val back = resetTimeEpochSeconds(model.availableAt, now)) {
        ResetTime.Soon -> localized(Res.string.usage_model_back_soon)
        is ResetTime.In ->
            if (back.hours > 0) localized(Res.string.usage_model_back_in_hm, back.hours, back.minutes)
            else localized(Res.string.usage_model_back_in_m, back.minutes)
        is ResetTime.On -> localized(Res.string.usage_model_back_on, back.day)
        null ->
            if (model.creditsWouldEnable) localized(Res.string.usage_model_locked_credits)
            else localized(Res.string.usage_model_locked)
    }
}

/** The use-by date of the grant a redeem would spend (or the first grant with resets left); "" when unknown. */
private fun claudeResetsUseBy(resets: ClaudeResets): String {
    val grant = resets.grants.firstOrNull { it.id == resets.nextGrantId }
        ?: resets.grants.firstOrNull { it.resetsLeft > 0 }
    val endsMs = grant?.endsAtIso?.let { parseIsoMillis(it) }
    return endsMs?.let { shortMonthDayLabel(it) }.orEmpty()
}

/**
 * Value of the Claude card's "Resets banked" row: the count, plus the use-by date of the grant a
 * redeem would spend (or the first grant with resets left). "1 · use by Oct 22".
 */
fun claudeResetsValue(resets: ClaudeResets): String {
    val by = claudeResetsUseBy(resets)
    return if (by.isEmpty()) "${resets.resetsLeft}" else "${resets.resetsLeft} · use by $by" // i18n-ok: test twin
}

/** [claudeResetsValue] in the app's language. */
@Composable
fun localizedClaudeResetsValue(resets: ClaudeResets): String {
    val by = claudeResetsUseBy(resets)
    return if (by.isEmpty()) "${resets.resetsLeft}" else localized(Res.string.usage_resets_value_by, resets.resetsLeft, by)
}

/** Why a banked Claude reset can't be spent right now (see [claudeResetBlockedHint]). */
internal enum class ResetBlocked { Paused, Cooldown, NeedsLimit, NotNow }

internal fun claudeResetBlocked(resets: ClaudeResets): ResetBlocked? {
    if (resets.nextGrantId != null) return null
    val live = resets.grants.filter { it.resetsLeft > 0 }
    return when {
        live.isEmpty() -> null
        live.all { it.paused } -> ResetBlocked.Paused
        resets.cooldownUntilIso != null -> ResetBlocked.Cooldown
        live.all { it.useRequiresLimit } && !resets.atLimit -> ResetBlocked.NeedsLimit
        else -> ResetBlocked.NotNow
    }
}

/**
 * Why a banked Claude reset can't be spent right now, or null when it can ([ClaudeResets.nextGrantId]
 * set). Shown under the row in place of the button.
 */
fun claudeResetBlockedHint(resets: ClaudeResets): String? = when (claudeResetBlocked(resets)) {
    null -> null
    ResetBlocked.Paused -> "Reset paused" // i18n-ok: test twin
    ResetBlocked.Cooldown -> "Try again in a minute" // i18n-ok: test twin
    ResetBlocked.NeedsLimit -> "Usable once you hit a limit" // i18n-ok: test twin
    ResetBlocked.NotNow -> "Not usable right now" // i18n-ok: test twin
}

/** [claudeResetBlockedHint] in the app's language. */
@Composable
fun localizedClaudeResetBlockedHint(resets: ClaudeResets): String? = when (claudeResetBlocked(resets)) {
    null -> null
    ResetBlocked.Paused -> localized(Res.string.usage_reset_paused)
    ResetBlocked.Cooldown -> localized(Res.string.usage_reset_try_minute)
    ResetBlocked.NeedsLimit -> localized(Res.string.usage_reset_needs_limit)
    ResetBlocked.NotNow -> localized(Res.string.usage_reset_not_now)
}

/** The windows a Claude reset grant clears, in order; unknown window ids are left out. */
private enum class ClearedWindow { FiveHour, SevenDay, SevenDaySonnet, SevenDayOpus }

private fun clearedWindows(clears: List<String>): List<ClearedWindow> = clears.mapNotNull {
    when (it) {
        "five_hour" -> ClearedWindow.FiveHour
        "seven_day" -> ClearedWindow.SevenDay
        "seven_day_sonnet" -> ClearedWindow.SevenDaySonnet
        "seven_day_opus" -> ClearedWindow.SevenDayOpus
        else -> null
    }
}

/** "5-hour and 7-day limits" from a grant's `clears`; unknown window ids are left out. */
fun claudeResetClears(clears: List<String>): String {
    val names = clearedWindows(clears).map {
        when (it) {
            ClearedWindow.FiveHour -> "5-hour"
            ClearedWindow.SevenDay -> "7-day"
            ClearedWindow.SevenDaySonnet -> "7-day Sonnet" // i18n-ok: test twin
            ClearedWindow.SevenDayOpus -> "7-day Opus" // i18n-ok: test twin
        }
    }
    return when (names.size) {
        0 -> "usage limits"
        1 -> "${names[0]} limit" // i18n-ok: test twin
        else -> names.dropLast(1).joinToString(", ") + " and ${names.last()} limits"
    }
}

/** [claudeResetClears] in the app's language. */
@Composable
private fun localizedClaudeResetClears(clears: List<String>): String {
    val names = clearedWindows(clears).map {
        when (it) {
            ClearedWindow.FiveHour -> localized(Res.string.usage_clears_five_hour)
            ClearedWindow.SevenDay -> localized(Res.string.usage_clears_seven_day)
            ClearedWindow.SevenDaySonnet -> localized(Res.string.usage_clears_seven_day_sonnet)
            ClearedWindow.SevenDayOpus -> localized(Res.string.usage_clears_seven_day_opus)
        }
    }
    return when (names.size) {
        0 -> localized(Res.string.usage_clears_none)
        1 -> localized(Res.string.usage_clears_one, names[0])
        else -> localized(Res.string.usage_clears_many, names.dropLast(1).joinToString(", "), names.last())
    }
}

/**
 * Label for Grok's quota window. Unified billing bills weekly; older accounts stay monthly,
 * and a broker that predates the field sends "monthly".
 */
fun grokPeriodLabel(periodType: String): String = when (periodType) {
    "weekly" -> "Weekly credits" // i18n-ok: test twin
    "monthly" -> "Monthly credits" // i18n-ok: test twin
    else -> "Credits"
}

/** [grokPeriodLabel] in the app's language. */
@Composable
fun localizedGrokPeriodLabel(periodType: String): String = when (periodType) {
    "weekly" -> localized(Res.string.usage_grok_weekly_credits)
    "monthly" -> localized(Res.string.usage_grok_monthly_credits)
    else -> localized(Res.string.usage_credits)
}

/** Seconds since [fetchedAt] (ISO-8601 or epoch millis), never negative; null when missing or unparseable. */
private fun fetchedAgoSeconds(fetchedAt: String?, now: Long): Long? {
    val s = fetchedAt?.takeIf { it.isNotBlank() } ?: return null
    val ms = s.toLongOrNull() ?: parseIsoMillis(s) ?: return null
    return ((now - ms) / 1000L).coerceAtLeast(0L)
}

/**
 * Per-provider "as of <relative>" caption from [UsageResponse.fetchedAt] (ISO-8601, or a
 * numeric epoch-millis string). Blank when missing/unparseable — the card then omits the line.
 */
fun formatFetchedAt(fetchedAt: String?, now: Long = nowMs()): String {
    val diffSec = fetchedAgoSeconds(fetchedAt, now) ?: return ""
    return when {
        diffSec < 60L -> "as of just now"
        diffSec < 3600L -> "as of ${diffSec / 60}m ago"
        diffSec < 86_400L -> "as of ${diffSec / 3600}h ago"
        else -> "as of ${diffSec / 86_400}d ago"
    }
}

/** [formatFetchedAt] in the app's language. */
@Composable
fun localizedFetchedAt(fetchedAt: String?, now: Long = nowMs()): String {
    val diffSec = fetchedAgoSeconds(fetchedAt, now) ?: return ""
    return when {
        diffSec < 60L -> localized(Res.string.usage_as_of_now)
        diffSec < 3600L -> localized(Res.string.usage_as_of_minutes, diffSec / 60)
        diffSec < 86_400L -> localized(Res.string.usage_as_of_hours, diffSec / 3600)
        else -> localized(Res.string.usage_as_of_days, diffSec / 86_400)
    }
}

/**
 * ISO-8601 → epoch millis, the shared parser both apps' chat timestamps use. `java.time`'s
 * `Instant.parse` is not available here; [parseChatTs] accepts the same shapes (and, additionally,
 * a bare epoch — harmless, since the callers try `toLongOrNull()` first anyway).
 */
private fun parseIsoMillis(s: String): Long? = parseChatTs(s)

/** When a window resets, before it is worded: soon, in [In.hours] h [In.minutes] m, or on a calendar [On.day]. */
internal sealed interface ResetTime {
    data object Soon : ResetTime
    data class In(val hours: Int, val minutes: Int) : ResetTime
    data class On(val day: String) : ResetTime
}

/** Null when there is nothing to say (no time, or a date that can't be labelled). */
private fun resetTimeFromEpochMillis(ms: Long, now: Long): ResetTime? {
    val diff = ms - now
    if (diff <= 0) return ResetTime.Soon
    if (diff < MS_PER_DAY) {
        return ResetTime.In((diff / MS_PER_HOUR).toInt(), ((diff % MS_PER_HOUR) / MS_PER_MINUTE).toInt())
    }
    val label = shortMonthDayLabel(ms)
    return if (label.isEmpty()) null else ResetTime.On(label)
}

internal fun resetTimeIso(resetsAt: String?, now: Long): ResetTime? {
    val s = resetsAt?.takeIf { it.isNotBlank() } ?: return null
    val ms = s.toLongOrNull() ?: parseIsoMillis(s) ?: return null
    return resetTimeFromEpochMillis(ms, now)
}

internal fun resetTimeEpochSeconds(resetsAt: Double?, now: Long): ResetTime? {
    val secs = resetsAt ?: return null
    return resetTimeFromEpochMillis((secs * 1000.0).toLong(), now)
}

private fun englishReset(r: ResetTime?): String = when (r) {
    null -> ""
    ResetTime.Soon -> "resets soon"
    is ResetTime.In -> if (r.hours > 0) "resets in ${r.hours}h ${r.minutes}m" else "resets in ${r.minutes}m"
    is ResetTime.On -> "resets ${r.day}"
}

/** "resets in 1h 24m" / "resets soon" / "resets Oct 22" in the app's language; "" for null. */
@Composable
internal fun localizedReset(r: ResetTime?): String = when (r) {
    null -> ""
    ResetTime.Soon -> localized(Res.string.usage_resets_soon)
    is ResetTime.In ->
        if (r.hours > 0) localized(Res.string.usage_resets_in_hm, r.hours, r.minutes)
        else localized(Res.string.usage_resets_in_m, r.minutes)
    is ResetTime.On -> localized(Res.string.usage_resets_on, r.day)
}

private fun clampPct(v: Double): Double = v.coerceIn(0.0, 100.0)

/**
 * `"%.<decimals>f"` without `java.util.Formatter`.
 *
 * HALF_UP on the magnitude — an exact midpoint rounds AWAY from zero — because that is what
 * `String.format(Locale.US, "%.2f", …)` did for every value these cards show. Note this is NOT
 * `kotlin.math.round`, which is ties-to-even (`0.125` would come out `0.12`).
 */
internal fun fixed(v: Double, decimals: Int): String {
    if (v.isNaN() || v.isInfinite()) return v.toString()
    var scale = 1L
    repeat(decimals) { scale *= 10L }
    val scaled = floor(abs(v) * scale + 0.5).toLong()
    val whole = scaled / scale
    val frac = scaled % scale
    val sign = if (v < 0 && scaled != 0L) "-" else ""
    if (decimals == 0) return "$sign$whole"
    return "$sign$whole." + frac.toString().padStart(decimals, '0')
}

private fun money(cents: Double): String = "$" + fixed(cents / 100.0, 2)
private fun dollars(v: Double): String = "$" + fixed(v, 2)

/** 1.2M / 34.5K / 912 — mirrors the web UsageView's formatTokens. */
private fun tokens(n: Long): String = when {
    n >= 1_000_000 -> fixed(n / 1_000_000.0, 1) + "M"
    n >= 1_000 -> fixed(n / 1_000.0, 1) + "K"
    else -> n.toString()
}

/** Bar colour by percentage: >=85 red, >=60 amber, else primary — both apps' `barColor`. */
@Composable
private fun barColor(pct: Double): Color {
    val cs = MaterialTheme.colorScheme
    val semantics = LocalSemantics.current
    return when {
        pct >= 85 -> cs.error
        pct >= 60 -> semantics.warning
        else -> cs.primary
    }
}

// ─── Shared card chrome ─────────────────────────────────────────────────────────────────────────

/** Outer usage card: rounded, bordered, title + subtitle, an optional badge slot, content. */
@Composable
fun UsageCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    asOf: String? = null,
    asOfTag: String? = null,
    refreshing: Boolean = false,
    refreshingTag: String? = null,
    badge: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.5f
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(cs.surfaceContainer.copy(alpha = alpha))
            .border(1.dp, cs.outline.copy(alpha = alpha), RoundedCornerShape(Radii.md))
            .padding(Space.lg),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, color = cs.onSurfaceVariant, fontSize = 12.sp)
                if (!asOf.isNullOrEmpty()) {
                    Text(
                        asOf,
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = asOfTag?.let { Modifier.testTag(it) } ?: Modifier,
                    )
                }
            }
            if (refreshing) {
                CircularProgressIndicator(
                    color = cs.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .padding(end = Space.sm)
                        .size(14.dp)
                        .then(refreshingTag?.let { Modifier.testTag(it) } ?: Modifier),
                )
            }
            badge?.invoke()
        }
        content()
    }
}

/** A labelled usage window: label + "{pct}% used" + a determinate progress bar + reset line. */
@Composable
fun UsageWindowRow(label: String, usedPct: Double, resetLine: String) {
    val cs = MaterialTheme.colorScheme
    val pct = clampPct(usedPct)
    Column(Modifier.fillMaxWidth().padding(bottom = Space.md)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(localized(Res.string.usage_percent_used, pct.roundToInt()), color = cs.onSurface, fontSize = 12.sp)
        }
        LinearProgressIndicator(
            progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = barColor(pct),
            trackColor = cs.surfaceVariant,
        )
        if (resetLine.isNotEmpty()) {
            Text(resetLine, color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = Space.xs))
        }
    }
}

/**
 * A footer row separated by a top divider (extra usage / credits / spend). [valueColor] tints
 * the value when it carries a status ("available" / "locked"); null keeps the plain onSurface.
 */
@Composable
fun UsageFooterRow(label: String, value: String, valueColor: Color? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = Space.sm)) {
        HorizontalDivider(color = cs.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(top = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(value, color = valueColor ?: cs.onSurface, fontSize = 12.sp)
        }
    }
}

/**
 * "Claude · Work" for an added account's card, "Claude · System login" for the system login's card
 * once the agent has other accounts, plain "Claude" otherwise.
 */
fun accountCardTitle(provider: String, account: String?): String =
    if (account.isNullOrBlank()) provider else "$provider · $account"

/** The system login keeps the bare tag; each added account's card gets its own. */
fun accountCardTag(base: String, accountId: String?): String =
    if (accountId.isNullOrBlank()) base else "${base}_$accountId"

/** A card's line when it has no numbers: the sign-in notice for a refused login, the broker's words otherwise. */
@Composable
private fun UsageUnavailable(provider: String, error: String?, errorCode: String?, onSignIn: (() -> Unit)?, signInTag: String) {
    if (errorCode == SIGN_IN_EXPIRED) SignInExpiredNotice(provider, errorCode, onSignIn, signInTag)
    else Text(error ?: localized(Res.string.usage_not_available), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
}

/**
 * For a login the provider refused ([errorCode] [SIGN_IN_EXPIRED]): "<provider> sign-in expired", with
 * "Sign in again" when the host can take the user to the sign-in ([onSignIn]). Under last-known numbers
 * too: the store keeps them when a later fetch fails. Nothing for any other error.
 */
@Composable
private fun SignInExpiredNotice(provider: String, errorCode: String?, onSignIn: (() -> Unit)?, signInTag: String) {
    if (errorCode != SIGN_IN_EXPIRED) return
    val cs = MaterialTheme.colorScheme
    Text(localized(Res.string.usage_sign_in_expired, provider), color = cs.onSurface, fontSize = 12.sp)
    if (onSignIn != null) {
        OutlinedButton(onClick = onSignIn, modifier = Modifier.padding(top = Space.sm).testTag(signInTag)) {
            Text(localized(Res.string.usage_sign_in_again), color = cs.onSurface, fontSize = 13.sp)
        }
    }
}

/** Label for the system login's card: only needed to tell it apart from the agent's other accounts. */
@Composable
private fun systemCardLabel(usage: UsageResponse?, agent: String): String? =
    if (usage?.accounts?.any { it.agent == agent } == true) localized(Res.string.accounts_system_login) else null

// ─── Provider cards ─────────────────────────────────────────────────────────────────────────────

/**
 * `onRedeem` — null hides the "Use a reset" affordance. On confirm: spends 1 banked limit reset via
 * [onRedeem], shows the resulting [claudeResetNote] inline, then calls [onRedeemed] — the same
 * flow as [CodexUsageCard].
 */
@Composable
fun ClaudeUsageCard(
    claude: ClaudeUsage?,
    error: String?,
    asOf: String? = null,
    refreshing: Boolean = false,
    now: Long = nowMs(),
    onRedeem: (suspend () -> ClaudeResetResult?)? = null,
    onRedeemed: () -> Unit = {},
    account: String? = null,
    accountId: String? = null,
    errorCode: String? = null,
    onSignIn: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // Un-keyed for the same reason as CodexUsageCard: the note outlives the refreshed `claude`.
    var redeeming by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var redeemed by remember { mutableStateOf<Redeemed<ClaudeResetResult>?>(null) }
    val resets = claude?.resets?.takeIf { it.resetsLeft > 0 }
    UsageCard(
        title = accountCardTitle("Claude", account),
        subtitle = localized(Res.string.usage_pro_plan),
        enabled = claude != null,
        modifier = Modifier.testTag(accountCardTag("usage_card_claude", accountId)),
        asOf = asOf,
        asOfTag = "usage_as_of_claude",
        refreshing = refreshing,
        refreshingTag = "usage_refreshing_claude",
    ) {
        if (claude == null) {
            UsageUnavailable("Claude", error, errorCode, onSignIn, accountCardTag("usage_sign_in_claude", accountId))
        } else {
            UsageWindowRow(localized(Res.string.usage_five_hour_window), claude.fiveHour.used, localizedResetIso(claude.fiveHour.resetsAt, now))
            UsageWindowRow(localized(Res.string.usage_seven_day_window), claude.sevenDay.used, localizedResetIso(claude.sevenDay.resetsAt, now))
            claude.sevenDaySonnet?.let { UsageWindowRow(localized(Res.string.usage_seven_day_sonnet), it.used, localizedResetIso(it.resetsAt, now)) }
            claude.sevenDayFable?.let { UsageWindowRow(localized(Res.string.usage_seven_day_fable), it.used, localizedResetIso(it.resetsAt, now)) }
            claude.extraUsage?.takeIf { it.enabled }?.let { e ->
                UsageFooterRow(localized(Res.string.usage_extra_usage), "${dollars(e.usedCredits)} / ${dollars(e.monthlyLimit)}")
            }
            if (resets != null) {
                UsageFooterRow(localized(Res.string.usage_resets_banked), localizedClaudeResetsValue(resets))
                val hint = localizedClaudeResetBlockedHint(resets)
                if (hint != null) {
                    Text(
                        hint,
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = Space.xs).testTag("claude_redeem_hint"),
                    )
                } else if (onRedeem != null) {
                    OutlinedButton(
                        onClick = { showDialog = true },
                        enabled = !redeeming,
                        modifier = Modifier.padding(top = Space.sm).testTag("claude_redeem_button"),
                    ) {
                        Text(localized(if (redeeming) Res.string.usage_redeeming else Res.string.usage_use_a_reset), color = cs.onSurface, fontSize = 13.sp)
                    }
                }
            }
            redeemed?.let {
                Text(
                    localizedClaudeResetNote(it.result),
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = Space.xs).testTag("claude_redeem_note"),
                )
            }
            SignInExpiredNotice("Claude", errorCode, onSignIn, accountCardTag("usage_sign_in_claude", accountId))
        }
    }
    if (showDialog && resets != null) {
        val grant = resets.grants.firstOrNull { it.id == resets.nextGrantId }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(localized(Res.string.usage_redeem_title)) },
            text = {
                Text(localized(Res.string.usage_claude_redeem_text, localizedClaudeResetClears(grant?.clears.orEmpty()), resets.resetsLeft))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        scope.launch {
                            redeeming = true
                            val r = onRedeem?.invoke()
                            redeemed = Redeemed(r)
                            redeeming = false
                            onRedeemed()
                        }
                    },
                    modifier = Modifier.testTag("claude_redeem_confirm"),
                ) { Text(localized(Res.string.usage_use_reset), color = cs.primary) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDialog = false },
                    modifier = Modifier.testTag("claude_redeem_cancel"),
                ) { Text(localized(Res.string.usage_cancel)) }
            },
        )
    }
}

/** A redeem's answer, kept so its note is worded at render time, in the app's language (null result = it failed). */
private class Redeemed<T>(val result: T?)

/** The transient inline status line after a Claude redeem attempt. English: the tests' twin of [localizedClaudeResetNote]. */
fun claudeResetNote(r: ClaudeResetResult?): String {
    if (r == null) return "Reset failed"
    return when (r.result) {
        "reset" -> "✓ Limits reset" + (r.resetsLeft?.let { " · $it left" } ?: "")
        "not_limited" -> "Your limits were already clear — nothing was used" // i18n-ok: test twin
        "already_used" -> "That reset was already used" // i18n-ok: test twin
        "cooldown" -> "A reset is still going through — try again in a minute" // i18n-ok: test twin
        "ineligible", "no_reset" -> "No reset available to use" // i18n-ok: test twin
        "unavailable" -> "Couldn't use a reset right now — try again later" // i18n-ok: test twin
        else -> "Reset request completed" // i18n-ok: test twin
    }
}

/** [claudeResetNote] in the app's language. */
@Composable
fun localizedClaudeResetNote(r: ClaudeResetResult?): String = when (r?.result) {
    null -> localized(Res.string.usage_reset_failed)
    "reset" -> r?.resetsLeft?.let { localized(Res.string.usage_claude_reset_done_left, it) } ?: localized(Res.string.usage_claude_reset_done)
    "not_limited" -> localized(Res.string.usage_claude_not_limited)
    "already_used" -> localized(Res.string.usage_claude_already_used)
    "cooldown" -> localized(Res.string.usage_claude_cooldown)
    "ineligible", "no_reset" -> localized(Res.string.usage_claude_no_reset)
    "unavailable" -> localized(Res.string.usage_claude_unavailable)
    else -> localized(Res.string.usage_reset_completed)
}

/**
 * `onRedeem` — null hides the "Use a reset" affordance entirely (kept optional, mirroring both
 * originals, though [UsageScreen] always supplies one). On confirm: spends 1 banked reset via
 * [onRedeem], shows the resulting [codexResetNote] inline, then calls [onRedeemed] so the caller
 * can pull fresh numbers (desktop swaps `.codex` in place; Android re-fetched the whole payload —
 * the stateful [UsageScreen] overload does both through [UsageActions]).
 */
@Composable
fun CodexUsageCard(
    codex: CodexUsage?,
    error: String?,
    onRedeem: (suspend () -> CodexResetResult?)? = null,
    asOf: String? = null,
    refreshing: Boolean = false,
    now: Long = nowMs(),
    onRedeemed: () -> Unit = {},
    account: String? = null,
    accountId: String? = null,
    errorCode: String? = null,
    onSignIn: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val semantics = LocalSemantics.current
    val scope = rememberCoroutineScope()
    // NOT keyed to `codex`: a successful redeem swaps the caller's `codex` for a refreshed value
    // (see UsageScreen's KDoc) — the note must survive that swap so "✓ Reset — cleared N window(s)"
    // stays visible under the now-updated numbers, matching both originals (whose `note`/`redeeming`
    // are similarly un-keyed local state that outlives the re-fetch).
    var redeeming by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var redeemed by remember { mutableStateOf<Redeemed<CodexResetResult>?>(null) }
    UsageCard(
        title = accountCardTitle("Codex", account),
        subtitle = codex?.plan?.takeIf { it.isNotBlank() } ?: localized(Res.string.usage_unknown),
        enabled = codex != null,
        modifier = Modifier.testTag(accountCardTag("usage_card_codex", accountId)),
        asOf = asOf,
        asOfTag = "usage_as_of_codex",
        refreshing = refreshing,
        refreshingTag = "usage_refreshing_codex",
        badge = if (codex?.limitReached == true) {
            {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(Radii.pill))
                        .background(cs.error.copy(alpha = 0.1f))
                        .padding(horizontal = Space.sm, vertical = 2.dp),
                ) {
                    Text(localized(Res.string.usage_limit_reached_badge), color = cs.error, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        } else null,
    ) {
        if (codex == null) {
            UsageUnavailable("Codex", error, errorCode, onSignIn, accountCardTag("usage_sign_in_codex", accountId))
        } else {
            val usageWindow = localized(Res.string.usage_window)
            codex.windows.forEach { window ->
                UsageWindowRow(
                    window.label.ifBlank { usageWindow },
                    window.used,
                    localizedResetEpochSeconds(window.resetsAt, now),
                )
            }
            // Per-model gates (e.g. Astra), independent of the windows above.
            codex.models.forEach { model ->
                UsageFooterRow(
                    model.label.ifBlank { model.id },
                    localizedCodexModelStatus(model, now),
                    valueColor = if (model.available) semantics.success else semantics.warning,
                )
            }
            codex.credits?.takeIf { it.hasCredits }?.let { cr ->
                UsageFooterRow(localized(Res.string.usage_credits_balance), localized(Res.string.usage_credits_amount, cr.balance))
            }
            UsageFooterRow(localized(Res.string.usage_resets_banked), "${codex.resetCredits}")
            if (codex.resetCredits > 0 && onRedeem != null) {
                OutlinedButton(
                    onClick = { showDialog = true },
                    enabled = !redeeming,
                    modifier = Modifier.padding(top = Space.sm).testTag("codex_redeem_button"),
                ) {
                    Text(localized(if (redeeming) Res.string.usage_redeeming else Res.string.usage_use_a_reset), color = cs.onSurface, fontSize = 13.sp)
                }
            }
            redeemed?.let {
                Text(
                    localizedCodexResetNote(it.result),
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = Space.xs).testTag("codex_redeem_note"),
                )
            }
            SignInExpiredNotice("Codex", errorCode, onSignIn, accountCardTag("usage_sign_in_codex", accountId))
        }
    }
    if (showDialog && codex != null) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(localized(Res.string.usage_redeem_title)) },
            text = { Text(localized(Res.string.usage_codex_redeem_text, codex.resetCredits)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        scope.launch {
                            redeeming = true
                            val r = onRedeem?.invoke()
                            redeemed = Redeemed(r)
                            redeeming = false
                            onRedeemed()
                        }
                    },
                    modifier = Modifier.testTag("codex_redeem_confirm"),
                ) { Text(localized(Res.string.usage_use_reset), color = cs.primary) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDialog = false },
                    modifier = Modifier.testTag("codex_redeem_cancel"),
                ) { Text(localized(Res.string.usage_cancel)) }
            },
        )
    }
}

/** Both apps' `codexResetNote` — the transient inline status line after a redeem attempt. English: the tests' twin. */
fun codexResetNote(r: CodexResetResult?): String {
    if (r == null) return "Reset failed"
    return when (r.code) {
        "reset" -> "✓ Reset — cleared ${r.windowsReset} window${if (r.windowsReset == 1) "" else "s"}"
        "nothing_to_reset" -> "Nothing to reset right now" // i18n-ok: test twin
        "no_credit" -> "No banked resets left" // i18n-ok: test twin
        "already_redeemed" -> "That reset was already redeemed" // i18n-ok: test twin
        else -> "Reset request completed" // i18n-ok: test twin
    }
}

/** [codexResetNote] in the app's language. */
@Composable
fun localizedCodexResetNote(r: CodexResetResult?): String = when (r?.code) {
    null -> localized(Res.string.usage_reset_failed)
    "reset" -> (r?.windowsReset ?: 0).let { localizedPlural(Res.plurals.usage_codex_reset_done, it, it) }
    "nothing_to_reset" -> localized(Res.string.usage_codex_nothing)
    "no_credit" -> localized(Res.string.usage_codex_no_credit)
    "already_redeemed" -> localized(Res.string.usage_codex_already_redeemed)
    else -> localized(Res.string.usage_reset_completed)
}

@Composable
fun CursorUsageCard(
    cursor: CursorUsage?,
    error: String?,
    asOf: String? = null,
    refreshing: Boolean = false,
    now: Long = nowMs(),
    account: String? = null,
    accountId: String? = null,
    errorCode: String? = null,
    onSignIn: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    UsageCard(
        title = accountCardTitle("Cursor", account),
        subtitle = localized(Res.string.usage_billing_cycle),
        enabled = cursor != null,
        modifier = Modifier.testTag(accountCardTag("usage_card_cursor", accountId)),
        asOf = asOf,
        asOfTag = "usage_as_of_cursor",
        refreshing = refreshing,
        refreshingTag = "usage_refreshing_cursor",
    ) {
        if (cursor == null) {
            UsageUnavailable("Cursor", error, errorCode, onSignIn, accountCardTag("usage_sign_in_cursor", accountId))
        } else {
            UsageWindowRow(localized(Res.string.usage_usage), cursor.totalPercentUsed, localizedResetIso(cursor.billingCycleEnd, now))
            if (cursor.spendAvailable) {
                UsageFooterRow(localized(Res.string.usage_spend), localized(Res.string.usage_spend_included, money(cursor.totalSpendCents), money(cursor.includedCents)))
            }
            SignInExpiredNotice("Cursor", errorCode, onSignIn, accountCardTag("usage_sign_in_cursor", accountId))
        }
    }
}

/**
 * OpenCode: the OpenCode Go plan's 5-hour / weekly / monthly windows when the login has one, a plain line when it
 * doesn't, then the local spend and activity opencode records. An older broker sends no plan: local totals only,
 * spend as the badge, as before.
 */
@Composable
fun OpenCodeUsageCard(
    opencode: OpenCodeUsage?,
    error: String?,
    asOf: String? = null,
    refreshing: Boolean = false,
    now: Long = nowMs(),
    onSignIn: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val plan = opencode?.plan
    val go = plan?.go?.takeIf { plan.status == "active" }
    UsageCard(
        title = "opencode", // i18n-ok: brand
        subtitle = when {
            go != null -> listOfNotNull("OpenCode Go", plan.email?.ifBlank { null }).joinToString(" · ")
            plan != null -> "OpenCode"
            else -> localized(Res.string.usage_local_all_time)
        },
        enabled = opencode != null,
        modifier = Modifier.testTag("usage_card_opencode"),
        asOf = asOf,
        asOfTag = "usage_as_of_opencode",
        refreshing = refreshing,
        refreshingTag = "usage_refreshing_opencode",
        badge = opencode?.takeIf { plan == null }?.let { u -> { Text(dollars(u.totalCostUsd), color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) } },
    ) {
        if (opencode == null) {
            Text(error ?: localized(Res.string.usage_not_available), color = cs.onSurfaceVariant, fontSize = 12.sp)
            return@UsageCard
        }
        if (go != null) {
            OpenCodeGoWindow(localized(Res.string.usage_five_hours), go.rolling, now)
            OpenCodeGoWindow(localized(Res.string.usage_weekly), go.weekly, now)
            OpenCodeGoWindow(localized(Res.string.usage_monthly), go.monthly, now)
        } else if (plan != null) {
            OpenCodePlanLine(plan, onSignIn)
        }
        plan?.free?.let { free ->
            val reset = localizedFreeResetLabel(free.resetsAtIso, now)
            UsageFooterRow(
                localized(Res.string.usage_free_models),
                listOf(localized(if (free.reached) Res.string.usage_limit_reached else Res.string.usage_daily_limit), reset)
                    .filter { it.isNotEmpty() }.joinToString(" · "),
                valueColor = if (free.reached) cs.error else null,
            )
        }
        if (plan == null) {
            UsageFooterRow(localized(Res.string.usage_input), tokens(opencode.inputTokens))
            UsageFooterRow(localized(Res.string.usage_output), tokens(opencode.outputTokens))
            UsageFooterRow(localized(Res.string.usage_cache_read), tokens(opencode.cacheReadTokens))
            UsageFooterRow(localized(Res.string.usage_cache_write), tokens(opencode.cacheWriteTokens))
        } else {
            UsageFooterRow(localized(Res.string.usage_local_spend), dollars(opencode.totalCostUsd))
        }
        UsageFooterRow(localized(Res.string.usage_activity), localized(Res.string.usage_activity_value, opencode.sessions, opencode.messages))
    }
}

/**
 * When OpenCode's free allowance starts over, in the viewer's own clock: "resets 19:00" today, "resets tomorrow 03:00"
 * on the next local day (00:00 UTC is 03:00 in Istanbul). "" when [iso] is unreadable.
 */
internal fun freeResetLabel(iso: String?, now: Long, offsetMs: Long? = null): String {
    val (days, hhmm) = freeResetDay(iso, now, offsetMs) ?: return ""
    return when (days) {
        0L -> "resets $hhmm"
        1L -> "resets tomorrow $hhmm"
        else -> formatResetIso(iso, now)
    }
}

/** [freeResetLabel] in the app's language. */
@Composable
internal fun localizedFreeResetLabel(iso: String?, now: Long): String {
    val (days, hhmm) = freeResetDay(iso, now, null) ?: return ""
    return when (days) {
        0L -> localized(Res.string.usage_resets_on, hhmm)
        1L -> localized(Res.string.usage_resets_tomorrow, hhmm)
        else -> localizedResetIso(iso, now)
    }
}

/** How many local days after today [iso] falls (0 today, 1 tomorrow …) and its local HH:mm; null when unreadable. */
private fun freeResetDay(iso: String?, now: Long, offsetMs: Long?): Pair<Long, String>? {
    val at = iso?.let { parseIsoMillis(it) } ?: return null
    val offset = offsetMs ?: localUtcOffsetMs(at)
    val day = 86_400_000L
    val local = at + offset
    val atDay = local.floorDiv(day)
    val nowDay = (now + offset).floorDiv(day)
    val minute = local.mod(day) / 60_000L
    val hhmm = "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"
    return (atDay - nowDay) to hhmm
}

@Composable
private fun OpenCodeGoWindow(label: String, meter: OpenCodeGoMeter, now: Long) {
    val reset = localizedResetIso(meter.resetsAtIso, now)
    val limitReached = localized(Res.string.usage_limit_reached)
    UsageWindowRow(label, meter.percent, if (meter.limited) listOf(limitReached, reset).filter { it.isNotEmpty() }.joinToString(" · ") else reset)
}

/** The plan's one line when there are no meters: no Go plan, signed out, expired (with a sign-in), or unreadable. */
@Composable
private fun OpenCodePlanLine(plan: OpenCodePlan, onSignIn: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val error = plan.error
    val (line, action) = when (plan.status) {
        "none" -> localized(Res.string.usage_opencode_no_plan) to null
        "signed_out" -> localized(Res.string.usage_opencode_signed_out) to localized(Res.string.usage_sign_in)
        "expired" -> localized(Res.string.usage_opencode_expired) to localized(Res.string.usage_sign_in_again)
        else -> (if (error != null) localized(Res.string.usage_opencode_read_failed_reason, error) else localized(Res.string.usage_opencode_read_failed)) to null
    }
    Text(line, color = cs.onSurface, fontSize = 12.sp)
    if (action != null && onSignIn != null) {
        OutlinedButton(onClick = onSignIn, modifier = Modifier.padding(top = Space.sm).testTag("usage_sign_in_opencode")) {
            Text(action, color = cs.onSurface, fontSize = 13.sp)
        }
    }
}

@Composable
fun GrokUsageCard(
    grok: GrokUsage?,
    error: String?,
    asOf: String? = null,
    refreshing: Boolean = false,
    now: Long = nowMs(),
    account: String? = null,
    accountId: String? = null,
    errorCode: String? = null,
    onSignIn: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    UsageCard(
        title = accountCardTitle("Grok", account),
        subtitle = grok?.plan?.ifBlank { null } ?: localized(Res.string.usage_unknown),
        enabled = grok != null,
        modifier = Modifier.testTag(accountCardTag("usage_card_grok", accountId)),
        asOf = asOf,
        asOfTag = "usage_as_of_grok",
        refreshing = refreshing,
        refreshingTag = "usage_refreshing_grok",
    ) {
        if (grok == null) {
            UsageUnavailable("Grok", error, errorCode, onSignIn, accountCardTag("usage_sign_in_grok", accountId))
        } else {
            UsageWindowRow(localizedGrokPeriodLabel(grok.periodType), grok.percentUsed, localizedResetIso(grok.billingPeriodEnd, now))
            // Only worth their own rows when xAI splits the pool across more than one product.
            if (grok.products.size > 1) {
                grok.products.forEach { product ->
                    UsageFooterRow(product.product, localized(Res.string.usage_percent_used, product.percentUsed.roundToInt()))
                }
            }
            if (grok.monthlyLimit > 0) {
                UsageFooterRow(localized(Res.string.usage_credits), "${grok.used.toLong()} / ${grok.monthlyLimit.toLong()}")
            }
            if (grok.onDemandCap > 0) {
                UsageFooterRow(localized(Res.string.usage_on_demand), "${grok.onDemandUsed.toLong()} / ${grok.onDemandCap.toLong()}")
            }
            if (grok.prepaidBalance > 0) {
                UsageFooterRow(localized(Res.string.usage_prepaid_balance), "${grok.prepaidBalance.toLong()}")
            }
            SignInExpiredNotice("Grok", errorCode, onSignIn, accountCardTag("usage_sign_in_grok", accountId))
        }
    }
}

/**
 * Antigravity's limits, one bar per bucket agy reports, under the broker's "<group> · <window>" label. agy reports no
 * plan, so the subtitle says "unknown", as Codex's and Grok's do without one. The system login's only: an added
 * account's card is [NoQuotaAccountCard].
 */
@Composable
fun AntigravityUsageCard(
    antigravity: AntigravityUsage?,
    error: String?,
    asOf: String? = null,
    refreshing: Boolean = false,
    now: Long = nowMs(),
    account: String? = null,
    errorCode: String? = null,
    onSignIn: (() -> Unit)? = null,
) {
    UsageCard(
        title = accountCardTitle("Antigravity", account),
        subtitle = localized(Res.string.usage_unknown),
        enabled = antigravity != null,
        modifier = Modifier.testTag("usage_card_antigravity"),
        asOf = asOf,
        asOfTag = "usage_as_of_antigravity",
        refreshing = refreshing,
        refreshingTag = "usage_refreshing_antigravity",
    ) {
        if (antigravity == null) {
            UsageUnavailable("Antigravity", error, errorCode, onSignIn, "usage_sign_in_antigravity")
        } else {
            if (antigravity.windows.isEmpty()) {
                Text(localized(Res.string.usage_antigravity_no_limits), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            val usageWindow = localized(Res.string.usage_window)
            antigravity.windows.forEach { w ->
                UsageWindowRow(w.label.ifBlank { usageWindow }, w.used, localizedResetIso(w.resetsAt, now))
            }
            SignInExpiredNotice("Antigravity", errorCode, onSignIn, "usage_sign_in_antigravity")
        }
    }
}

// ─── Actions holder ─────────────────────────────────────────────────────────────────────────────

/**
 * Everything the Usage screen needs from a store: the held snapshot it paints immediately, the two
 * fetches, and the Codex redeem. Both builders below keep the SAME rule desktop's AppShell had —
 * a successful redeem swaps the refreshed `codex` into the held snapshot in place, so the card's
 * numbers move under the note it just showed.
 */
@Immutable
class UsageActions(
    val snapshot: StateFlow<UsageResponse?>,
    /** GET /usage — fills gaps / picks up the broker's current snapshot. Null on failure. */
    val load: suspend () -> UsageResponse?,
    /** POST /usage/refresh — force a live re-fetch. Null on failure. */
    val refresh: suspend () -> UsageResponse?,
    val redeem: suspend () -> CodexResetResult?,
    /** POST /usage/claude/reset — spend one banked Claude limit reset. Null on failure. */
    val redeemClaude: suspend () -> ClaudeResetResult? = { null },
)

/** [UsageActions] against one paired host — desktop's wiring. */
@Composable
fun rememberUsageActions(app: HostStore): UsageActions = remember(app) {
    UsageActions(
        snapshot = app.usageSnapshot,
        load = { app.usage() },
        refresh = { app.refreshUsage() },
        redeem = {
            val r = app.redeemCodexReset()
            if (r?.code == "reset" && r.codex != null) {
                app.usageSnapshot.value?.copy(codex = r.codex)?.let { app.applyUsage(it) }
            }
            r
        },
        redeemClaude = {
            val r = app.redeemClaudeReset()
            if (r?.claude != null) {
                app.usageSnapshot.value?.copy(claude = r.claude)?.let { app.applyUsage(it) }
            }
            r
        },
    )
}

/** [UsageActions] against the fleet's ACTIVE host — Android's wiring. */
@Composable
fun rememberUsageActions(fleet: FleetStore): UsageActions = remember(fleet) {
    UsageActions(
        snapshot = fleet.usageSnapshot,
        load = { fleet.usage() },
        refresh = { fleet.refreshUsage() },
        redeem = {
            val r = fleet.redeemCodexReset()
            if (r?.code == "reset" && r.codex != null) {
                fleet.usageSnapshot.value?.copy(codex = r.codex)?.let { fleet.applyUsage(it) }
            }
            r
        },
        redeemClaude = {
            val r = fleet.redeemClaudeReset()
            if (r?.claude != null) {
                fleet.usageSnapshot.value?.copy(claude = r.claude)?.let { fleet.applyUsage(it) }
            }
            r
        },
    )
}

// ─── UsageScreen ────────────────────────────────────────────────────────────────────────────────

/**
 * Usage over a store: paints [UsageActions.snapshot] immediately (never blanks on re-open) and
 * runs [UsageActions.load] once per host, exactly as desktop's AppShell did around the popover.
 *
 * @param onBack close the popover (desktop) / pop the route (Android).
 * @param topBarShown something above already painted a `TopAppBar` for this screen.
 * @param standalone this is its own route rather than an anchored popover, so it needs a title and
 *   Back at EVERY width (a phone in landscape is Medium, not Compact) — Android's `Route.Usage`.
 */
@Composable
fun UsageScreen(
    actions: UsageActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
    standalone: Boolean = false,
    now: Long = nowMs(),
    onSignIn: ((agent: String, accountId: String?) -> Unit)? = null,
) {
    val usage by actions.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(actions) {
        loading = actions.snapshot.value == null
        actions.load()
        loading = false
    }
    UsageScreen(
        usage = usage,
        loading = loading,
        onBack = onBack,
        onRedeem = actions.redeem,
        onRedeemClaude = actions.redeemClaude,
        // Android re-fetched the WHOLE payload after a redeem; the holder's `redeem` only swaps
        // the refreshed Codex block into the held snapshot, so the other providers would keep
        // showing pre-redeem numbers without this.
        onRedeemed = { scope.launch { actions.load() } },
        onRefresh = {
            loading = actions.snapshot.value == null
            actions.refresh()
            loading = false
        },
        now = now,
        modifier = modifier,
        topBarShown = topBarShown,
        standalone = standalone,
        onSignIn = onSignIn,
    )
}

/**
 * Usage card body: title row + close, then either a spinner (still loading), "Unable to load
 * usage data." (resolved to null), or the provider cards fed from [usage]. [loading] and [usage]
 * are both owned by the caller — this composable renders whatever point-in-time snapshot it is
 * given. [onRedeem] is threaded straight to [CodexUsageCard].
 *
 * Chrome: desktop hosts this inside [UsagePopover], where the card's own header row IS the title
 * bar (a close ✕ and the refresh ⟳). A phone route, or any width when [standalone], gets Android's
 * `TopAppBar` with Back + Refresh instead — unless [topBarShown] says something above already
 * painted one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    usage: UsageResponse?,
    loading: Boolean,
    onBack: () -> Unit,
    onRedeem: suspend () -> CodexResetResult?,
    onRefresh: (suspend () -> Unit)? = null,
    now: Long = nowMs(),
    modifier: Modifier = Modifier,
    topBarShown: Boolean = false,
    standalone: Boolean = false,
    onRedeemed: () -> Unit = {},
    onRedeemClaude: (suspend () -> ClaudeResetResult?)? = null,
    /** "Sign in again" on a card whose login the provider refused: the agent, and the added account's id (null = the system login). */
    onSignIn: ((agent: String, accountId: String?) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val semantics = LocalSemantics.current
    val scope = rememberCoroutineScope()
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    val barOwned = (standalone || compact) && !topBarShown

    if (barOwned) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(localized(Res.string.usage_title), color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("usage_back")) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = localized(Res.string.usage_back),
                                tint = cs.onSurface,
                            )
                        }
                    },
                    actions = {
                        if (onRefresh != null) {
                            IconButton(
                                onClick = { if (!loading) scope.launch { onRefresh() } },
                                enabled = !loading,
                                modifier = Modifier.testTag("usage_refresh"),
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = localized(Res.string.usage_refresh),
                                    tint = if (loading) cs.onSurfaceVariant else cs.onSurface,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
                )
            },
            containerColor = cs.background,
            modifier = Modifier.testTag("usage_screen"),
        ) { padding ->
            UsageBody(usage, loading, onRedeem, onRedeemClaude, now, onRedeemed, onSignIn, modifier.padding(padding))
        }
    } else {
        Column(modifier.fillMaxSize().testTag("usage_screen")) {
            UsageHeaderRow(loading, onBack, onRefresh)
            HorizontalDivider(color = cs.outlineVariant)
            UsageBody(usage, loading, onRedeem, onRedeemClaude, now, onRedeemed, onSignIn, Modifier)
        }
    }
}

/** Desktop's in-card header: title, refresh, close. Only painted when no top bar owns them. */
@Composable
private fun UsageHeaderRow(loading: Boolean, onBack: () -> Unit, onRefresh: (suspend () -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val touch = !LocalPointerAvailable.current
    val actionSize = if (touch) Modifier.sizeIn(minWidth = TouchTargetMin, minHeight = TouchTargetMin) else Modifier
    Row(
        Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.sm, top = Space.sm, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            localized(Res.string.usage_title),
            color = cs.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (onRefresh != null) {
            IconButton(
                onClick = { if (!loading) scope.launch { onRefresh() } },
                enabled = !loading,
                modifier = actionSize.testTag("usage_refresh"),
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = localized(Res.string.usage_refresh),
                    tint = if (loading) cs.onSurfaceVariant else cs.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onBack, modifier = actionSize.testTag("usage_back")) {
            Icon(
                Icons.Filled.Close,
                contentDescription = localized(Res.string.usage_close),
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun UsageBody(
    usage: UsageResponse?,
    loading: Boolean,
    onRedeem: suspend () -> CodexResetResult?,
    onRedeemClaude: (suspend () -> ClaudeResetResult?)?,
    now: Long,
    onRedeemed: () -> Unit,
    onSignIn: ((agent: String, accountId: String?) -> Unit)?,
    modifier: Modifier,
) {
    val signInFor: (String) -> (() -> Unit)? = { agent -> onSignIn?.let { { it(agent, null) } } }
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().testTag("usage_body")) {
        when {
            loading && usage == null -> {
                CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center).testTag("usage_spinner"),
                )
            }
            usage == null && !loading -> {
                Text(
                    localized(Res.string.usage_load_failed),
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(Space.lg),
                )
            }
            else -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(Space.lg),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    ClaudeUsageCard(
                        usage?.claude,
                        usage?.errors?.get("claude"),
                        asOf = localizedFetchedAt(usage?.fetchedAt?.get("claude"), now),
                        refreshing = usage?.refreshing?.contains("claude") == true,
                        now = now,
                        onRedeem = onRedeemClaude,
                        onRedeemed = onRedeemed,
                        account = systemCardLabel(usage, "claude"),
                        errorCode = usage?.errorCodes?.get("claude"),
                        onSignIn = signInFor("claude"),
                    )
                    AccountUsageCards(usage, "claude", now, onSignIn)
                    CodexUsageCard(
                        usage?.codex,
                        usage?.errors?.get("codex"),
                        onRedeem = onRedeem,
                        asOf = localizedFetchedAt(usage?.fetchedAt?.get("codex"), now),
                        refreshing = usage?.refreshing?.contains("codex") == true,
                        now = now,
                        onRedeemed = onRedeemed,
                        account = systemCardLabel(usage, "codex"),
                        errorCode = usage?.errorCodes?.get("codex"),
                        onSignIn = signInFor("codex"),
                    )
                    AccountUsageCards(usage, "codex", now, onSignIn)
                    CursorUsageCard(
                        usage?.cursor,
                        usage?.errors?.get("cursor"),
                        asOf = localizedFetchedAt(usage?.fetchedAt?.get("cursor"), now),
                        refreshing = usage?.refreshing?.contains("cursor") == true,
                        now = now,
                        account = systemCardLabel(usage, "cursor"),
                        errorCode = usage?.errorCodes?.get("cursor"),
                        onSignIn = signInFor("cursor"),
                    )
                    AccountUsageCards(usage, "cursor", now, onSignIn)
                    OpenCodeUsageCard(
                        usage?.opencode,
                        usage?.errors?.get("opencode"),
                        asOf = localizedFetchedAt(usage?.fetchedAt?.get("opencode"), now),
                        refreshing = usage?.refreshing?.contains("opencode") == true,
                        now = now,
                        onSignIn = signInFor("opencode"),
                    )
                    AccountUsageCards(usage, "opencode", now, onSignIn)
                    GrokUsageCard(
                        usage?.grok,
                        usage?.errors?.get("grok"),
                        asOf = localizedFetchedAt(usage?.fetchedAt?.get("grok"), now),
                        refreshing = usage?.refreshing?.contains("grok") == true,
                        now = now,
                        account = systemCardLabel(usage, "grok"),
                        errorCode = usage?.errorCodes?.get("grok"),
                        onSignIn = signInFor("grok"),
                    )
                    AccountUsageCards(usage, "grok", now, onSignIn)
                    AntigravityUsageCard(
                        usage?.antigravity,
                        usage?.errors?.get("antigravity"),
                        asOf = localizedFetchedAt(usage?.fetchedAt?.get("antigravity"), now),
                        refreshing = usage?.refreshing?.contains("antigravity") == true,
                        now = now,
                        account = systemCardLabel(usage, "antigravity"),
                        errorCode = usage?.errorCodes?.get("antigravity"),
                        onSignIn = signInFor("antigravity"),
                    )
                    AccountUsageCards(usage, "antigravity", now, onSignIn)
                }
            }
        }
    }
}

/**
 * One card per account added for [agent] (Settings → Accounts), under the system login's card.
 * They read the same way but offer no redeem: those endpoints spend the system login's resets.
 */
@Composable
private fun AccountUsageCards(usage: UsageResponse?, agent: String, now: Long, onSignIn: ((agent: String, accountId: String?) -> Unit)?) {
    usage?.accounts?.filter { it.agent == agent }?.forEach { a ->
        val asOf = localizedFetchedAt(a.fetchedAt, now)
        val signIn = onSignIn?.let { { it(agent, a.id) } }
        when {
            !a.quota -> NoQuotaAccountCard(a)
            agent == "claude" -> ClaudeUsageCard(a.claude, a.error, asOf, a.refreshing, now, account = a.label, accountId = a.id, errorCode = a.errorCode, onSignIn = signIn)
            agent == "codex" -> CodexUsageCard(a.codex, a.error, asOf = asOf, refreshing = a.refreshing, now = now, account = a.label, accountId = a.id, errorCode = a.errorCode, onSignIn = signIn)
            agent == "cursor" -> CursorUsageCard(a.cursor, a.error, asOf, a.refreshing, now, account = a.label, accountId = a.id, errorCode = a.errorCode, onSignIn = signIn)
            agent == "grok" -> GrokUsageCard(a.grok, a.error, asOf, a.refreshing, now, account = a.label, accountId = a.id, errorCode = a.errorCode, onSignIn = signIn)
            else -> NoQuotaAccountCard(a)
        }
    }
}

/**
 * An account with no quota to show: an API key, billed per token, or an added Antigravity sign-in, whose usage is not
 * read (agy may answer for the system login's keyring sign-in instead).
 */
@Composable
private fun NoQuotaAccountCard(account: AccountUsage) {
    val apiKey = account.method == "api_key"
    val systemOnly = account.agent == "antigravity" && !apiKey
    UsageCard(
        title = accountCardTitle(agentTitle(account.agent), account.label),
        subtitle = when {
            apiKey -> localized(Res.string.accounts_option_api_key)
            systemOnly -> localized(Res.string.usage_google_account)
            else -> localized(Res.string.usage_no_limits)
        },
        enabled = true,
        modifier = Modifier.testTag(accountCardTag("usage_card_${account.agent}", account.id)),
    ) {
        Text(
            localized(if (systemOnly) Res.string.usage_system_login_only else Res.string.usage_billed_per_token),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

private fun agentTitle(agent: String): String = when (agent) {
    "opencode" -> "opencode"
    else -> agent.replaceFirstChar { it.uppercase() }
}
