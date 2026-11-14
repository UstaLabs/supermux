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
import dev.supermux.net.AccountUsageWindowDto
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.usage.formatResetIso
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke

/** A tag ("Isolated", "Unavailable") as a word in its [tint], no pill. */
@Composable
internal fun AccountTag(text: String, tint: Color, modifier: Modifier = Modifier) {
    Text(text, fontSize = 11.5.sp, color = tint, maxLines = 1, modifier = modifier)
}

/** The method ("Subscription", "Token", "API key") as a dim word. */
@Composable
internal fun MethodChip(method: String, modifier: Modifier = Modifier) {
    val label = methodLabel(method) ?: return
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
    val name = usageWindowName(window.name)
    val reset = formatResetIso(window.resetsAt)
    val percent = localized(Str.usage_meter_percent, kotlin.math.round(pct).toInt())
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
    val sentence = localized(if (exhausted) Str.accounts_notice_exhausted else Str.accounts_notice_switched, name)
    val at = sentence.indexOf(name).takeIf { it >= 0 } ?: sentence.length
    val why = reason?.let { noticeReason(it) }
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
private fun noticeReason(reason: String): String {
    val other = NO_OTHER_ACCOUNT.find(reason)?.groupValues?.get(1)
    return when {
        reason == "usage limit reached" -> localized(Str.accounts_notice_reason_limit)
        reason == "switched manually" -> localized(Str.accounts_notice_reason_manual)
        other != null -> localized(Str.accounts_notice_reason_none, other)
        else -> reason
    }
}
