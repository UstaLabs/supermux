// #92: a session whose every account is out waits for the first usage-limit reset, then carries on
// by itself. Its session row says so, and a quiet strip docked above the composer says when and on
// which account, with Cancel and Resume now (the approved prototype, /p/limit-wait-proto/).
package dev.supermux.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.chatTimeLabel
import dev.supermux.chat.parseChatTs
import dev.supermux.proto.LimitWaitDto
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import kotlinx.coroutines.delay
import kotlin.time.Clock
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.limit_any_moment
import dev.supermux.ui.resources.limit_cancel
import dev.supermux.ui.resources.limit_in_hours_minutes
import dev.supermux.ui.resources.limit_in_minutes
import dev.supermux.ui.resources.limit_on_account
import dev.supermux.ui.resources.limit_resume_now
import dev.supermux.ui.resources.limit_row_checks
import dev.supermux.ui.resources.limit_row_resumes
import dev.supermux.ui.resources.limit_soon
import dev.supermux.ui.resources.limit_title_checks
import dev.supermux.ui.resources.limit_title_resumes

// The plain functions word in English: the tests' twins (#123). The strip and the session rows read the composable
// twins (localizedLimitWaitRowText …), which word the same in the app's language.

private fun at(wait: LimitWaitDto, nowMs: Long): String = chatTimeLabel(wait.resumeAt, nowMs) ?: "soon"

@Composable
private fun localizedAt(wait: LimitWaitDto, nowMs: Long): String =
    chatTimeLabel(wait.resumeAt, nowMs) ?: localized(Res.string.limit_soon)

/** The session row's line: "Waiting · resumes 18:13". */
fun limitWaitRowText(wait: LimitWaitDto, nowMs: Long): String =
    if (wait.resetsAt != null) "Waiting · resumes ${at(wait, nowMs)}" else "Waiting · checks again ${at(wait, nowMs)}" // i18n-ok: test twin

/** [limitWaitRowText] in the app's language. */
@Composable
fun localizedLimitWaitRowText(wait: LimitWaitDto, nowMs: Long): String =
    localized(if (wait.resetsAt != null) Res.string.limit_row_resumes else Res.string.limit_row_checks, localizedAt(wait, nowMs))

/** The strip's first line. */
fun limitWaitTitle(wait: LimitWaitDto, nowMs: Long): String =
    if (wait.resetsAt != null) "Usage limit reached. Resumes automatically at ${at(wait, nowMs)}"
    else "Usage limit reached. Checks again at ${at(wait, nowMs)}" // i18n-ok: test twin

/** [limitWaitTitle] in the app's language. */
@Composable
fun localizedLimitWaitTitle(wait: LimitWaitDto, nowMs: Long): String =
    localized(if (wait.resetsAt != null) Res.string.limit_title_resumes else Res.string.limit_title_checks, localizedAt(wait, nowMs))

private fun minutesLeft(wait: LimitWaitDto, nowMs: Long): Long = ((parseChatTs(wait.resumeAt) ?: nowMs) - nowMs) / 60_000L

/** The strip's second line: "in 1 h 24 min · on Work Max". */
fun limitWaitDetail(wait: LimitWaitDto, nowMs: Long): String {
    val left = minutesLeft(wait, nowMs)
    val whenText = when {
        left <= 0 -> "any moment now"
        left < 60 -> "in $left min"
        else -> "in ${left / 60} h ${left % 60} min"
    }
    return if (wait.accountLabel.isBlank()) whenText else "$whenText · on ${wait.accountLabel}" // i18n-ok: test twin
}

/** [limitWaitDetail] in the app's language. */
@Composable
fun localizedLimitWaitDetail(wait: LimitWaitDto, nowMs: Long): String {
    val left = minutesLeft(wait, nowMs)
    val whenText = when {
        left <= 0 -> localized(Res.string.limit_any_moment)
        left < 60 -> localized(Res.string.limit_in_minutes, left)
        else -> localized(Res.string.limit_in_hours_minutes, left / 60, left % 60)
    }
    return if (wait.accountLabel.isBlank()) whenText else localized(Res.string.limit_on_account, whenText, wait.accountLabel)
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
    val shape = RoundedCornerShape(Radii.md)
    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = Space.sm)
            .clip(shape)
            .background(warning.copy(alpha = 0.07f))
            .border(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.7f), shape)
            .testTag("limit-wait-strip"),
    ) {
        Row(
            Modifier.padding(start = Space.md, end = Space.xs, top = Space.sm, bottom = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Icon(Icons.Outlined.HourglassBottom, contentDescription = null, tint = warning, modifier = Modifier.size(17.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(localizedLimitWaitTitle(wait, now), color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text(localizedLimitWaitDetail(wait, now), color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            StripAction(localized(Res.string.limit_cancel), cs.onSurfaceVariant, onCancel, "limit-wait-cancel")
            StripAction(localized(Res.string.limit_resume_now), cs.onSurface, onResumeNow, "limit-wait-resume")
        }
    }
}

/** A quiet word to tap at the strip's end, with a full-size target. */
@Composable
private fun StripAction(text: String, color: Color, onClick: () -> Unit, tag: String) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(Radii.sm))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.sm)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 12.5.sp)
    }
}
