package dev.supermux.ui.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.shell_workspace_no_views

/**
 * What a workspace group with no views draws (workspace still open, last view closed — spec §9.3
 * answer 3).
 *
 * This is the workspace's wording, not the pane layer's: "view" and "workspace" are content
 * vocabulary, and the layer that draws groups and splits has no business knowing either. The layer
 * keeps the box and its bounds; this fills it.
 */
@Composable
fun WorkspaceEmptyHint() {
    val cs = MaterialTheme.colorScheme
    Text(
        localized(Res.string.shell_workspace_no_views),
        color = cs.onSurfaceVariant,
        fontSize = 13.sp,
    )
}
