package dev.supermux.ui.session

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val ActionWidth = 104.dp

/**
 * Horizontal swipe-to-reveal row actions (Android's list rows, moved verbatim).
 *
 * Touch only: under [InputMode.Pointer] the row renders just its [content] — a mouse
 * reaches the same actions through the row's context menu, and a trackpad fling would
 * otherwise open a drawer nobody asked for. Only one row is open at a time; the caller
 * owns that id ([openRowId] / [onOpenRowChange]).
 */

@Composable
fun SwipeActionRow(
    rowId: String,
    openRowId: String?,
    onOpenRowChange: (String?) -> Unit,
    startLabel: String?,
    endLabel: String?,
    startIcon: ImageVector? = null,
    endIcon: ImageVector? = null,
    onStartAction: () -> Unit,
    onEndAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    endColor: Color = MaterialTheme.colorScheme.errorContainer,
    content: @Composable () -> Unit,
) {
    if (LocalInputMode.current != InputMode.Touch) {
        // Pointer hosts have no swipe: the same affordances live in the row's
        // right-click menu, so the row renders its content and nothing else.
        Box(modifier) { content() }
        return
    }
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
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (startIcon != null) {
                                Icon(
                                    imageVector = startIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(startLabel, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
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
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (endIcon != null) {
                                Icon(
                                    imageVector = endIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(endLabel, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
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

/**
 * The Material glyph for a swipe action — Android's `R.drawable.ic_*` set, one mapping
 * for every list that renders these rows. [SessionSwipeAction.Settle] is `Check` in the
 * session list and `Archive` on a workspace row, so that one is the caller's to override.
 */
fun sessionSwipeActionIcon(action: SessionSwipeAction?): ImageVector? = when (action) {
    SessionSwipeAction.Mute -> Icons.AutoMirrored.Filled.VolumeOff
    SessionSwipeAction.Unmute -> Icons.AutoMirrored.Filled.VolumeUp
    SessionSwipeAction.Settle -> Icons.Filled.Check
    SessionSwipeAction.Edit -> Icons.Filled.Edit
    SessionSwipeAction.Discard -> Icons.Filled.Delete
    SessionSwipeAction.Activate -> Icons.Filled.PlayArrow
    null -> null
}

/** Workspace rows call the archive box a workspace "Archive", not a session "Settle". */
val WorkspaceArchiveSwipeIcon: ImageVector get() = Icons.Filled.Archive
