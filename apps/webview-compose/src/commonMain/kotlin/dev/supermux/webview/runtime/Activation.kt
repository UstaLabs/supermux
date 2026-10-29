package dev.supermux.webview.runtime

import dev.supermux.webview.Activation

/** Everything the activation decision depends on, at one instant. Times are milliseconds on the runtime clock. */
internal data class ActivationInputs(
    val activation: Activation,
    val visible: Boolean,
    val focused: Boolean,
    val interacted: Boolean,
    val live: Boolean,
    val now: Long,
    /** When the view's window position or size last changed (scroll, animation, resize). */
    val lastMovedAt: Long,
    /** When it stopped being visible; null while visible. A hidden view with none counts as hidden since [now]. */
    val hiddenSince: Long?,
    val graceMillis: Long,
    val hiddenReleaseMillis: Long,
)

/** Whether the view wants a live engine, its pool priority, and when to decide again (null: on the next change). */
internal data class ActivationDecision(val wantLive: Boolean, val priority: Int, val reevaluateAt: Long?)

/**
 * The activation rules (spec §4).
 * - A visible live view stays live, even while it moves.
 * - A cold view activates per its [Activation], and never mid-movement unless Eager. Eager activates while the view
 *   moves, so it is for panes only: in a scrolling list it would activate mid-fling.
 * - A hidden live view keeps its engine for the grace period: long for Eager panes, short for embeds.
 * - Hidden, a view ranks lowest whatever its activation, even when focused.
 */
internal fun decideActivation(i: ActivationInputs): ActivationDecision {
    val priority = when {
        i.focused && i.visible -> PRIORITY_FOCUSED
        i.visible && i.activation is Activation.Eager -> PRIORITY_VISIBLE_EAGER
        i.visible -> PRIORITY_VISIBLE
        else -> PRIORITY_HIDDEN
    }
    if (!i.visible) {
        if (!i.live) return ActivationDecision(false, priority, null)
        val keepFor = if (i.activation is Activation.Eager) i.hiddenReleaseMillis else i.graceMillis
        val until = (i.hiddenSince ?: i.now) plusSaturated keepFor
        return if (i.now < until) ActivationDecision(true, priority, until) else ActivationDecision(false, priority, null)
    }
    if (i.live) return ActivationDecision(true, priority, null)
    return when (val a = i.activation) {
        Activation.Eager -> ActivationDecision(true, priority, null)
        Activation.OnInteraction -> ActivationDecision(i.interacted, priority, null)
        is Activation.WhenVisible -> {
            val settledAt = i.lastMovedAt plusSaturated a.settleMillis
            if (i.now >= settledAt) ActivationDecision(true, priority, null) else ActivationDecision(false, priority, settledAt)
        }
    }
}

/** `this + other`, clamped to the Long range, so a wait of [Long.MAX_VALUE] ends never rather than in the past. */
private infix fun Long.plusSaturated(other: Long): Long {
    val sum = this + other
    // It overflowed if both operands have the same sign and the sum's sign differs.
    return if (((this xor sum) and (other xor sum)) < 0) (if (other > 0) Long.MAX_VALUE else Long.MIN_VALUE) else sum
}
