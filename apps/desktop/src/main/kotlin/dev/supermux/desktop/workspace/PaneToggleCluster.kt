// Ported from apps/android/src/main/kotlin/dev/supermux/android/workspace/PaneToggleCluster.kt —
// keep in sync until a shared UI module exists.
//
// Icon substitutions (Android uses bundled vector drawables; desktop has no bundled icon set for
// these glyphs yet, so the closest compose.materialIconsExtended equivalents stand in):
//   ic_sparkle  → Icons.Filled.AutoAwesome   (chat)
//   ic_terminal → Icons.Filled.Terminal      (terminal)
//   ic_code     → Icons.Filled.Code          (editor, the `</>` glyph)
//   ic_monitor  → Icons.Filled.Monitor       (display)
package dev.supermux.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Compact pane show/hide capsule for the workspace header — a native match for the iOS/Android
 * `PaneToggleCluster`: four inline icon toggles (chat · terminal · editor · display) in one rounded
 * capsule, teal-filled when the pane is open, quiet when closed. Order + icons mirror the mobile
 * clients (editor is `</>`, not a file glyph).
 *
 * Each toggle drives the matching `layout.toggleX`; the [WorkspaceLayout] never-empty invariant
 * auto-reopens Chat when the last work pane closes, and Chat is disabled while it is the only open
 * pane (parity with the mobile `chatToggleDisabled`).
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
        PaneToggle(panes.chat, Icons.Filled.AutoAwesome, "Toggle chat", "pane_toggle_chat", enabled = !chatOnly) { layout.toggleChat(sessionId) }
        PaneToggle(panes.terminal, Icons.Filled.Terminal, "Toggle terminal", "pane_toggle_terminal") { layout.toggleTerminal(sessionId) }
        PaneToggle(panes.editor, Icons.Filled.Code, "Toggle editor", "pane_toggle_editor") { layout.toggleEditor(sessionId) }
        PaneToggle(panes.display, Icons.Filled.Monitor, "Toggle display", "pane_toggle_display") { layout.toggleDisplay(sessionId) }
    }
}

@Composable
private fun PaneToggle(
    checked: Boolean,
    icon: ImageVector,
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
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onToggle) else Modifier)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
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
