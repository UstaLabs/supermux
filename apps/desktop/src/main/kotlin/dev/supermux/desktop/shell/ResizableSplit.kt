// Ported from apps/android/src/main/kotlin/dev/supermux/android/shell/ResizableSplit.kt.
// The seam chrome it used to own now lives in :ui (dev.supermux.ui.panes.SplitSeam), shared with
// PaneSplit; only the two-slot wrapper is left here. Desktop: splitters match [SidebarDivider] —
// panes abut with no layout gap; a 12dp overlay strip (1dp hairline + transparent drag hit)
// sits on the seam and lights primary on hover/drag.
package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.ui.panes.SplitSeamCenterOffset
import dev.supermux.ui.panes.SplitSeamOverlay

enum class SplitAxis { Horizontal, Vertical }

/**
 * Two slots separated by a drag-resizable divider. [fraction] is the share (0..1) given to the
 * FIRST slot; dragging the divider calls [onFractionChange] clamped to [range].
 *
 * Same seam model as [SidebarDivider]: panes share the full area with **no layout gap**; the
 * hairline + drag hit are an overlay centered on the fraction boundary (12dp hit, 1dp rule).
 *
 * `Modifier.weight(...)` only resolves inside a `RowScope`/`ColumnScope` receiver, so the
 * Horizontal/Vertical cases are inlined rather than shared through a generic helper.
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
    val density = LocalDensity.current
    // Read `fraction` fresh each frame: pointerInput isn't keyed on it, so the drag callback would
    // otherwise capture a stale value and the divider wouldn't accumulate/track the finger.
    val currentFraction by rememberUpdatedState(fraction)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { totalPx = if (horizontal) it.width else it.height },
    ) {
        if (horizontal) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(if (second != null) fraction else 1f).fillMaxHeight()) { first() }
                if (second != null) {
                    Box(Modifier.weight(1f - fraction).fillMaxHeight()) { second() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(if (second != null) fraction else 1f).fillMaxWidth()) { first() }
                if (second != null) {
                    Box(Modifier.weight(1f - fraction).fillMaxWidth()) { second() }
                }
            }
        }

        if (second != null && totalPx > 0) {
            val seamPx = totalPx * currentFraction
            val seamDp = with(density) { seamPx.toDp() }
            SplitSeamOverlay(
                horizontal = horizontal,
                onDragDeltaPx = { deltaPx ->
                    if (totalPx <= 0) return@SplitSeamOverlay
                    val delta = deltaPx / totalPx
                    onFractionChange((currentFraction + delta).coerceIn(range.start, range.endInclusive))
                },
                modifier = if (horizontal) {
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = seamDp - SplitSeamCenterOffset)
                        .fillMaxHeight()
                } else {
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(y = seamDp - SplitSeamCenterOffset)
                        .fillMaxWidth()
                },
                testTag = testTag,
            )
        }
    }
}
