package dev.supermux.android.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.GitBadgeTone
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.gitBadge

/**
 * Per-session git badge: branch icon + `+N −M` for local (base), `↑N ↓M` for remote.
 * Renders nothing when [git] is null (non-repo session).
 */
@Composable
fun GitBadgeRow(git: GitLiteStatusDto?, modifier: Modifier = Modifier) {
    val badge = gitBadge(git) ?: return
    val cs = MaterialTheme.colorScheme
    val color: Color =
        if (badge.tone == GitBadgeTone.MUTED) cs.onSurfaceVariant.copy(alpha = 0.6f) else cs.onSurface
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (badge.kind == GitBadgeKind.BASE) {
            Icon(
                painter = painterResource(R.drawable.ic_git_branch),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(11.dp),
            )
        }
        Text(badge.text, color = color, fontFamily = MonoFontFamily, fontSize = 10.sp)
    }
}
