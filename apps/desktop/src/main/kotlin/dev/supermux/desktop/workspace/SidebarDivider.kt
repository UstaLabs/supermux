// Ported from apps/android/src/main/kotlin/dev/supermux/android/workspace/SidebarDivider.kt —
// keep in sync until a shared UI module exists.
//
// Desktop adaptations vs. the Android source:
//   - `statusBarsPadding()` dropped (no system status bar on desktop) and the chevron's top offset
//     shrunk to a plain 12dp — the desktop sidebar has no app bar for it to clear.
//   - Android's `R.drawable.ic_chevron_right` (rotated 180° for "collapse") → Icons.Filled.ChevronLeft
//     (compose.materialIconsExtended; desktop has no bundled icon set for that glyph).
//   - Desktop bonus: `pointerHoverIcon(PointerIcon.Hand)` on the drag gutter + chevron, noted inline.
package dev.supermux.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The resize gutter between the expanded sidebar and the detail: a hairline [outlineVariant] rule
 * inside a ~14dp-wide horizontal drag hit area (testTag `sidebar_divider`) whose drag reports a
 * width delta in dp via [onDragDelta] (px→dp conversion happens inside `pointerInput`, whose scope
 * is a Density). Near the top it hosts the collapse chevron (testTag `sidebar_collapse`,
 * [onCollapse]).
 *
 * [onStartDrag]/[onEndDrag] bracket a drag so the caller can suppress the collapse/expand width
 * animation while the user is actively resizing (otherwise the spring lags behind the finger).
 */
@Composable
fun SidebarDivider(
    onDragDelta: (Dp) -> Unit,
    onCollapse: () -> Unit,
    onStartDrag: () -> Unit = {},
    onEndDrag: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // Transparent overlay: the caller positions this over the sidebar↔detail seam (it holds no
    // layout width there), so the only thing that shows is the centered hairline + the chevron.
    Box(
        modifier
            .fillMaxHeight()
            .width(14.dp),
    ) {
        // Hairline centered on the seam.
        Box(
            Modifier
                .align(Alignment.Center)
                .width(1.dp)
                .fillMaxHeight()
                .background(cs.outlineVariant),
        )
        // Drag-to-resize hit area over the whole gutter.
        Box(
            Modifier
                .fillMaxSize()
                // Desktop bonus: grab cursor on hover over the resize gutter.
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onStartDrag() },
                        onDragEnd = { onEndDrag() },
                        onDragCancel = { onEndDrag() },
                    ) { _, drag -> onDragDelta(drag.x.toDp()) }
                }
                .testTag("sidebar_divider"),
        )
        // Collapse chevron, tucked just below the top of the sidebar. The 48dp touch target grows
        // DOWNward from the top offset; the visible chip stays ~24dp.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .size(48.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onCollapse)
                .testTag("sidebar_collapse"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(cs.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronLeft,
                    contentDescription = "Collapse sidebar",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
