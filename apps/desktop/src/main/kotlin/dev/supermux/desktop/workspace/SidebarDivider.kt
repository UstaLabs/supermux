// Ported from apps/android/src/main/kotlin/dev/supermux/android/workspace/SidebarDivider.kt —
// keep in sync until a shared UI module exists.
//
// Desktop: this is an OVERLAY on the sidebar↔detail seam, not a Row child that steals width.
// The parent [Box] positions it with `offset(x = sidebarWidth - halfWidth)` + high zIndex so the
// hairline, drag strip, and collapse chip paint ABOVE both panes (overflow inside a 1dp Row
// sibling was clipped/covered and the chip vanished). Drag strip uses AWT col-resize cursor
// (PointerIcon.Hand is only for the collapse chip). Hover/drag lights the hairline in primary
// (parity with the web app's hover:bg-primary/25 on resize handles).
package dev.supermux.desktop.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.awt.Cursor

/**
 * Overlay on the sidebar↔detail seam: 1dp hairline + drag hit strip + floating collapse chip.
 *
 * **Does not participate in Row layout** — the caller must place this in a parent [Box] above the
 * workspace [androidx.compose.foundation.layout.Row], offset so its center sits on
 * `sidebarWidth`. That way the chip is visible (drawn last / high z-index) without fattening the gap.
 *
 * Drag reports a width delta in dp via [onDragDelta]. [onStartDrag]/[onEndDrag] bracket a drag
 * so the caller can suppress springy width animation while resizing.
 *
 * Hovering or dragging the strip highlights the hairline in [primary] so the seam reads as active.
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
        targetValue = if (active) 2.dp else HAIRLINE,
        animationSpec = tween(120),
        label = "sidebar_hairline_width",
    )

    // Overlay strip: only as wide as the drag hit area; hairline centered; chip at top.
    Box(
        modifier
            .width(DRAG_HIT_WIDTH)
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

        // Full-height drag hit (transparent). col-resize cursor — not Hand (that's for the chip).
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

        // Floating collapse chip — sits on the seam, slightly elevated so it reads on both themes.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = 10.dp)
                .size(CHIP_HIT)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onCollapse)
                .testTag("sidebar_collapse"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(CHIP_VISUAL)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(cs.surfaceContainerHighest)
                    .border(1.dp, cs.onSurface.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronLeft,
                    contentDescription = "Collapse sidebar",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** Visible rule thickness (idle). */
private val HAIRLINE = 1.dp
/** Overlay strip width (drag + centers the hairline/chip). */
private val DRAG_HIT_WIDTH = 12.dp
/** Clickable hit for the collapse control. */
private val CHIP_HIT = 28.dp
/** Painted circle of the collapse control. */
private val CHIP_VISUAL = 22.dp

/** Half of [DRAG_HIT_WIDTH] — use when offsetting this overlay so its center sits on the seam. */
val SidebarDividerCenterOffset: Dp = 6.dp

/** CSS `col-resize` equivalent (Compose common API has no resize icons — desktop uses AWT). */
internal val ColResizeIcon: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))

/** CSS `row-resize` equivalent for horizontal (top/bottom) splits. */
internal val RowResizeIcon: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR))
