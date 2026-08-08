// Ported from apps/android/src/main/kotlin/dev/supermux/android/shell/SidebarDivider.kt —
// keep in sync until a shared UI module exists.
//
// Desktop: this is an OVERLAY on the sidebar↔detail seam, not a Row child that steals width.
// The parent [Box] positions it with `offset(x = sidebarWidth - halfWidth)` + high zIndex so the
// hairline + drag strip paint ABOVE both panes. Resize-only (no collapse chip). Collapse/expand:
// title-bar toggle next to traffic lights; when collapsed, also the rail expand chevron.
// Hover/drag lights the hairline in primary (parity with the web app's hover:bg-primary/25).
package dev.supermux.desktop.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.awt.Cursor

/**
 * Overlay on the sidebar↔detail seam: 1dp hairline + drag hit strip (resize only).
 *
 * **Does not participate in Row layout** — the caller must place this in a parent [Box] above the
 * shell [androidx.compose.foundation.layout.Row], offset so its center sits on `sidebarWidth`.
 *
 * Drag reports a width delta in dp via [onDragDelta]. [onStartDrag]/[onEndDrag] bracket a drag
 * so the caller can suppress springy width animation while resizing.
 *
 * Hovering or dragging the strip highlights the hairline in [primary] so the seam reads as active.
 * Sidebar collapse/expand is not here — title-bar toggle + collapsed rail chevron.
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

/** Half of [SplitSeamHitWidth] — use when offsetting this overlay so its center sits on the seam. */
val SidebarDividerCenterOffset: Dp = SplitSeamCenterOffset

/** CSS `col-resize` equivalent (Compose common API has no resize icons — desktop uses AWT). */
internal val ColResizeIcon: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))

/** CSS `row-resize` equivalent for horizontal (top/bottom) splits. */
internal val RowResizeIcon: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR))
