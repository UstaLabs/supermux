package dev.supermux.editor.compose

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * A scroll position in pixels: [y] from the document top, [x] from the text's left edge (0 while
 * wrapping). Snapshot state, read only by the draw pass, so scrolling repaints without recomposing.
 *
 * Every editor has its own ([EditorView.scrollState]); pass one to several `Editor`s to scroll them
 * together (M3c's linked views build their line alignment on this). It is clamped to the largest
 * of the surfaces showing it, and while it is shared the surfaces' scroll anchoring is off (each
 * would pull it toward its own anchor).
 *
 * Gestures arrive through `Modifier.scrollable`, which normalizes wheel notches and trackpad deltas
 * into pixels, tracks a finger's release velocity and runs the fling's decay (terminal-compose's
 * ScrollController lesson). A boundary consumes less than it was given, which stops a fling there.
 */
@Stable
class EditorScrollState {
    var x: Float by mutableFloatStateOf(0f)
        private set
    var y: Float by mutableFloatStateOf(0f)
        private set

    /** The composed surfaces showing this state; their extents clamp it. */
    internal val surfaces = ArrayList<EditorController>()

    internal val shared: Boolean get() = surfaces.size > 1

    fun scrollTo(x: Float = this.x, y: Float = this.y) {
        val nx = if (surfaces.isEmpty()) maxOf(0f, x) else x.coerceIn(0f, maxOf(0f, surfaces.maxOf { it.maxScrollX() }))
        val ny = if (surfaces.isEmpty()) maxOf(0f, y) else y.coerceIn(0f, maxOf(0f, surfaces.maxOf { it.maxScrollY() }))
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
    internal fun clamp() = scrollTo(x, y)

    /** Positive deltas scroll toward the end of the document. */
    internal val vertical: ScrollableState = ScrollableState { d -> scrollBy(0f, d).second }

    /** Positive deltas scroll toward the end of the lines. */
    internal val horizontal: ScrollableState = ScrollableState { d -> scrollBy(d, 0f).first }
}

/** A scroll state that lives as long as the composition (to share between editors). */
@Composable
fun rememberEditorScrollState(): EditorScrollState = remember { EditorScrollState() }

/**
 * A scroll position that survives edits and relayout: the document offset of the first visible
 * line's start, how far below that line's top the viewport starts, and the horizontal scroll.
 * Save it with [EditorView.scrollPosition], give it back with [EditorView.restoreScroll].
 */
@Immutable
data class EditorScrollPosition(val anchor: Int, val offsetPx: Float = 0f, val x: Float = 0f)
