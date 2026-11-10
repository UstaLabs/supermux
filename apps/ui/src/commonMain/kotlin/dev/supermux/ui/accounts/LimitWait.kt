// #92: a session whose every account is out waits for the first usage-limit reset, then carries on
// by itself. Its session row says so, and a quiet strip docked above the composer says when and on
// which account, with Cancel and Resume now (the approved prototype, /p/limit-wait-proto/).
package dev.supermux.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.chatTimeLabel
import dev.supermux.chat.parseChatTs
import dev.supermux.proto.LimitWaitDto
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import kotlinx.coroutines.delay
import kotlin.time.Clock

private fun at(wait: LimitWaitDto, nowMs: Long): String = chatTimeLabel(wait.resumeAt, nowMs) ?: "soon"

/** The session row's line: "Waiting · resumes 18:13". */
fun limitWaitRowText(wait: LimitWaitDto, nowMs: Long): String =
    if (wait.resetsAt != null) "Waiting · resumes ${at(wait, nowMs)}" else "Waiting · checks again ${at(wait, nowMs)}"

/** The strip's first line. */
fun limitWaitTitle(wait: LimitWaitDto, nowMs: Long): String =
    if (wait.resetsAt != null) "Usage limit reached. Resumes automatically at ${at(wait, nowMs)}"
    else "Usage limit reached. Checks again at ${at(wait, nowMs)}"

/** The strip's second line: "in 1 h 24 min · on Work Max". */
fun limitWaitDetail(wait: LimitWaitDto, nowMs: Long): String {
    val left = ((parseChatTs(wait.resumeAt) ?: nowMs) - nowMs) / 60_000L
    val whenText = when {
        left <= 0 -> "any moment now"
        left < 60 -> "in $left min"
        else -> "in ${left / 60} h ${left % 60} min"
    }
    return if (wait.accountLabel.isBlank()) whenText else "$whenText · on ${wait.accountLabel}"
}

/** Ticks once a minute, so "in N min" stays true while the strip is on screen. */
@Composable
private fun rememberMinuteClock(): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - now % 60_000L)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    return now
}

/** The wait, docked above the composer: when it resumes, on which account; Cancel and Resume now. */
@Composable
fun LimitWaitStrip(
    wait: LimitWaitDto,
    onCancel: () -> Unit,
    onResumeNow: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: Long? = null,
) {
    val cs = MaterialTheme.colorScheme
    val warning = LocalSemantics.current.warning
    val now = nowMs ?: rememberMinuteClock()
    Surface(
        shape = RoundedCornerShape(Radii.md),
        color = warning.copy(alpha = 0.07f),
        border = BorderStroke(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.7f)),
        modifier = modifier.fillMaxWidth().padding(bottom = Space.sm).testTag("limit-wait-strip"),
    ) {
        Row(
            Modifier.padding(start = Space.md, end = Space.xs, top = Space.sm, bottom = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Icon(Icons.Outlined.HourglassBottom, contentDescription = null, tint = warning, modifier = Modifier.size(17.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(limitWaitTitle(wait, now), color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text(limitWaitDetail(wait, now), color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("limit-wait-cancel")) {
                Text("Cancel", color = cs.onSurfaceVariant, fontSize = 12.5.sp)
            }
            TextButton(onClick = onResumeNow, modifier = Modifier.testTag("limit-wait-resume")) {
                Text("Resume now", color = cs.onSurface, fontSize = 12.5.sp)
            }
        }
    }
}
