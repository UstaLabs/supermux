// Ported from apps/android/src/main/kotlin/dev/supermux/android/workspace/AgentViewToggle.kt —
// keep in sync until a shared UI module exists. This is terminal UX (it flips the chat column to
// the raw agent PTY), so the M2 terminal plan pulls it forward from the deferred M4 header chrome.
//
// Icon substitutions (Android uses bundled vector drawables; desktop reuses the
// compose.materialIconsExtended stand-ins already established by PaneToggleCluster):
//   ic_sparkle  → Icons.Filled.AutoAwesome   (Chat)
//   ic_terminal → Icons.Filled.Terminal      (Native)
package dev.supermux.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The workspace header's Chat ⇄ Native main-view switch — a two-segment rounded pill (native match
 * for the iOS/Android `AgentViewToggle`) that flips the chat column between the transcript and the
 * agent's raw ("Native") terminal. The caller shows it only for agents that have a native view
 * (claude) and only while the chat pane is visible.
 *
 * Crucially it is a LABELLED pill, so it reads as its own control rather than a bare terminal icon
 * sitting next to the terminal *pane* toggle in [PaneToggleCluster] — that adjacency was the
 * "duplicate / ugly icons" the tablet header showed before.
 */
@Composable
fun AgentViewToggle(
    nativeView: Boolean,
    onSetNative: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(cs.surfaceContainerHighest)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Segment("Chat", Icons.Filled.AutoAwesome, selected = !nativeView, tag = "agent_view_chat") { onSetNative(false) }
        Segment("Native", Icons.Filled.Terminal, selected = nativeView, tag = "agent_view_native") { onSetNative(true) }
    }
}

@Composable
private fun Segment(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) cs.primary else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .height(28.dp)
            .padding(horizontal = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) cs.onPrimary else cs.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        Text(
            label,
            color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
