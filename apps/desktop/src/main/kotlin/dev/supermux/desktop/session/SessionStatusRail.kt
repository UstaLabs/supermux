// Ported from apps/android/src/main/kotlin/dev/supermux/android/session/SessionStatusRail.kt —
// keep in sync until a shared UI module exists. Android renders the git/cloud status via bundled
// vector drawables (R.drawable.ic_check etc.); desktop has no bundled icon set for those glyphs
// yet, so this uses the equivalent glyphs from compose.materialIconsExtended instead.
package dev.supermux.desktop.session

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
import dev.supermux.desktop.theme.LocalSemantics
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.SessionStatusKind
import dev.supermux.proto.SessionStatusLevel
import dev.supermux.proto.sessionStatus

/**
 * Leading per-session state, priority order:
 *  1. working spinner (hides unread — the agent is still busy)
 *  2. unread green dot when idle with a newer message than last_read_at
 *  3. git/cloud status icon (or a quiet gray neutral dot when pristine/unknown)
 *
 * `bgOpen` > 0 adds a static mono "⧗N" badge (open background tasks) — static because the
 * session list is a 100+/day surface and the design language budgets motion there.
 */
@Composable
fun SessionStatusRail(
    git: GitLiteStatusDto?,
    working: Boolean,
    bgOpen: Int = 0,
    unread: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val sem = LocalSemantics.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (bgOpen > 0) {
            Text("⧗$bgOpen", color = sem.warning, fontFamily = MonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(4.dp))
        }
        if (working) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            return@Row
        }
        if (unread) {
            UnreadDot(sem.success)
            return@Row
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

@Composable private fun NeutralDot() {
    Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)))
}

@Composable private fun UnreadDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
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
