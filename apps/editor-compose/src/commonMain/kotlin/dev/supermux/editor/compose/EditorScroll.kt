package dev.supermux.editor.compose

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * A scroll position in pixels: [y] from the document top, [x] from the text's left edge (0 while
 * wrapping). Snapshot state, read by the surface's layout pass (through [version]), so scrolling relayouts
 * and repaints without recomposing.
 *
 * Every editor has its own ([EditorView.scrollState]); pass one to several `Editor`s to scroll them
 * together. It is clamped to the largest of the surfaces showing it, and while it is shared the
 * surfaces' scroll anchoring is off (each would pull it toward its own anchor): side-by-side views
 * that keep corresponding LINES aligned are M3c's linked views.
 *
 * Gestures arrive through `Modifier.scrollable`, which normalizes wheel notches and trackpad deltas
 * into pixels, tracks a finger's release velocity and runs the fling's decay (terminal-compose's
 * ScrollController lesson). A boundary consumes less than it was given, which stops a fling there.
 */
@Stable
class EditorScrollState {
    private val xState = mutableFloatStateOf(0f)
    private val yState = mutableFloatStateOf(0f)

    // While a surface lays out, reading the position is NOT observed: the pass observes [version]
    // instead, so its own anchoring and clamping never schedule another pass.
    var x: Float
        get() = if (layoutDepth > 0) Snapshot.withoutReadObservation { xState.floatValue } else xState.floatValue
        private set(v) { xState.floatValue = v }
    var y: Float
        get() = if (layoutDepth > 0) Snapshot.withoutReadObservation { yState.floatValue } else yState.floatValue
        private set(v) { yState.floatValue = v }

    /**
     * Bumped by every change of the position that a surface's own layout pass did not make (a
     * gesture, scroll-into-view, a host). The layout pass observes THIS and reads [x] / [y]
     * unobserved: its own writes (anchoring, clamping) are no news to it, so they never cost a
     * second layout or a second frame.
     */
    internal var version: Int by mutableIntStateOf(0)
        private set

    /** > 0 while a surface showing this state lays out: its writes are the layout's own. */
    internal var layoutDepth = 0

    /** The composed surfaces showing this state; their extents clamp it. */
    internal val surfaces = ArrayList<EditorController>()

    internal val shared: Boolean get() = surfaces.size > 1

    fun scrollTo(x: Float = this.x, y: Float = this.y) {
        DrawGuard.check("the scroll position")
        val nx = if (surfaces.isEmpty()) maxOf(0f, x) else x.coerceIn(0f, maxOf(0f, surfaces.maxOf { it.maxScrollX() }))
        val ny = if (surfaces.isEmpty()) maxOf(0f, y) else y.coerceIn(0f, maxOf(0f, surfaces.maxOf { it.maxScrollY() }))
        var changed = false
        if (nx != this.x) { this.x = nx; changed = true }
        if (ny != this.y) { this.y = ny; changed = true }
        // Another surface showing a shared state must hear even a layout's write.
        if (changed && (layoutDepth == 0 || shared)) Snapshot.withoutReadObservation { version++ }
        // A scroll of its own (not the layout's): a linked view tells the other side.
        if (changed && layoutDepth == 0) onOwnScroll?.invoke()
    }

    /** Set by a surface in a [LinkedScroll]: called after every scroll its layout did not make. */
    internal var onOwnScroll: (() -> Unit)? = null

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

/**
 * Test hook for the measure-before-draw rule: while an editor draws, nothing may measure a line or
 * move the scroll ([strict]: the UI tests fail on it). UI thread only.
 */
internal object DrawGuard {
    var strict = false
    var depth = 0
        private set

    inline fun <T> drawing(block: () -> T): T {
        depth++
        try { return block() } finally { depth-- }
    }

    fun check(what: String) {
        check(!(strict && depth > 0)) { "$what changed while drawing: measuring and scrolling belong to the layout pass" }
    }
}
