package dev.supermux.android.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
    second: @Composable () -> Unit,
) {
    var totalPx by remember { mutableStateOf(0) }
    val horizontal = axis == SplitAxis.Horizontal
    val handle = 24.dp
    // Read `fraction` fresh each frame: pointerInput isn't keyed on it, so the drag callback would
    // otherwise capture a stale value and the divider wouldn't accumulate/track the finger.
    val currentFraction by rememberUpdatedState(fraction)

    val divider: @Composable () -> Unit = {
        Box(
            (if (horizontal) Modifier.fillMaxHeight().width(handle) else Modifier.fillMaxWidth().height(handle))
                .pointerInput(totalPx, range) {
                    detectDragGestures { _, drag ->
                        if (totalPx <= 0) return@detectDragGestures
                        val delta = (if (horizontal) drag.x else drag.y) / totalPx
                        onFractionChange((currentFraction + delta).coerceIn(range.start, range.endInclusive))
                    }
                }
                .testTag(testTag),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                (if (horizontal) Modifier.fillMaxHeight().width(1.dp) else Modifier.fillMaxWidth().height(1.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }

    if (horizontal) {
        Row(Modifier.fillMaxSize().onSizeChanged { totalPx = it.width }) {
            Box(Modifier.weight(fraction).fillMaxHeight()) { first() }
            divider()
            Box(Modifier.weight(1f - fraction).fillMaxHeight()) { second() }
        }
    } else {
        Column(Modifier.fillMaxSize().onSizeChanged { totalPx = it.height }) {
            Box(Modifier.weight(fraction).fillMaxWidth()) { first() }
            divider()
            Box(Modifier.weight(1f - fraction).fillMaxWidth()) { second() }
        }
    }
}
