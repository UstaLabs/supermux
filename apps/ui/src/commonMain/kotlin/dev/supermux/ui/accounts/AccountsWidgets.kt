// Small pieces every accounts surface shares (Settings, the login sheet, the new-chat picker and
// the session pill): the method chip, the usage meter, the tinted tag and the adaptive modal.
package dev.supermux.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material3.Icon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.supermux.net.AccountUsageWindowDto
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.widgets.Dialog

/** A tinted pill tag ("Subscription", "Isolated") — the RequestCard "Waiting for you" family. */
@Composable
internal fun AccountTag(text: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(Radii.pill), color = tint.copy(alpha = 0.14f), modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** The method chip in its own tone: subscription = brand, token = info, API key = neutral. */
@Composable
internal fun MethodChip(method: String, modifier: Modifier = Modifier) {
    val label = methodLabel(method) ?: return
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    val tint = when (method) {
        "subscription" -> sem.brand
        "token" -> sem.info
        else -> cs.onSurfaceVariant
    }
    AccountTag(label, tint, modifier)
}

/** Meter tint: calm until the window is nearly spent. */
@Composable
internal fun usageTint(usedPercent: Double): Color {
    val sem = LocalSemantics.current
    return when {
        usedPercent >= 95.0 -> sem.danger
        usedPercent >= 80.0 -> sem.warning
        else -> MaterialTheme.colorScheme.primary
    }
}

/**
 * One window: "5h" · a thin bar · "62% · resets in 1h 24m". Fixed name and bar widths so every
 * meter in a list lines up; on a narrow row the reset time drops under the bar instead of being cut.
 */
@Composable
internal fun UsageMeter(window: AccountUsageWindowDto, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val pct = window.usedPercent.coerceIn(0.0, 100.0)
    val tint = usageTint(pct)
    val name = usageWindowName(window.name)
    val detail = usageWindowText(window).removePrefix(name).trim()
    val percent = detail.substringBefore(" · ")
    val reset = detail.substringAfter(" · ", "")
    val textColor = if (pct >= 80.0) tint else cs.onSurfaceVariant
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val narrow = maxWidth < 300.dp
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(if (narrow) 44.dp else 52.dp),
                )
                Box(
                    Modifier
                        .then(if (narrow) Modifier.weight(1f) else Modifier.width(96.dp))
                        .height(4.dp)
                        .clip(RoundedCornerShape(Radii.pill))
                        .background(cs.onSurface.copy(alpha = 0.08f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((pct / 100.0).toFloat().coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(Radii.pill))
                            .background(tint),
                    )
                }
                Text(
                    if (narrow) percent else detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (narrow) Modifier.widthIn(min = 32.dp) else Modifier.weight(1f),
                )
            }
            if (narrow && reset.isNotBlank()) {
                Text(
                    reset,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = if (pct >= 80.0) 1f else 0.85f),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 44.dp + Space.sm),
                )
            }
        }
    }
}

/**
 * The accounts modal: a bottom sheet on a compact window, a centred card otherwise. Keyed on the
 * window WIDTH, not on pointer availability — a phone-width browser has a mouse and still needs
 * the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountsModal(
    onDismiss: () -> Unit,
    testTag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    if (LocalWindowWidthClass.current == WindowWidthClass.Compact) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = cs.surfaceContainerLow,
            contentColor = cs.onSurface,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = Space.lg, end = Space.lg, bottom = Space.xl)
                    .testTag(testTag),
                verticalArrangement = Arrangement.spacedBy(Space.md),
                content = content,
            )
        }
    } else {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                shape = RoundedCornerShape(Radii.lg),
                color = cs.surfaceContainerLow,
                border = BorderStroke(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.7f)),
                modifier = Modifier.padding(Space.lg).widthIn(max = 460.dp).fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(Space.xl).testTag(testTag),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                    content = content,
                )
            }
        }
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
    val exhausted = text.startsWith("Usage limit reached")
    val (lead, reason) = text.split(" — ", limit = 2).let { it[0] to it.getOrNull(1) }
    val prefix = if (exhausted) "Usage limit reached on " else "Switched to "
    val name = lead.removePrefix(prefix)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            shape = RoundedCornerShape(Radii.pill),
            color = cs.surfaceContainerHigh.copy(alpha = 0.6f),
            border = BorderStroke(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.widthIn(max = 520.dp).testTag("account-notice"),
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
                        withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(prefix) }
                        withStyle(SpanStyle(color = cs.onSurface, fontWeight = FontWeight.Medium)) { append(name) }
                        if (!reason.isNullOrBlank()) withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(" · $reason") }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
