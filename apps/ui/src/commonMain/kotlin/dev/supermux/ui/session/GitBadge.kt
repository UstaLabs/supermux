package dev.supermux.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.proto.GitBadge
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.GitBadgeTone
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.gitBadge
import dev.supermux.ui.theme.MonoFontFamily

/**
 * The header label for a rendered [GitBadge]: BASE-kind badges prefix the compare ref (e.g.
 * `main +2 ·1`), every other kind is just the glyph text.
 *
 * Desktop's `GitBadgeMenu` (workspace header, cluster G) renders this; [GitBadgeRow] renders the
 * same badge as a row leaf. One derivation of the badge, one label rule.
 */
fun headerGitBadgeLabel(badge: GitBadge): String =
    if (badge.kind == GitBadgeKind.BASE && badge.compareRef.isNotEmpty())
        "${badge.compareRef} ${badge.text}"
    else badge.text

/**
 * Per-session git badge: branch icon + `+N −M` for local (base), `↑N ↓M` for remote.
 * Renders nothing when [git] is null (non-repo session).
 *
 * The branch glyph is `Icons.AutoMirrored.Filled.CallSplit` — the Material stand-in for Android's
 * bundled `R.drawable.ic_git_branch`, already used by [SessionStatusRail].
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
                imageVector = Icons.AutoMirrored.Filled.CallSplit,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(11.dp),
            )
        }
        Text(badge.text, color = color, fontFamily = MonoFontFamily, fontSize = 10.sp)
    }
}
