// Ported from apps/android/src/main/kotlin/dev/supermux/android/theme/Tokens.kt — keep in sync until a shared UI module exists (spec 2026-07-09, Decision 1).
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
    /** Pairing / QR display size (scannable at arm's length on a desktop monitor). */
    val qr = 200.dp
}

/** Icon and inline-control sizes. */
object IconSize {
    val sm = 14.dp
    val md = 18.dp
    val lg = 24.dp
}

/**
 * Stroke widths — borders and progress indicators (not layout spacing).
 * Prefer these over raw `1.dp` / `2.dp` so surfaces stay consistent.
 *
 * Merge note: two parity branches each grew this object. Unified here so every existing call site
 * keeps its intended width — borders use [hairline] (1dp), progress indicators use [thin]/[md]
 * (both 2dp; [md] is retained because the Devices spinner refers to it).
 */
object Stroke {
    val hairline = 1.dp
    val thin = 2.dp
    val md = 2.dp
}

/** Corner-radius tokens. */
object Radii {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val pill = 999.dp
}

/**
 * Component-size tokens that sit outside the spacing grid (status dots, menu widths).
 * Named so call sites never hardcode magic dimensions.
 */
object Size {
    /** Online-status badge on forge connection rows. */
    val statusDot = 8.dp
    /** Project-picker / forge omnibox dropdown width. */
    val omniboxWidth = 384.dp
    /** Max height of the scrollable omnibox option list. */
    val omniboxListMax = 360.dp
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
