// Ported from apps/android/src/main/kotlin/dev/supermux/android/editor/EditorTabs.kt — keep in
// sync until a shared UI module exists.
//
// Desktop adaptations vs. the Android source:
//   - Bundled drawable ic_x → Icons.Filled.Close (established mapping; see TerminalTabs.kt /
//     DesktopComposer.kt).
//   - `rememberHaptics()(HapticKind.Tick)` on select/close dropped — see FileTree.kt's note; no
//     haptic actuator on desktop, and no ported desktop file wires the no-op haptics stub at a call
//     site.
//   - `pointerHoverIcon(PointerIcon.Hand)` added to the chip and its close glyph (desktop mouse
//     affordance; Android is touch-only). The loading chip stays non-interactive (default arrow).
package dev.supermux.desktop.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.theme.LocalPanes
import dev.supermux.desktop.theme.MonoFontFamily

@Composable
fun EditorTabs(
    tabs: List<EditorTab>,
    activeTabPath: String?,
    loadingPath: String? = null,
    isDirty: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()

    if (tabs.isEmpty() && loadingPath == null) return

    Row(
        modifier
            .fillMaxWidth()
            .background(cs.surfaceContainerHigh)
            .horizontalScroll(scroll),
        // Square flush tabs — no strip padding / no inter-tab gap.
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            TabChip(
                label = tab.path.substringAfterLast('/'),
                active = tab.path == activeTabPath,
                dirty = isDirty(tab.path),
                loading = false,
                onSelect = { onSelect(tab.path) },
                onClose = { onClose(tab.path) },
            )
        }
        if (loadingPath != null) {
            TabChip(
                label = loadingPath.substringAfterLast('/'),
                active = false,
                dirty = false,
                loading = true,
                onSelect = {},
                onClose = {},
            )
        }
    }
}

@Composable
private fun TabChip(
    label: String,
    active: Boolean,
    dirty: Boolean,
    loading: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(28.dp)
            // Square tabs; horizontal padding so label + × look centered.
            .background(if (active) cs.surfaceContainer else Color.Transparent)
            .clickable(enabled = !loading) { onSelect() }
            .pointerHoverIcon(if (loading) PointerIcon.Default else PointerIcon.Hand)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = cs.onSurfaceVariant,
                )
            }
            dirty -> {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(c.warning)),
                )
            }
        }
        Text(
            label,
            color = if (active) cs.onSurface else cs.onSurfaceVariant,
            fontFamily = MonoFontFamily,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!loading) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close",
                tint = cs.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onClose() }
                    .pointerHoverIcon(PointerIcon.Hand),
            )
        }
    }
}
