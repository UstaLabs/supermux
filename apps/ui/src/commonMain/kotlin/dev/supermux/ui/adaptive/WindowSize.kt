package dev.supermux.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
 * Classifies a window width measured in pixels. The first composed frame can report a container
 * size of 0 (before the window is laid out) — that is not "a tiny window", so it falls back to
 * [WindowWidthClass.Expanded], the same desktop-safe default [LocalWindowWidthClass] carries.
 * Both entry points measure the same way (window bounds ÷ density), so a given physical window
 * classifies identically on Android and desktop.
 */
fun widthClassForPx(widthPx: Int, density: Float): WindowWidthClass =
    if (widthPx <= 0 || density <= 0f) {
        WindowWidthClass.Expanded
    } else {
        widthClassFor((widthPx / density).toInt())
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

/**
 * The current window's width class, measured from the window itself.
 *
 * Every host that hosts the shared root needs exactly this measurement and used to derive it
 * separately: `containerSize` ÷ density, with a fallback for the frame before the window has been
 * laid out. [androidx.compose.ui.platform.LocalWindowInfo]'s `containerSize` is `0` during the
 * FIRST composition (composition precedes measure), and [widthClassForPx] maps 0 to
 * [WindowWidthClass.Expanded] — the desktop-safe answer, and the wrong one for a phone, which
 * would paint one frame at the desktop type scale before settling.
 *
 * @param fallbackDp a width in dp to classify while the window reports nothing yet. Android passes
 *   `LocalConfiguration.screenWidthDp`; a host with no such value (iOS, where the view controller's
 *   bounds are known by the time Compose measures) passes null and takes the Expanded default for
 *   that one frame.
 */
@Composable
fun rememberWindowWidthClass(fallbackDp: Int? = null): WindowWidthClass {
    val widthPx = LocalWindowInfo.current.containerSize.width
    val density = LocalDensity.current.density
    return if (widthPx > 0 || fallbackDp == null) widthClassForPx(widthPx, density)
    else widthClassFor(fallbackDp)
}
