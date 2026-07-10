package dev.supermux.android.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring

/**
 * Named motion tokens — the "budget motion by frequency" policy (design language §05).
 *
 * Base is *Standard*, not bouncy: a developer tool should not overshoot on every state change.
 * Overshoot is reserved for [press] tactility and [spatial] navigation. The busiest surfaces
 * (terminal, editor, composer typing) use [none] — animating an action done 100+×/day is a tax.
 *
 * Generic factories so one token serves `animateFloatAsState`, `animateDpAsState`,
 * `animateColorAsState`, `AnimatedVisibility`, etc. Consolidates the springs that were previously
 * re-specified inline at each call site.
 */
object Motion {
    /** State changes — send, tool-card expand, status pill. Snappy, no overshoot. */
    fun <T> stateChange(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Press feedback on chips / buttons — a little bounce for tactility. */
    fun <T> press(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)

    /** Navigation, pane resize, sheets — gentle overshoot is welcome. */
    fun <T> spatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    /** High-frequency surfaces (terminal / editor / composer) — no animation. */
    fun <T> none(): FiniteAnimationSpec<T> = snap()
}
