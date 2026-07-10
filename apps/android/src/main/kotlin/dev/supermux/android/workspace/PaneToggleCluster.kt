package dev.supermux.android.workspace

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.supermux.android.R

/**
 * Compact pane show/hide capsule for the wide (tablet) workspace header — a native match for the
 * iOS `PaneToggleCluster`: four inline icon toggles (chat · terminal · editor · display) in one
 * rounded capsule, teal-filled when the pane is open, quiet when closed. Replaces the old bulky
 * row of [androidx.compose.material3.FilledIconToggleButton]s (each its own filled chip), which
 * read heavy and — with the editor as a plain document icon — looked off next to the header's
 * other controls. Order + icons mirror iOS (editor is `</>`, not a file glyph).
 *
 * Each toggle drives the matching `layout.toggleX`; the [WorkspaceLayout] never-empty invariant
 * auto-reopens Chat when the last work pane closes, and Chat is disabled while it is the only
 * open pane (parity with iOS `chatToggleDisabled`).
 */
@Composable
fun PaneToggleCluster(
    layout: WorkspaceLayout,
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val panes = layout.panesFor(sessionId)
    val chatOnly = !panes.editor && !panes.terminal && !panes.display
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(cs.surfaceContainerHighest)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PaneToggle(panes.chat, R.drawable.ic_sparkle, "Toggle chat", "pane_toggle_chat", enabled = !chatOnly) { layout.toggleChat(sessionId) }
        PaneToggle(panes.terminal, R.drawable.ic_terminal, "Toggle terminal", "pane_toggle_terminal") { layout.toggleTerminal(sessionId) }
        PaneToggle(panes.editor, R.drawable.ic_code, "Toggle editor", "pane_toggle_editor") { layout.toggleEditor(sessionId) }
        PaneToggle(panes.display, R.drawable.ic_monitor, "Toggle display", "pane_toggle_display") { layout.toggleDisplay(sessionId) }
    }
}

@Composable
private fun PaneToggle(
    checked: Boolean,
    @DrawableRes icon: Int,
    description: String,
    tag: String,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (checked) cs.primary else Color.Transparent)
            .size(width = 32.dp, height = 28.dp)
            .then(if (enabled) Modifier.clickable(onClick = onToggle) else Modifier)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = when {
                checked -> cs.onPrimary
                enabled -> cs.onSurfaceVariant
                else -> cs.onSurfaceVariant.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(16.dp),
        )
    }
}
