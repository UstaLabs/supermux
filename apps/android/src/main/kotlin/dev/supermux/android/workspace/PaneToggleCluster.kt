package dev.supermux.android.workspace

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.supermux.android.R
import dev.supermux.android.theme.Space

/**
 * Compact row of four [FilledIconToggleButton]s — Chat, Editor, Terminal, Display — that mirror and
 * drive [WorkspaceLayout.panesFor] for [sessionId] on the wide (tablet) workspace. Each button's
 * `checked` reflects the matching pane's visibility; tapping flips it via the matching
 * `layout.toggleX`. The [WorkspaceLayout] invariant auto-reopens Chat when the last work pane closes.
 *
 * [agentIsClaude] is accepted for parity with the pane set (the Native / agent-PTY view is
 * claude-only) and is reserved for a future toggle; the four panes here exist for every agent.
 */
@Composable
fun PaneToggleCluster(
    layout: WorkspaceLayout,
    sessionId: String,
    agentIsClaude: Boolean,
    modifier: Modifier = Modifier,
) {
    val panes = layout.panesFor(sessionId)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        PaneToggle(
            checked = panes.chat,
            onToggle = { layout.toggleChat(sessionId) },
            icon = R.drawable.ic_sparkle,
            description = "Toggle chat pane",
            tag = "pane_toggle_chat",
        )
        PaneToggle(
            checked = panes.editor,
            onToggle = { layout.toggleEditor(sessionId) },
            icon = R.drawable.ic_file,
            description = "Toggle editor pane",
            tag = "pane_toggle_editor",
        )
        PaneToggle(
            checked = panes.terminal,
            onToggle = { layout.toggleTerminal(sessionId) },
            icon = R.drawable.ic_terminal,
            description = "Toggle terminal pane",
            tag = "pane_toggle_terminal",
        )
        PaneToggle(
            checked = panes.display,
            onToggle = { layout.toggleDisplay(sessionId) },
            icon = R.drawable.ic_monitor,
            description = "Toggle display pane",
            tag = "pane_toggle_display",
        )
    }
}

@Composable
private fun PaneToggle(
    checked: Boolean,
    onToggle: () -> Unit,
    @DrawableRes icon: Int,
    description: String,
    tag: String,
) {
    FilledIconToggleButton(
        checked = checked,
        onCheckedChange = { onToggle() },
        modifier = Modifier.testTag(tag),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(20.dp),
        )
    }
}
