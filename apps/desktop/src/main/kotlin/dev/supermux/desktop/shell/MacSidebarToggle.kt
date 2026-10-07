package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * macOS title-bar sidebar collapse control — next to the traffic lights (live JBR left
 * inset, or a small gutter in native fullscreen when the lights hide), **only while the
 * sidebar is expanded**. When collapsed, expand is the rail chevron only (no title-bar toggle).
 *
 * Cluster G8 moved the shell into `:ui`; this is one of the leaves that stayed behind, because
 * nothing but a macOS window has a title bar to hang it in. `Main.kt` hands it to
 * `SupermuxApp(sidebarChrome = …)`.
 */
@Composable
fun MacSidebarToggle(
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .height(MacTitleBarHeight)
            .padding(start = LocalMacTrafficLightsInset.current)
            .testTag("mac_sidebar_toggle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onCollapse,
            modifier = Modifier
                .size(MacTitleBarHeight)
                .pointerHoverIcon(PointerIcon.Hand)
                // Inside the sidebar-band drag region — keep the button client-only so a drag or
                // double-click on it never moves/zooms the window (MacWindowChrome.kt).
                .macTitleBarNoDragRegion("sidebar-toggle")
                .testTag("sidebar_collapse_title"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuOpen,
                contentDescription = "Hide sidebar",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
