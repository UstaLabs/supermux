// The desktop Usage panel — a port of apps/android/.../settings/MoreScreens.kt's `UsageScreen` +
// its provider cards (ClaudeUsageCard/CodexUsageCard/CursorUsageCard) + UsageWindowRow/
// UsageFooterRow/UsageCard/formatReset/money/dollars/codexResetNote. Hosted inside
// [UsagePopover] (icon-anchored Popup above the sidebar footer Usage glyph — not a centered
// modal). File ▸ "Usage…", footer, and session ⋮ all call [ShellUiState.openUsage].
//
// Desktop deltas from Android:
//   - Consumes the TYPED `BrokerApi.usage()` (`UsageResponse`) instead of Android's `usageRaw()` +
//     org.json `parseUsage(JSONObject)` path — the shared kotlinx.serialization DTOs
//     (ClaudeUsage/CodexUsage/CursorUsage/...) already carry every field Android's private parsed
//     data classes had, so there is nothing to re-parse.
//   - `resetsAt` is typed per-provider at the source (BrokerApi.kt): Claude's `ClaudeWindow` and
//     Cursor's `billingCycleEnd` are ISO-8601 Strings; Codex's `CodexWindow` is a Double of epoch
//     SECONDS. Two formatter entry points below match that split — no stringify-then-reparse.
//   - kotlinx-datetime deviation: the M4f plan text says "use kotlinx-datetime (shared dep)", but
//     no module in this repo actually depends on it (checked shared/build.gradle.kts and
//     desktop/build.gradle.kts) — and Android's own `formatReset` uses `java.time`, not
//     kotlinx.datetime, despite the plan's framing. The desktop module already uses
//     `java.time.Instant` elsewhere (chat/Timeline.kt's gutter timestamps), and this task's ground
//     rules restrict changes to `apps/desktop/src` (no build.gradle.kts edits to add a dependency).
//     So [formatResetIso]/[formatResetEpochSeconds] inject a `java.time.Instant now` instead —
//     same determinism property the plan asked for, just the type actually on the classpath.
//   - TopAppBar → card header ("Usage" + close). Full-pane route → centered Surface card in AppShell.
//   - Redeem: `onRedeem` updates the codex card in place at the AppShell level (the popover
//     owns `usageData` and replaces `.codex` with the refreshed value on `code == "reset"`) rather
//     than Android's `onRefresh` re-fetching the whole usage payload.
package dev.supermux.desktop.usage

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import dev.supermux.desktop.ui.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.theme.LocalSemantics
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.net.ClaudeUsage
import dev.supermux.net.CodexResetResult
import dev.supermux.net.CodexUsage
import dev.supermux.net.CursorUsage
import dev.supermux.net.GrokUsage
import dev.supermux.net.OpenCodeUsage
import dev.supermux.net.UsageResponse
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// ─── Reset formatting (pure) ────────────────────────────────────────────────────────────────────

private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_HOUR = 3_600_000L
private const val MS_PER_DAY = 24 * MS_PER_HOUR

/**
 * Claude windows (`ClaudeWindow.resetsAt`) + Cursor's `billingCycleEnd`: an ISO-8601 string
 * (Android also falls back to a numeric epoch-millis string first). `now` is injected for
 * deterministic tests (see file header re: java.time vs kotlinx-datetime).
 */
fun formatResetIso(resetsAt: String?, now: Instant = Instant.now()): String {
    val s = resetsAt?.takeIf { it.isNotBlank() } ?: return ""
    val ms = s.toLongOrNull() ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrElse { return "" }
    return formatResetFromEpochMillis(ms, now)
}

/** Codex windows (`CodexWindow.resetsAt`): a Double of epoch SECONDS. */
fun formatResetEpochSeconds(resetsAt: Double?, now: Instant = Instant.now()): String {
    val secs = resetsAt ?: return ""
    return formatResetFromEpochMillis((secs * 1000.0).toLong(), now)
}

private fun formatResetFromEpochMillis(ms: Long, now: Instant): String {
    val diff = ms - now.toEpochMilli()
    if (diff <= 0) return "resets soon"
    if (diff < MS_PER_DAY) {
        val h = (diff / MS_PER_HOUR).toInt()
        val m = ((diff % MS_PER_HOUR) / MS_PER_MINUTE).toInt()
        return if (h > 0) "resets in ${h}h ${m}m" else "resets in ${m}m"
    }
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val month = date.month.getDisplayName(TextStyle.SHORT, Locale.US)
    return "resets $month ${date.dayOfMonth}"
}

private fun clampPct(v: Double): Double = v.coerceIn(0.0, 100.0)

private fun money(cents: Double): String = "$" + String.format(Locale.US, "%.2f", cents / 100.0)
private fun dollars(v: Double): String = "$" + String.format(Locale.US, "%.2f", v)

/** 1.2M / 34.5K / 912 — mirrors the web UsageView's formatTokens. */
private fun tokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

/** Bar colour by percentage: >=85 red, >=60 amber, else primary — ports Android's `barColor`. */
@Composable
private fun barColor(pct: Double): androidx.compose.ui.graphics.Color {
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
fun ClaudeUsageCard(claude: ClaudeUsage?, error: String?) {
    val cs = MaterialTheme.colorScheme
    UsageCard(
        title = "Claude",
        subtitle = "Pro plan",
        enabled = claude != null,
        modifier = Modifier.testTag("usage_card_claude"),
    ) {
        if (claude == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            UsageWindowRow("5-hour window", claude.fiveHour.used, formatResetIso(claude.fiveHour.resetsAt))
            UsageWindowRow("7-day window", claude.sevenDay.used, formatResetIso(claude.sevenDay.resetsAt))
            claude.sevenDaySonnet?.let { UsageWindowRow("7-day Sonnet", it.used, formatResetIso(it.resetsAt)) }
            claude.sevenDayFable?.let { UsageWindowRow("7-day Fable", it.used, formatResetIso(it.resetsAt)) }
            claude.extraUsage?.takeIf { it.enabled }?.let { e ->
                UsageFooterRow("Extra usage", "${dollars(e.usedCredits)} / ${dollars(e.monthlyLimit)}")
            }
        }
    }
}

/**
 * `onRedeem` — null hides the "Use a reset" affordance entirely (kept optional, mirroring
 * Android, though [UsageScreen] always supplies one). On confirm: spends 1 banked reset via
 * [onRedeem], shows the resulting [codexResetNote] inline. The CALLER (AppShell) is
 * responsible for swapping in the refreshed `CodexUsage` on `code == "reset"` — this card only
 * renders whatever `codex` it's given, so a parent-level re-composition after a successful redeem
 * is what makes the numbers move (see [UsageScreen]'s KDoc + the AppShell wiring).
 */
@Composable
fun CodexUsageCard(
    codex: CodexUsage?,
    error: String?,
    onRedeem: (suspend () -> CodexResetResult?)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // NOT keyed to `codex`: a successful redeem swaps the caller's `codex` for a refreshed value
    // (see UsageScreen's KDoc) — the note must survive that swap so "✓ Reset — cleared N window(s)"
    // stays visible under the now-updated numbers, matching Android (whose `note`/`redeeming` are
    // similarly un-keyed local state that outlives its own `reloadKey` re-fetch).
    var redeeming by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    UsageCard(
        title = "Codex",
        subtitle = codex?.plan?.takeIf { it.isNotBlank() } ?: "unknown",
        enabled = codex != null,
        modifier = Modifier.testTag("usage_card_codex"),
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
                    formatResetEpochSeconds(window.resetsAt),
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

/** Ports Android's `codexResetNote` — the transient inline status line after a redeem attempt. */
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
fun CursorUsageCard(cursor: CursorUsage?, error: String?) {
    val cs = MaterialTheme.colorScheme
    UsageCard(
        title = "Cursor",
        subtitle = "Billing cycle",
        enabled = cursor != null,
        modifier = Modifier.testTag("usage_card_cursor"),
    ) {
        if (cursor == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            UsageWindowRow("Usage", cursor.totalPercentUsed, formatResetIso(cursor.billingCycleEnd))
            if (cursor.spendAvailable) {
                UsageFooterRow("Spend", "${money(cursor.totalSpendCents)} / ${money(cursor.includedCents)} included")
            }
        }
    }
}

/** opencode has no subscription quota, so this shows cumulative local token/cost
 *  stats — same shape as the web UsageView card. */
@Composable
fun OpenCodeUsageCard(opencode: OpenCodeUsage?, error: String?) {
    val cs = MaterialTheme.colorScheme
    UsageCard(
        title = "opencode",
        subtitle = "Local usage · all time",
        enabled = opencode != null,
        modifier = Modifier.testTag("usage_card_opencode"),
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
fun GrokUsageCard(grok: GrokUsage?, error: String?) {
    val cs = MaterialTheme.colorScheme
    UsageCard(
        title = "Grok",
        subtitle = grok?.plan?.ifBlank { null } ?: "unknown",
        enabled = grok != null,
        modifier = Modifier.testTag("usage_card_grok"),
    ) {
        if (grok == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            UsageWindowRow("Monthly credits", grok.percentUsed, formatResetIso(grok.billingPeriodEnd))
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

// ─── UsageScreen ────────────────────────────────────────────────────────────────────────────────

/**
 * Usage card body: title row + close, then either a spinner (still loading), "Unable to load
 * usage data." (resolved to null), or the provider cards fed from [usage]. [loading] and
 * [usage] are both owned by the caller (AppShell fetches `app.usage()` once per open) — this
 * composable renders whatever point-in-time snapshot it's given. [onRedeem] is threaded straight
 * to [CodexUsageCard]; the caller is responsible for swapping in the refreshed codex usage on
 * `code == "reset"` (see AppShell's `usageData = usageData?.copy(codex = r.codex)`).
 *
 * Hosted inside [UsagePopover] (anchored to the footer Usage icon), not as a full-pane route.
 */
@Composable
fun UsageScreen(
    usage: UsageResponse?,
    loading: Boolean,
    onBack: () -> Unit,
    onRedeem: suspend () -> CodexResetResult?,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .testTag("usage_screen"),
    ) {
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
            IconButton(onClick = onBack, modifier = Modifier.testTag("usage_back")) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        HorizontalDivider(color = cs.outlineVariant)
        Box(Modifier.fillMaxSize()) {
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
                        ClaudeUsageCard(usage?.claude, usage?.errors?.get("claude"))
                        CodexUsageCard(usage?.codex, usage?.errors?.get("codex"), onRedeem = onRedeem)
                        CursorUsageCard(usage?.cursor, usage?.errors?.get("cursor"))
                        OpenCodeUsageCard(usage?.opencode, usage?.errors?.get("opencode"))
                        GrokUsageCard(usage?.grok, usage?.errors?.get("grok"))
                    }
                }
            }
        }
    }
}
