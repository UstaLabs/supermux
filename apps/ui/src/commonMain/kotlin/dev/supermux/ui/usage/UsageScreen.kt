// The one Usage screen for both apps (cluster E6).
//
// Base = desktop's `usage/UsageScreen.kt`: the typed `UsageResponse` DTOs, the per-provider
// "as of …" caption, the per-provider refreshing spinner, the opencode card, the clamped
// percentage, and the redeem confirm whose note survives the card's numbers changing under it.
// Android's `MoreScreens.kt` Usage section contributes the Compact branch — its `TopAppBar` with
// Back + Refresh — and, with this task, LOSES its hand-written JSON parser: Android's `parseUsage`,
// its nine private data classes and the `usageRaw()` wrapper that fed them are gone; the shared
// typed DTOs already carry every field they had. Android's post-redeem re-fetch survives as [UsageActions.refresh] being
// re-run by the stateful overload.
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
import dev.supermux.net.ClaudeUsage
import dev.supermux.net.CodexResetResult
import dev.supermux.net.CodexUsage
import dev.supermux.net.CursorUsage
import dev.supermux.net.GrokUsage
import dev.supermux.net.OpenCodeUsage
import dev.supermux.net.UsageResponse
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.chat.parseChatTs
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
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.time.Clock

// ─── Reset formatting (pure) ────────────────────────────────────────────────────────────────────

private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_HOUR = 3_600_000L
private const val MS_PER_DAY = 24 * MS_PER_HOUR

/** Material's minimum touch target, applied to the header actions when there is no pointer. */
private val TouchTargetMin = 48.dp

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Claude windows (`ClaudeWindow.resetsAt`) + Cursor's `billingCycleEnd`: an ISO-8601 string
 * (both apps also fall back to a numeric epoch-millis string first). [now] is epoch millis,
 * injected for deterministic tests.
 */
fun formatResetIso(resetsAt: String?, now: Long = nowMs()): String {
    val s = resetsAt?.takeIf { it.isNotBlank() } ?: return ""
    val ms = s.toLongOrNull() ?: parseIsoMillis(s) ?: return ""
    return formatResetFromEpochMillis(ms, now)
}

/** Codex windows (`CodexWindow.resetsAt`): a Double of epoch SECONDS. */
fun formatResetEpochSeconds(resetsAt: Double?, now: Long = nowMs()): String {
    val secs = resetsAt ?: return ""
    return formatResetFromEpochMillis((secs * 1000.0).toLong(), now)
}

/**
 * Per-provider "as of <relative>" caption from [UsageResponse.fetchedAt] (ISO-8601, or a
 * numeric epoch-millis string). Blank when missing/unparseable — the card then omits the line.
 */
fun formatFetchedAt(fetchedAt: String?, now: Long = nowMs()): String {
    val s = fetchedAt?.takeIf { it.isNotBlank() } ?: return ""
    val ms = s.toLongOrNull() ?: parseIsoMillis(s) ?: return ""
    val diffSec = ((now - ms) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "as of just now"
        diffSec < 3600L -> "as of ${diffSec / 60}m ago"
        diffSec < 86_400L -> "as of ${diffSec / 3600}h ago"
        else -> "as of ${diffSec / 86_400}d ago"
    }
}

/**
 * ISO-8601 → epoch millis, the shared parser both apps' chat timestamps use. `java.time`'s
 * `Instant.parse` is not available here; [parseChatTs] accepts the same shapes (and, additionally,
 * a bare epoch — harmless, since the callers try `toLongOrNull()` first anyway).
 */
private fun parseIsoMillis(s: String): Long? = parseChatTs(s)

private fun formatResetFromEpochMillis(ms: Long, now: Long): String {
    val diff = ms - now
    if (diff <= 0) return "resets soon"
    if (diff < MS_PER_DAY) {
        val h = (diff / MS_PER_HOUR).toInt()
        val m = ((diff % MS_PER_HOUR) / MS_PER_MINUTE).toInt()
        return if (h > 0) "resets in ${h}h ${m}m" else "resets in ${m}m"
    }
    val label = shortMonthDayLabel(ms)
    return if (label.isEmpty()) "" else "resets $label"
}

private fun clampPct(v: Double): Double = v.coerceIn(0.0, 100.0)

/**
 * `"%.<decimals>f"` without `java.util.Formatter`: half-away-from-zero on the scaled magnitude,
 * which is what `String.format(Locale.US, "%.2f", …)` produced for every value these cards show.
 */
internal fun fixed(v: Double, decimals: Int): String {
    if (v.isNaN() || v.isInfinite()) return v.toString()
    var scale = 1L
    repeat(decimals) { scale *= 10L }
    val scaled = round(abs(v) * scale).toLong()
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
            Text("${pct.roundToInt()}% used", color = cs.onSurface, fontSize = 12.sp)
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

/** A footer row separated by a top divider (extra usage / credits / spend). */
@Composable
fun UsageFooterRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = Space.sm)) {
        HorizontalDivider(color = cs.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(top = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(value, color = cs.onSurface, fontSize = 12.sp)
        }
    }
}

// ─── Provider cards ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ClaudeUsageCard(
    claude: ClaudeUsage?,
    error: String?,
    asOf: String? = null,
    refreshing: Boolean = false,
    now: Long = nowMs(),
) {
    val cs = MaterialTheme.colorScheme
    UsageCard(
        title = "Claude",
        subtitle = "Pro plan",
        enabled = claude != null,
        modifier = Modifier.testTag("usage_card_claude"),
        asOf = asOf,
        asOfTag = "usage_as_of_claude",
        refreshing = refreshing,
        refreshingTag = "usage_refreshing_claude",
    ) {
        if (claude == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            UsageWindowRow("5-hour window", claude.fiveHour.used, formatResetIso(claude.fiveHour.resetsAt, now))
            UsageWindowRow("7-day window", claude.sevenDay.used, formatResetIso(claude.sevenDay.resetsAt, now))
            claude.sevenDaySonnet?.let { UsageWindowRow("7-day Sonnet", it.used, formatResetIso(it.resetsAt, now)) }
            claude.sevenDayFable?.let { UsageWindowRow("7-day Fable", it.used, formatResetIso(it.resetsAt, now)) }
            claude.extraUsage?.takeIf { it.enabled }?.let { e ->
                UsageFooterRow("Extra usage", "${dollars(e.usedCredits)} / ${dollars(e.monthlyLimit)}")
            }
        }
    }
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
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // NOT keyed to `codex`: a successful redeem swaps the caller's `codex` for a refreshed value
    // (see UsageScreen's KDoc) — the note must survive that swap so "✓ Reset — cleared N window(s)"
    // stays visible under the now-updated numbers, matching both originals (whose `note`/`redeeming`
    // are similarly un-keyed local state that outlives the re-fetch).
    var redeeming by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    UsageCard(
        title = "Codex",
        subtitle = codex?.plan?.takeIf { it.isNotBlank() } ?: "unknown",
        enabled = codex != null,
        modifier = Modifier.testTag("usage_card_codex"),
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
                    Text("limit reached", color = cs.error, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        } else null,
    ) {
        if (codex == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            codex.windows.forEach { window ->
                UsageWindowRow(
                    window.label,
                    window.used,
                    formatResetEpochSeconds(window.resetsAt, now),
                )
            }
            codex.credits?.takeIf { it.hasCredits }?.let { cr ->
                UsageFooterRow("Credits balance", "${cr.balance} credits")
            }
            UsageFooterRow("🎟️ Resets banked", "${codex.resetCredits}")
            if (codex.resetCredits > 0 && onRedeem != null) {
                OutlinedButton(
                    onClick = { showDialog = true },
                    enabled = !redeeming,
                    modifier = Modifier.padding(top = Space.sm).testTag("codex_redeem_button"),
                ) {
                    Text(if (redeeming) "Redeeming…" else "Use a reset", color = cs.onSurface, fontSize = 13.sp)
                }
            }
            note?.let {
                Text(
                    it,
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = Space.xs).testTag("codex_redeem_note"),
                )
            }
        }
    }
    if (showDialog && codex != null) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Use a banked reset?") },
            text = { Text("Spends 1 of ${codex.resetCredits} to clear your rate-limit windows now.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        scope.launch {
                            redeeming = true
                            val r = onRedeem?.invoke()
                            note = codexResetNote(r)
                            redeeming = false
                            onRedeemed()
                        }
                    },
                    modifier = Modifier.testTag("codex_redeem_confirm"),
                ) { Text("Use reset", color = cs.primary) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDialog = false },
                    modifier = Modifier.testTag("codex_redeem_cancel"),
                ) { Text("Cancel") }
            },
        )
    }
}

/** Both apps' `codexResetNote` — the transient inline status line after a redeem attempt. */
fun codexResetNote(r: CodexResetResult?): String {
    if (r == null) return "Reset failed"
    return when (r.code) {
        "reset" -> "✓ Reset — cleared ${r.windowsReset} window${if (r.windowsReset == 1) "" else "s"}"
        "nothing_to_reset" -> "Nothing to reset right now"
        "no_credit" -> "No banked resets left"
        "already_redeemed" -> "That reset was already redeemed"
        else -> "Reset request completed"
    }
}

@Composable
fun CursorUsageCard(
    cursor: CursorUsage?,
    error: String?,
    asOf: String? = null,
    refreshing: Boolean = false,
    now: Long = nowMs(),
) {
    val cs = MaterialTheme.colorScheme
    UsageCard(
        title = "Cursor",
        subtitle = "Billing cycle",
        enabled = cursor != null,
        modifier = Modifier.testTag("usage_card_cursor"),
        asOf = asOf,
        asOfTag = "usage_as_of_cursor",
        refreshing = refreshing,
        refreshingTag = "usage_refreshing_cursor",
    ) {
        if (cursor == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            UsageWindowRow("Usage", cursor.totalPercentUsed, formatResetIso(cursor.billingCycleEnd, now))
            if (cursor.spendAvailable) {
                UsageFooterRow("Spend", "${money(cursor.totalSpendCents)} / ${money(cursor.includedCents)} included")
            }
        }
    }
}

/** opencode has no subscription quota, so this shows cumulative local token/cost
 *  stats — same shape as the web UsageView card. */
@Composable
fun OpenCodeUsageCard(
    opencode: OpenCodeUsage?,
    error: String?,
    asOf: String? = null,
    refreshing: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    UsageCard(
        title = "opencode",
        subtitle = "Local usage · all time",
        enabled = opencode != null,
        modifier = Modifier.testTag("usage_card_opencode"),
        asOf = asOf,
        asOfTag = "usage_as_of_opencode",
        refreshing = refreshing,
        refreshingTag = "usage_refreshing_opencode",
        badge = opencode?.let { u -> { Text(dollars(u.totalCostUsd), color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) } },
    ) {
        if (opencode == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            UsageFooterRow("Input", tokens(opencode.inputTokens))
            UsageFooterRow("Output", tokens(opencode.outputTokens))
            UsageFooterRow("Cache read", tokens(opencode.cacheReadTokens))
            UsageFooterRow("Cache write", tokens(opencode.cacheWriteTokens))
            UsageFooterRow("Activity", "${opencode.sessions} sessions · ${opencode.messages} messages")
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
) {
    val cs = MaterialTheme.colorScheme
    UsageCard(
        title = "Grok",
        subtitle = grok?.plan?.ifBlank { null } ?: "unknown",
        enabled = grok != null,
        modifier = Modifier.testTag("usage_card_grok"),
        asOf = asOf,
        asOfTag = "usage_as_of_grok",
        refreshing = refreshing,
        refreshingTag = "usage_refreshing_grok",
    ) {
        if (grok == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            UsageWindowRow("Monthly credits", grok.percentUsed, formatResetIso(grok.billingPeriodEnd, now))
            if (grok.monthlyLimit > 0) {
                UsageFooterRow("Credits", "${grok.used.toLong()} / ${grok.monthlyLimit.toLong()}")
            }
            if (grok.onDemandCap > 0) {
                UsageFooterRow("On-demand", "${grok.onDemandUsed.toLong()} / ${grok.onDemandCap.toLong()}")
            }
            if (grok.prepaidBalance > 0) {
                UsageFooterRow("Prepaid balance", "${grok.prepaidBalance.toLong()}")
            }
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
) {
    val usage by actions.snapshot.collectAsState()
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
        onRefresh = {
            loading = actions.snapshot.value == null
            actions.refresh()
            loading = false
        },
        now = now,
        modifier = modifier,
        topBarShown = topBarShown,
        standalone = standalone,
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
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    val barOwned = (standalone || compact) && !topBarShown

    if (barOwned) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Usage", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("usage_back")) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
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
                                    contentDescription = "Refresh",
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
            UsageBody(usage, loading, onRedeem, now, onRedeemed, modifier.padding(padding))
        }
    } else {
        Column(modifier.fillMaxSize().testTag("usage_screen")) {
            UsageHeaderRow(loading, onBack, onRefresh)
            HorizontalDivider(color = cs.outlineVariant)
            UsageBody(usage, loading, onRedeem, now, onRedeemed, Modifier)
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
            "Usage",
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
                    contentDescription = "Refresh",
                    tint = if (loading) cs.onSurfaceVariant else cs.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onBack, modifier = actionSize.testTag("usage_back")) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close",
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
    now: Long,
    onRedeemed: () -> Unit,
    modifier: Modifier,
) {
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
                    "Unable to load usage data.",
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
                        asOf = formatFetchedAt(usage?.fetchedAt?.get("claude"), now),
                        refreshing = usage?.refreshing?.contains("claude") == true,
                        now = now,
                    )
                    CodexUsageCard(
                        usage?.codex,
                        usage?.errors?.get("codex"),
                        onRedeem = onRedeem,
                        asOf = formatFetchedAt(usage?.fetchedAt?.get("codex"), now),
                        refreshing = usage?.refreshing?.contains("codex") == true,
                        now = now,
                        onRedeemed = onRedeemed,
                    )
                    CursorUsageCard(
                        usage?.cursor,
                        usage?.errors?.get("cursor"),
                        asOf = formatFetchedAt(usage?.fetchedAt?.get("cursor"), now),
                        refreshing = usage?.refreshing?.contains("cursor") == true,
                        now = now,
                    )
                    OpenCodeUsageCard(
                        usage?.opencode,
                        usage?.errors?.get("opencode"),
                        asOf = formatFetchedAt(usage?.fetchedAt?.get("opencode"), now),
                        refreshing = usage?.refreshing?.contains("opencode") == true,
                    )
                    GrokUsageCard(
                        usage?.grok,
                        usage?.errors?.get("grok"),
                        asOf = formatFetchedAt(usage?.fetchedAt?.get("grok"), now),
                        refreshing = usage?.refreshing?.contains("grok") == true,
                        now = now,
                    )
                }
            }
        }
    }
}
