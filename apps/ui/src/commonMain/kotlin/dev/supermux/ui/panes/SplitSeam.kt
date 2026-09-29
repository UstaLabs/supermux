// Splitter seam chrome: the hairline + drag strip that sits on a split boundary.
//
// Shared by PaneSplit (this module), and by the desktop's ResizableSplit and SidebarDivider — all
// three must draw the same seam, so it lives in one place.
//
// NOTE: resize cursors are expect/actual — JVM uses AWT E/N_RESIZE; Android uses PointerIcon.Default
// (no pointer cursors).
package dev.supermux.ui.panes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Overlay seam identical to [SidebarDivider]: [SplitSeamHitWidth] strip, centered hairline,
 * primary highlight on hover/drag. Does **not** consume layout space in a Row/Column of panes.
 *
 * @param horizontal true when panes are side-by-side (vertical hairline, col-resize).
 * @param onDragStart a drag began; the caller records what [onDragPx] is relative to.
 * @param onDragPx how far the pointer is from where the drag STARTED, in pixels along the
 *   split axis — a total, not a per-event step (see [seamDrag]).
 */
@Composable
fun SplitSeamOverlay(
    horizontal: Boolean,
    onDragPx: (totalPx: Float) -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
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
                .seamDrag(
                    horizontal = horizontal,
                    onStart = { dragging = true; onDragStart() },
                    onMove = onDragPx,
                    onEnd = { dragging = false },
                )
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

/** CSS `col-resize` equivalent (Compose common API has no resize icons — JVM uses AWT). */
expect val ColResizeIcon: PointerIcon

/** CSS `row-resize` equivalent for horizontal (top/bottom) splits. */
expect val RowResizeIcon: PointerIcon

/**
 * Drag gesture for a resize seam that reports the pointer's TOTAL travel since the press,
 * measured in window space.
 *
 * Summing per-event deltas loses whatever the caller clamped away: drag past a minimum,
 * come back, and the seam starts moving again at once, a full overshoot away from the
 * cursor. With a total, the caller computes `start + total`, clamps it, and the seam
 * stays pinned until the pointer returns to it. Window space, because the seam itself
 * moves under the pointer during the drag; and from the PRESS, not from where the touch
 * slop was passed, so the grabbed point stays under the cursor.
 */
@Composable
fun Modifier.seamDrag(
    horizontal: Boolean,
    onStart: () -> Unit,
    onMove: (totalPx: Float) -> Unit,
    onEnd: () -> Unit,
): Modifier {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    return this
        .onGloballyPositioned { coords[0] = it }
        .pointerInput(horizontal) {
            fun along(local: Offset): Float? {
                val c = coords[0]?.takeIf { it.isAttached } ?: return null
                val w = c.localToWindow(local)
                return if (horizontal) w.x else w.y
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val origin = along(down.position) ?: return@awaitEachGesture
                val first = if (horizontal) {
                    awaitHorizontalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                } else {
                    awaitVerticalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                } ?: return@awaitEachGesture
                start()
                try {
                    along(first.position)?.let { move(it - origin) }
                    val onChange: (PointerInputChange) -> Unit = { c ->
                        along(c.position)?.let { move(it - origin) }
                        c.consume()
                    }
                    if (horizontal) horizontalDrag(first.id, onChange) else verticalDrag(first.id, onChange)
                } finally {
                    end()
                }
            }
        }
}
