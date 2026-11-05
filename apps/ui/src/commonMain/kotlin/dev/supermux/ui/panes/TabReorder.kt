package dev.supermux.ui.panes

/**
 * Where a tab dragged along its own strip sits now, by its neighbours' real widths — the way a
 * browser reorders tabs. [current] is its index among the other tabs ([restWidths], in strip
 * order) and [left] where its left edge is drawn, from where the strip's first tab starts.
 *
 * A neighbour gives way as soon as the held tab's LEADING edge crosses its middle — the held tab
 * covers half of it: moving right, its right edge past the next tab's middle; moving left, its left
 * edge past the previous tab's. Each line has [hysteresis] px of dead band either side, so a
 * pointer resting on it never flips the order back and forth.
 */
internal fun liveInsertIndex(
    restWidths: List<Float>,
    draggedWidth: Float,
    current: Int,
    left: Float,
    hysteresis: Float = ReorderHysteresisPx,
): Int {
    var at = current.coerceIn(0, restWidths.size)
    // Where the held tab's slot starts when it sits at index i: after the i tabs before it.
    fun slotLeft(i: Int): Float {
        var x = 0f
        for (k in 0 until i) x += restWidths[k]
        return x
    }
    // Right edge (left + draggedWidth) past the middle of the next tab, which starts at
    // slotLeft(at) + draggedWidth: the held tab has moved half that tab's width off its slot.
    while (at < restWidths.size && left > slotLeft(at) + restWidths[at] / 2f + hysteresis) at++
    // Left edge past the middle of the previous tab.
    while (at > 0 && left < slotLeft(at - 1) + restWidths[at - 1] / 2f - hysteresis) at--
    return at
}

/** The dead band either side of a reorder line, in px: wider than a hand's tremor, too narrow to feel. */
internal const val ReorderHysteresisPx = 4f
