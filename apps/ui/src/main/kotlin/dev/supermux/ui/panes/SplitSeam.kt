// Splitter seam chrome: the hairline + drag strip that sits on a split boundary.
//
// Shared by PaneSplit (this module), and by the desktop's ResizableSplit and SidebarDivider — all
// three must draw the same seam, so it lives in one place.
//
// NOTE: the resize cursors use java.awt.Cursor. That is fine while :ui is jvm-only; when an
// Android target is added they need an expect/actual (Android has no pointer cursors at all).
package dev.supermux.ui.panes

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * Overlay seam identical to [SidebarDivider]: [SplitSeamHitWidth] strip, centered hairline,
 * primary highlight on hover/drag. Does **not** consume layout space in a Row/Column of panes.
 *
 * @param horizontal true when panes are side-by-side (vertical hairline, col-resize).
 * @param onDragDeltaPx drag delta in **pixels** along the split axis (x for horizontal, y for vertical).
 */
@Composable
fun SplitSeamOverlay(
    horizontal: Boolean,
    onDragDeltaPx: (Float) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "split_seam",
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val active = hovered || dragging
    val resizeIcon = if (horizontal) ColResizeIcon else RowResizeIcon

    val hairlineColor by animateColorAsState(
        targetValue = if (active) cs.primary.copy(alpha = 0.90f) else cs.onSurface.copy(alpha = 0.18f),
        animationSpec = tween(120),
        label = "split_hairline_color",
    )
    val hairlineWidth by animateDpAsState(
        targetValue = if (active) 2.dp else SplitSeamHairline,
        animationSpec = tween(120),
        label = "split_hairline_width",
    )

    Box(
        modifier
            .then(
                if (horizontal) Modifier.width(SplitSeamHitWidth).fillMaxHeight()
                else Modifier.height(SplitSeamHitWidth).fillMaxWidth(),
            )
            .zIndex(20f),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .then(
                    if (horizontal) Modifier.width(hairlineWidth).fillMaxHeight()
                    else Modifier.height(hairlineWidth).fillMaxWidth(),
                )
                .background(hairlineColor),
        )
        Box(
            Modifier
                .matchParentSize()
                .hoverable(interaction)
                .pointerHoverIcon(resizeIcon)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { _, drag ->
                        onDragDeltaPx(if (horizontal) drag.x else drag.y)
                    }
                }
                .testTag(testTag),
        )
    }
}

/** Idle hairline thickness — same as [SidebarDivider]. */
val SplitSeamHairline: Dp = 1.dp
/** Overlay strip width (drag + centers the hairline) — same as sidebar [DRAG_HIT_WIDTH]. */
val SplitSeamHitWidth: Dp = 12.dp
/** Half of [SplitSeamHitWidth] — offset so the strip center sits on the seam. */
val SplitSeamCenterOffset: Dp = 6.dp

/** CSS `col-resize` equivalent (Compose common API has no resize icons — desktop uses AWT). */
val ColResizeIcon: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))

/** CSS `row-resize` equivalent for horizontal (top/bottom) splits. */
val RowResizeIcon: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR))
