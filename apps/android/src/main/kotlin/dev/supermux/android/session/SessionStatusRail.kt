package dev.supermux.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.supermux.android.R
import dev.supermux.android.theme.LocalSemantics
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.SessionStatusKind
import dev.supermux.proto.SessionStatusLevel
import dev.supermux.proto.sessionStatus

/**
 * Leading per-session state: working spinner (top priority), else the git/cloud status icon.
 * Worktree: ✓ done / ⎇ not-done / neutral pristine. Remote: cloud-done / cloud-off.
 * Numbers (ahead/behind/dirty) are intentionally omitted here — the list is icon-only; the
 * session view surfaces the counts.
 */
@Composable
fun SessionStatusRail(git: GitLiteStatusDto?, working: Boolean, modifier: Modifier = Modifier) {
    val sem = LocalSemantics.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (working) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            return@Row
        }
        val st = sessionStatus(git)
        when {
            st == null || (st.kind == SessionStatusKind.WORKTREE && st.level == SessionStatusLevel.PRISTINE) ->
                NeutralDot()
            st.kind == SessionStatusKind.WORKTREE && st.level == SessionStatusLevel.DONE ->
                StatusIcon(R.drawable.ic_check, sem.success)
            st.kind == SessionStatusKind.WORKTREE ->
                StatusIcon(R.drawable.ic_git_branch, sem.warning)
            st.kind == SessionStatusKind.REMOTE && st.level == SessionStatusLevel.DONE ->
                StatusIcon(R.drawable.ic_cloud_done, sem.success)
            else ->
                StatusIcon(R.drawable.ic_cloud_off, sem.warning)
        }
    }
}

@Composable private fun StatusIcon(res: Int, color: Color) {
    Icon(painterResource(res), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
}

@Composable private fun NeutralDot() {
    Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)))
}
