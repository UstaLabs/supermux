package dev.supermux.android.session

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val ActionWidth = 104.dp

@Composable
fun SwipeActionRow(
    rowId: String,
    openRowId: String?,
    onOpenRowChange: (String?) -> Unit,
    startLabel: String?,
    endLabel: String?,
    onStartAction: () -> Unit,
    onEndAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    endColor: Color = MaterialTheme.colorScheme.errorContainer,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val revealPx = with(LocalDensity.current) { ActionWidth.toPx() }
    var offsetPx by remember(rowId) { mutableFloatStateOf(0f) }
    var settleJob: Job? by remember(rowId) { androidx.compose.runtime.mutableStateOf(null) }

    fun settle(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(offsetPx, target) { value, _ -> offsetPx = value }
        }
    }

    fun invokeAction(action: () -> Unit) {
        onOpenRowChange(null)
        settle(0f)
        action()
    }

    LaunchedEffect(openRowId, enabled) {
        if (!enabled || openRowId != rowId) settle(0f)
    }

    val showActions = offsetPx != 0f || openRowId == rowId
    Box(modifier) {
        if (showActions) {
            Row(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (startLabel != null) {
                    TextButton(
                        onClick = { invokeAction(onStartAction) },
                        modifier = Modifier
                            .width(ActionWidth)
                            .fillMaxHeight()
                            .background(startColor),
                    ) {
                        Text(startLabel, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                } else {
                    Box(Modifier.width(ActionWidth))
                }
                if (endLabel != null) {
                    TextButton(
                        onClick = { invokeAction(onEndAction) },
                        modifier = Modifier
                            .width(ActionWidth)
                            .fillMaxHeight()
                            .background(endColor),
                    ) {
                        Text(endLabel, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        Box(
            Modifier
                .graphicsLayer { translationX = offsetPx }
                .pointerInput(rowId, enabled, startLabel, endLabel) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { settleJob?.cancel() },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            val minimum = if (endLabel == null) 0f else -revealPx
                            val maximum = if (startLabel == null) 0f else revealPx
                            offsetPx = (offsetPx + delta).coerceIn(minimum, maximum)
                            if (offsetPx != 0f) onOpenRowChange(rowId)
                        },
                        onDragEnd = {
                            val target = when {
                                offsetPx > revealPx * 0.35f && startLabel != null -> revealPx
                                offsetPx < -revealPx * 0.35f && endLabel != null -> -revealPx
                                else -> 0f
                            }
                            onOpenRowChange(if (target == 0f) null else rowId)
                            settle(target)
                        },
                        onDragCancel = {
                            onOpenRowChange(null)
                            settle(0f)
                        },
                    )
                },
        ) {
            content()
        }
    }
}
