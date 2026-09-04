package dev.supermux.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * Material3 window width classes. The only layout breakpoint in the app: screens branch on this
 * (plus [LocalInputMode]) instead of measuring the platform themselves.
 *
 * - [Compact]  < 600dp — phone, folded cover screen. Single pane.
 * - [Medium]   600–839dp — unfolded foldable, small tablet, half-screen desktop. Multi-pane.
 * - [Expanded] ≥ 840dp — tablet landscape, desktop window.
 *
 * 600dp is where Android's old `isWorkspaceWidth` / `WORKSPACE_MIN_WIDTH_DP` sat, so
 * `!= Compact` reproduces the previous workspace predicate exactly.
 */
enum class WindowWidthClass { Compact, Medium, Expanded }

/** Classifies a window width in dp. See [WindowWidthClass] for the boundaries. */
fun widthClassFor(widthDp: Int): WindowWidthClass = when {
    widthDp < 600 -> WindowWidthClass.Compact
    widthDp < 840 -> WindowWidthClass.Medium
    else -> WindowWidthClass.Expanded
}

/**
 * Current window width class. Defaults to [WindowWidthClass.Expanded] — the desktop-safe value,
 * so a composable rendered outside an entry point (previews, tests, tooling) gets the roomy
 * layout rather than the phone one.
 *
 * Provided at the root of each entry point: Android from `LocalConfiguration.screenWidthDp`,
 * desktop from the window's container size in dp (recomputed on resize).
 */
val LocalWindowWidthClass = compositionLocalOf { WindowWidthClass.Expanded }

/** Provides [LocalWindowWidthClass] from a raw dp width; recomposes only when the class flips. */
@Composable
fun ProvideWindowWidthClass(widthDp: Int, content: @Composable () -> Unit) {
    val widthClass = remember(widthDp) { widthClassFor(widthDp) }
    CompositionLocalProvider(LocalWindowWidthClass provides widthClass, content = content)
}
