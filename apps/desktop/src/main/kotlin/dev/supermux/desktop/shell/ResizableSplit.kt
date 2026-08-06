// Ported from apps/android/src/main/kotlin/dev/supermux/android/shell/ResizableSplit.kt —
// keep in sync until a shared UI module exists. Desktop additions: col-/row-resize hover cursor
// and primary hairline highlight on hover/drag (parity with SidebarDivider + web hover:bg-primary).
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

enum class SplitAxis { Horizontal, Vertical }

/**
 * Two slots separated by a drag-resizable divider. [fraction] is the share (0..1) given to the
 * FIRST slot; dragging the divider calls [onFractionChange] clamped to [range]. The divider is a
 * 1dp outlineVariant rule inside a 24dp hit target. Does NOT impose fillMaxSize/alpha on its slots.
 *
 * `Modifier.weight(...)` only resolves inside a `RowScope`/`ColumnScope` receiver (it's declared
 * as a member extension of those scopes, not a top-level function), so the Horizontal/Vertical
 * cases are inlined below rather than shared through a generic helper — the divider itself needs
 * no weight, so that part is still factored into one lambda.
 */
@Composable
fun ResizableSplit(
    axis: SplitAxis,
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    testTag: String,
    first: @Composable () -> Unit,
    // Nullable: when null, [first] takes the full space and no divider shows. This lets a caller
    // keep [first] at a STABLE call position whether or not the second pane is present — so [first]
    // isn't remounted (and doesn't flash) when the second pane toggles on/off.
    second: (@Composable () -> Unit)? = null,
) {
    var totalPx by remember { mutableStateOf(0) }
    val horizontal = axis == SplitAxis.Horizontal
    val handle = 24.dp
    // Read `fraction` fresh each frame: pointerInput isn't keyed on it, so the drag callback would
    // otherwise capture a stale value and the divider wouldn't accumulate/track the finger.
    val currentFraction by rememberUpdatedState(fraction)

    val divider: @Composable () -> Unit = {
        // Horizontal split = side-by-side panes → col-resize; vertical split → row-resize.
        val resizeIcon = if (horizontal) ColResizeIcon else RowResizeIcon
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        var dragging by remember { mutableStateOf(false) }
        val active = hovered || dragging
        val cs = MaterialTheme.colorScheme
        val lineColor by animateColorAsState(
            targetValue = if (active) cs.primary.copy(alpha = 0.90f) else cs.outlineVariant,
            animationSpec = tween(120),
            label = "split_hairline_color",
        )
        val lineThickness by animateDpAsState(
            targetValue = if (active) 2.dp else 1.dp,
            animationSpec = tween(120),
            label = "split_hairline_width",
        )
        Box(
            (if (horizontal) Modifier.fillMaxHeight().width(handle) else Modifier.fillMaxWidth().height(handle))
                .hoverable(interaction)
                .pointerHoverIcon(resizeIcon)
                .pointerInput(totalPx, range) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { _, drag ->
                        if (totalPx <= 0) return@detectDragGestures
                        val delta = (if (horizontal) drag.x else drag.y) / totalPx
                        onFractionChange((currentFraction + delta).coerceIn(range.start, range.endInclusive))
                    }
                }
                .testTag(testTag),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                (if (horizontal) {
                    Modifier.fillMaxHeight().width(lineThickness)
                } else {
                    Modifier.fillMaxWidth().height(lineThickness)
                }).background(lineColor),
            )
        }
    }

    if (horizontal) {
        Row(Modifier.fillMaxSize().onSizeChanged { totalPx = it.width }) {
            Box(Modifier.weight(if (second != null) fraction else 1f).fillMaxHeight()) { first() }
            if (second != null) {
                divider()
                Box(Modifier.weight(1f - fraction).fillMaxHeight()) { second() }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().onSizeChanged { totalPx = it.height }) {
            Box(Modifier.weight(if (second != null) fraction else 1f).fillMaxWidth()) { first() }
            if (second != null) {
                divider()
                Box(Modifier.weight(1f - fraction).fillMaxWidth()) { second() }
            }
        }
    }
}
