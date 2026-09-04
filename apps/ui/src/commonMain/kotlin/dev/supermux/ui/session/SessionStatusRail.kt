// The one session-status rail. Git/cloud glyphs come from compose.materialIconsExtended, which
// both apps already ship — Android's bundled `R.drawable.ic_check`/`ic_git_branch`/`ic_cloud_*`
// vectors are no longer used here.
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.SessionStatusKind
import dev.supermux.proto.SessionStatusLevel
import dev.supermux.proto.sessionStatus
import dev.supermux.session.SessionListRailIndicator
import dev.supermux.session.sessionListRailIndicator
import androidx.compose.ui.platform.testTag

/**
 * Leading per-session state, priority order:
 *  1. working spinner (hides unread — the agent is still busy)
 *  2. unread green dot when idle with a newer message than last_read_at
 *  3. git/cloud status icon (or a quiet gray neutral dot when pristine/unknown)
 *
 * `bgOpen` > 0 adds a static mono "⧗N" badge (open background tasks) — static because the
 * session list is a 100+/day surface and the design language budgets motion there.
 *
 * @param unreadTestTag optional Compose test tag for the unread dot (phone UI tests key on it).
 */
@Composable
fun SessionStatusRail(
    git: GitLiteStatusDto?,
    working: Boolean,
    bgOpen: Int = 0,
    unread: Boolean = false,
    unreadTestTag: String? = "session_rail_unread",
    modifier: Modifier = Modifier,
) {
    val sem = LocalSemantics.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (bgOpen > 0) {
            Text("⧗$bgOpen", color = sem.warning, fontFamily = MonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(4.dp))
        }
        when (sessionListRailIndicator(working = working, unread = unread)) {
            SessionListRailIndicator.Working -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(14.dp)
                        .testTag("session_rail_working")
                        .semantics { contentDescription = "working" },
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                return@Row
            }
            SessionListRailIndicator.Unread -> {
                UnreadDot(sem.success, unreadTestTag)
                return@Row
            }
            SessionListRailIndicator.Other -> Unit
        }
        val st = sessionStatus(git)
        when {
            st == null || (st.kind == SessionStatusKind.WORKTREE && st.level == SessionStatusLevel.PRISTINE) ->
                NeutralDot()
            st.kind == SessionStatusKind.WORKTREE && st.level == SessionStatusLevel.DONE ->
                StatusIcon(Icons.Filled.Check, sem.success)
            st.kind == SessionStatusKind.WORKTREE ->
                StatusIcon(Icons.AutoMirrored.Filled.CallSplit, sem.warning)
            st.kind == SessionStatusKind.REMOTE && st.level == SessionStatusLevel.DONE ->
                StatusIcon(Icons.Filled.CloudDone, sem.success)
            else ->
                StatusIcon(Icons.Filled.CloudOff, sem.warning)
        }
    }
}

@Composable private fun StatusIcon(icon: ImageVector, color: Color) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
}

/** Quiet idle mark — intentionally smaller/dimmer than [UnreadDot]. */
@Composable private fun NeutralDot() {
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            .testTag("session_rail_neutral")
            .semantics { contentDescription = "idle" },
    )
}

/**
 * Unread attention mark: larger than the neutral gray (7dp core vs 6dp gray), solid success
 * green with a soft outer ring so it reads clearly against dark and light list surfaces.
 */
@Composable private fun UnreadDot(color: Color, testTag: String?) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics { contentDescription = "unread" }
            .border(width = 1.5.dp, color = color.copy(alpha = 0.35f), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}
