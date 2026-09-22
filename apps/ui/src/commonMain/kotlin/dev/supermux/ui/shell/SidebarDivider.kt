// Shared sidebar↔detail resize seam (UI cluster B, task B3). Two composables, because the two apps
// place the seam differently and neither placement is a mode of the other:
//
//   [SidebarDivider]        — desktop's OVERLAY (seam tokens shared with the pane splitters,
//                             hover/drag hairline animation, no collapse chip). The caller must put
//                             it in a parent Box and offset it by [SidebarDividerCenterOffset].
//   [CompactSidebarDivider] — Android's 14dp strip that takes real layout width and hosts the
//                             collapse chevron. Used by Android's compact/tablet shell.
//
// Both carry the `sidebar_divider` drag-hit test tag; only the compact one carries
// `sidebar_collapse`.
package dev.supermux.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.supermux.ui.panes.ColResizeIcon
import dev.supermux.ui.panes.SplitSeamCenterOffset
import dev.supermux.ui.panes.SplitSeamHairline
import dev.supermux.ui.panes.SplitSeamHitWidth

/**
 * Overlay on the sidebar↔detail seam: 1dp hairline + drag hit strip (resize only).
 *
 * **Does not participate in Row layout** — the caller must place this in a parent [Box] above the
 * shell [androidx.compose.foundation.layout.Row], offset so its center sits on `sidebarWidth`
 * (subtract [SidebarDividerCenterOffset]).
 *
 * Drag reports a width delta in dp via [onDragDelta]. [onStartDrag]/[onEndDrag] bracket a drag
 * so the caller can suppress springy width animation while resizing.
 *
 * Hovering or dragging the strip highlights the hairline in `primary` so the seam reads as active.
 * Sidebar collapse/expand is not here — title-bar toggle + collapsed rail chevron. A layout that
 * needs an in-line collapse chip uses [CompactSidebarDivider] instead.
 */
@Composable
fun SidebarDivider(
    onDragDelta: (Dp) -> Unit,
    onStartDrag: () -> Unit = {},
    onEndDrag: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val active = hovered || dragging

    val hairlineColor by animateColorAsState(
        targetValue = if (active) cs.primary.copy(alpha = 0.90f) else cs.onSurface.copy(alpha = 0.18f),
        animationSpec = tween(120),
        label = "sidebar_hairline_color",
    )
    val hairlineWidth by animateDpAsState(
        targetValue = if (active) 2.dp else SplitSeamHairline,
        animationSpec = tween(120),
        label = "sidebar_hairline_width",
    )

    // Same overlay geometry as pane splitters ([SplitSeamOverlay] / [SplitSeamHitWidth]).
    Box(
        modifier
            .width(SplitSeamHitWidth)
            .fillMaxHeight()
            .zIndex(20f),
    ) {
        // Hairline — thicker + primary while the strip is hovered or dragged.
        Box(
            Modifier
                .align(Alignment.Center)
                .width(hairlineWidth)
                .fillMaxHeight()
                .background(hairlineColor),
        )

        // Full-height drag hit (transparent). col-resize cursor.
        Box(
            Modifier
                .matchParentSize()
                .hoverable(interaction)
                .pointerHoverIcon(ColResizeIcon)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            onStartDrag()
                        },
                        onDragEnd = {
                            dragging = false
                            onEndDrag()
                        },
                        onDragCancel = {
                            dragging = false
                            onEndDrag()
                        },
                    ) { _, drag -> onDragDelta(drag.x.toDp()) }
                }
                .testTag("sidebar_divider"),
        )
    }
}

/** Half of [SplitSeamHitWidth] — use when offsetting the [SidebarDivider] overlay onto the seam. */
val SidebarDividerCenterOffset: Dp = SplitSeamCenterOffset

/**
 * In-layout variant of [SidebarDivider] for the compact/touch shell: a real 14dp-wide gutter with a
 * hairline [androidx.compose.material3.ColorScheme.outlineVariant] rule down its center (testTag
 * `sidebar_divider` on the drag hit area, reporting a width delta in dp via [onDragDelta]), plus
 * the collapse chevron (testTag `sidebar_collapse`, [onCollapse]) tucked just below the sidebar's
 * app bar so it never collides with the list overflow.
 *
 * The 48dp touch target (a11y minimum) grows DOWNward from the same top offset so it never rises
 * into the app-bar overflow button; the visible chip stays ~24dp. `statusBarsPadding()` is applied
 * unconditionally — the inset is empty wherever there is no status bar.
 *
 * [onStartDrag]/[onEndDrag] bracket a drag so the caller can suppress the collapse/expand width
 * animation while the user is actively resizing (otherwise the spring lags behind the finger).
 */
@Composable
fun CompactSidebarDivider(
    onDragDelta: (Dp) -> Unit,
    onCollapse: () -> Unit,
    onStartDrag: () -> Unit = {},
    onEndDrag: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
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
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onStartDrag() },
                        onDragEnd = { onEndDrag() },
                        onDragCancel = { onEndDrag() },
                    ) { _, drag -> onDragDelta(drag.x.toDp()) }
                }
                .testTag("sidebar_divider"),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 58.dp)
                .size(48.dp)
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
                    Icons.Filled.ChevronRight,
                    contentDescription = "Collapse sidebar",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(180f),
                )
            }
        }
    }
}
