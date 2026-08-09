package dev.supermux.desktop.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.theme.Motion
import kotlin.math.roundToInt

/**
 * Translucent drop **preview** over still-live pane content.
 *
 * Shown from a Popup so it paints above SwingPanel. The highlight is **one**
 * rectangle that morphs (position + size spring) between left/right/top/bottom/
 * centre — transforming into the next target rather than cross-fading widgets.
 */
@Composable
fun DropZoneOverlay(
    activeZone: DropZone?,
    modifier: Modifier = Modifier,
    edgesEnabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val hot = cs.primary.copy(alpha = 0.30f)
    val borderCol = cs.primary.copy(alpha = 0.70f)
    val veil = cs.scrim.copy(alpha = 0.16f)

    val zone = when {
        activeZone == null -> null
        !edgesEnabled && activeZone != DropZone.Centre -> null
        else -> activeZone
    }

    // Normalized rect in 0..1: left, top, right, bottom — springs between targets.
    val (tL, tT, tR, tB) = when (zone) {
        DropZone.Left -> listOf(0f, 0f, 0.5f, 1f)
        DropZone.Right -> listOf(0.5f, 0f, 1f, 1f)
        DropZone.Top -> listOf(0f, 0f, 1f, 0.5f)
        DropZone.Bottom -> listOf(0f, 0.5f, 1f, 1f)
        DropZone.Centre -> listOf(0.06f, 0.06f, 0.94f, 0.94f)
        null -> listOf(0.46f, 0.46f, 0.54f, 0.54f)
    }
    val left by animateFloatAsState(tL, Motion.spatial(), label = "drop-l")
    val top by animateFloatAsState(tT, Motion.spatial(), label = "drop-t")
    val right by animateFloatAsState(tR, Motion.spatial(), label = "drop-r")
    val bottom by animateFloatAsState(tB, Motion.spatial(), label = "drop-b")
    val highlightAlpha by animateFloatAsState(
        targetValue = if (zone != null) 1f else 0f,
        animationSpec = Motion.stateChange(),
        label = "drop-a",
    )
    val label = when (zone) {
        DropZone.Left -> "Split left"
        DropZone.Right -> "Split right"
        DropZone.Top -> "Split above"
        DropZone.Bottom -> "Split below"
        DropZone.Centre -> "Move here"
        null -> ""
    }
    val tag = when (zone) {
        DropZone.Left -> "drop-zone-left"
        DropZone.Right -> "drop-zone-right"
        DropZone.Top -> "drop-zone-top"
        DropZone.Bottom -> "drop-zone-bottom"
        DropZone.Centre -> "drop-zone-centre"
        null -> "drop-zone-surface"
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(veil)
            .testTag("drop-zone-surface"),
    ) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val x = (left * w).roundToInt()
        val y = (top * h).roundToInt()
        val bw = ((right - left) * w).roundToInt().coerceAtLeast(1)
        val bh = ((bottom - top) * h).roundToInt().coerceAtLeast(1)
        val shape = if (zone == DropZone.Centre) RoundedCornerShape(10.dp) else RoundedCornerShape(0.dp)

        Box(
            Modifier
                .offset { IntOffset(x, y) }
                .width(with(density) { bw.toDp() })
                .height(with(density) { bh.toDp() })
                .clip(shape)
                .background(hot.copy(alpha = hot.alpha * highlightAlpha))
                .border(2.dp, borderCol.copy(alpha = borderCol.alpha * highlightAlpha), shape)
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            if (label.isNotEmpty() && highlightAlpha > 0.4f) {
                Text(
                    text = label,
                    color = cs.primary.copy(alpha = highlightAlpha),
                    fontSize = 12.sp,
                )
            }
        }

        if (edgesEnabled && zone == null) {
            Box(Modifier.fillMaxSize().testTag("drop-zone-top"))
            Box(Modifier.fillMaxSize().testTag("drop-zone-bottom"))
            Box(Modifier.fillMaxSize().testTag("drop-zone-left"))
            Box(Modifier.fillMaxSize().testTag("drop-zone-right"))
        }
        if (!edgesEnabled && zone == null) {
            Box(Modifier.fillMaxSize().testTag("drop-zone-centre"))
        }
    }
}

/** Back-compat alias — old call sites / tests name. */
@Composable
fun DropZoneSurface(
    activeZone: DropZone?,
    modifier: Modifier = Modifier,
    edgesEnabled: Boolean = true,
) = DropZoneOverlay(activeZone, modifier, edgesEnabled)
