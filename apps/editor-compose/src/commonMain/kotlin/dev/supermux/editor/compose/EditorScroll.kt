package dev.supermux.editor.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * The surface's scroll position in pixels: [y] from the document top, [x] from the text's left
 * edge (always 0 while wrapping). Snapshot state, read only by the draw pass, so scrolling
 * repaints without recomposing.
 *
 * [maxX] / [maxY] are asked of the surface on every move, so a document that grew or shrank
 * clamps the next scroll without any bookkeeping here.
 */
@Stable
internal class EditorScroll(
    private val maxX: () -> Float,
    private val maxY: () -> Float,
) {
    var x: Float by mutableFloatStateOf(0f)
        private set
    var y: Float by mutableFloatStateOf(0f)
        private set

    fun scrollTo(x: Float = this.x, y: Float = this.y) {
        val nx = x.coerceIn(0f, maxOf(0f, maxX()))
        val ny = y.coerceIn(0f, maxOf(0f, maxY()))
        if (nx != this.x) this.x = nx
        if (ny != this.y) this.y = ny
    }

    /** Scroll by ([dx], [dy]); returns what was actually consumed on each axis. */
    fun scrollBy(dx: Float, dy: Float): Pair<Float, Float> {
        val ox = x
        val oy = y
        scrollTo(x + dx, y + dy)
        return (x - ox) to (y - oy)
    }

    /** Back inside the bounds (the document shrank, the viewport grew). */
    fun clamp() = scrollTo(x, y)
}
