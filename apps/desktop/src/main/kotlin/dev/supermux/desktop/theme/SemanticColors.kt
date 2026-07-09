package dev.supermux.desktop.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.supermux.ui.oklchToArgb

/**
 * Semantic status colors — fixed, human meaning (success / warning / danger / info), theme-aware,
 * and deliberately **independent of Material You** so status never gets repainted by a wallpaper.
 *
 * These replace scattered hardcoded status literals across the app (e.g. `DoneGreen 0xFF16A34A`,
 * `NotDoneAmber 0xFFF59E0B`, the GitLab orange `0xFFFC6D26`, the branch green `0xFF3FB950`) with
 * named roles. Authored in OKLCH — the same source of truth as the brand palette (Theme.kt).
 *
 * [brand] is the supermux teal held fixed even when dynamic color is on, for the agent "working"
 * state and status rails that must stay on-brand.
 */
data class SupermuxSemantics(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val brand: Color,
    /** Ink for text/icons sitting on top of a filled status color. */
    val onStatus: Color,
)

private fun oklch(l: Double, c: Double, h: Double) = Color(oklchToArgb(l, c, h))

fun supermuxSemanticsDark() = SupermuxSemantics(
    success = oklch(0.72, 0.15, 150.0),
    warning = oklch(0.80, 0.12, 75.0),   // = brand warning (Theme.kt dark)
    danger = oklch(0.72, 0.18, 24.0),    // = brand destructive (Theme.kt dark)
    info = oklch(0.70, 0.10, 235.0),
    brand = oklch(0.72, 0.105, 180.0),   // = brand primary (Theme.kt dark)
    onStatus = oklch(0.16, 0.02, 150.0),
)

fun supermuxSemanticsLight() = SupermuxSemantics(
    success = oklch(0.52, 0.15, 150.0),
    warning = oklch(0.55, 0.13, 70.0),
    danger = oklch(0.54, 0.19, 27.0),
    info = oklch(0.50, 0.12, 240.0),
    brand = oklch(0.49, 0.105, 185.0),
    onStatus = oklch(0.98, 0.01, 150.0),
)

/** Read via `LocalSemantics.current`. Provided by [SupermuxTheme]; defaults to the dark set. */
val LocalSemantics = staticCompositionLocalOf { supermuxSemanticsDark() }
