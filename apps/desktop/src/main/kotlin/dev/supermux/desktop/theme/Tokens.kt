package dev.supermux.desktop.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing tokens — 4-point grid. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Corner-radius tokens. */
object Radii {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val pill = 999.dp
}

/**
 * Subtle shadow for "calm depth" surfaces (cards, sheets).
 * Elevation is intentionally low and both ambient/spot are dimmed so
 * the shadow is present but never heavy.
 */
fun Modifier.softElevation(radius: Dp = Radii.md): Modifier = this.shadow(
    elevation = 6.dp,
    shape = RoundedCornerShape(radius),
    ambientColor = Color.Black.copy(alpha = 0.4f),
    spotColor = Color.Black.copy(alpha = 0.4f),
    clip = false,
)
