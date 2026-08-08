// Generic multi-step speedometer (desktop chrome). Effort/reasoning maps into
// [levels] + [value] via [dev.supermux.net.effortSpeedometerParams].
package dev.supermux.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Map discrete steps onto a 0f..1f needle position.
 *
 * @param levels total steps on the gauge (clamped to ≥ 1)
 * @param value current step, **1-based** (1 = leftmost / lowest, [levels] = rightmost / highest).
 *   Values outside 1..levels are clamped.
 */
fun speedometerProgress(levels: Int, value: Int): Float {
    val n = levels.coerceAtLeast(1)
    if (n == 1) return 1f
    val v = value.coerceIn(1, n)
    return ((v - 1).toFloat() / (n - 1).toFloat()).coerceIn(0f, 1f)
}

/**
 * Tiny semicircle speedometer.
 *
 * @param levels total number of steps (e.g. 5 for low…max)
 * @param value current step, **1-based** — 1 is lowest (needle left), [levels] is highest (needle right)
 */
@Composable
fun Speedometer(
    levels: Int,
    value: Int,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    activeTint: Color = MaterialTheme.colorScheme.primary,
    iconSize: Dp = 14.dp,
    testTag: String = "speedometer",
) {
    val progress = speedometerProgress(levels, value)
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 220),
        label = "speedometer",
    )
    Canvas(
        modifier
            .size(iconSize)
            .testTag(testTag),
    ) {
        val box = this.size
        val stroke = Stroke(width = box.minDimension * 0.14f, cap = StrokeCap.Round)
        // Upper semicircle: 180° (9 o'clock / low) → +180° sweep to 0° (3 o'clock / high).
        // Compose angles: 0° = 3 o'clock, positive = clockwise.
        val startAngle = 180f
        val fullSweep = 180f
        val diameter = box.minDimension * 0.92f
        val topLeft = Offset(
            (box.width - diameter) / 2f,
            (box.height - diameter) * 0.55f,
        )
        val arcSize = Size(diameter, diameter)
        val center = Offset(topLeft.x + diameter / 2f, topLeft.y + diameter / 2f)
        val radius = diameter / 2f

        drawArc(
            color = tint.copy(alpha = 0.35f),
            startAngle = startAngle,
            sweepAngle = fullSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        if (animated > 0.001f) {
            drawArc(
                color = activeTint,
                startAngle = startAngle,
                sweepAngle = fullSweep * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
        val needleAngleDeg = startAngle + fullSweep * animated
        val needleAngleRad = Math.toRadians(needleAngleDeg.toDouble())
        val needleLen = radius * 0.78f
        val tip = Offset(
            center.x + (cos(needleAngleRad) * needleLen).toFloat(),
            center.y + (sin(needleAngleRad) * needleLen).toFloat(),
        )
        drawLine(
            color = activeTint,
            start = center,
            end = tip,
            strokeWidth = box.minDimension * 0.12f,
            cap = StrokeCap.Round,
        )
        drawCircle(color = activeTint, radius = box.minDimension * 0.09f, center = center)
    }
}
