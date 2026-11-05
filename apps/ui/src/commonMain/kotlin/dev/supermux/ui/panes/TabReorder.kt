package dev.supermux.ui.panes

/**
 * Where a tab dragged along its own strip sits now, by its neighbours' real widths — the way a
 * browser reorders tabs. [current] is its index among the other tabs ([restWidths], in strip
 * order); it passes a neighbour once its centre ([centre], from where the strip's first tab
 * starts) crosses that neighbour's middle, as laid out with the dragged tab ([draggedWidth] wide)
 * at [current].
 *
 * Going back takes crossing the neighbour's middle where it sits now, a dragged tab's width
 * away, so a pointer resting near a boundary never flips the order back and forth.
 */
internal fun liveInsertIndex(restWidths: List<Float>, draggedWidth: Float, current: Int, centre: Float): Int {
    var at = current.coerceIn(0, restWidths.size)
    fun middleOf(k: Int): Float {
        var left = if (k >= at) draggedWidth else 0f
        for (i in 0 until k) left += restWidths[i]
        return left + restWidths[k] / 2f
    }
    while (at < restWidths.size && centre > middleOf(at)) at++
    while (at > 0 && centre < middleOf(at - 1)) at--
    return at
}
