// Scroll-to-hide chrome for a NARROW chat view: scrolling down through the transcript slides the
// header up and the composer down so there is more room to read; scrolling back up brings them
// back, and so does coming NEAR either end of the transcript — early, so the chrome is already
// back by the time you arrive instead of jumping in at the edge. The trigger is the chat view's own
// WIDTH, not the device — a desktop window (or a split pane) as narrow as a phone gets the same
// behaviour as a phone.
package dev.supermux.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A chat view narrower than this (the compact width class: a phone, or a pane that narrow) auto-hides its chrome. */
val CHAT_CHROME_AUTO_HIDE_MAX_WIDTH = 600.dp

/**
 * Hidden/shown state driven by USER scrolls only. It listens on nested scroll, which programmatic
 * scrolls (the transcript's follow-the-bottom autoscroll) never pass through — a new message
 * arriving does not hide the composer.
 *
 * [thresholdPx] of travel in one direction is needed before it flips, so a jittery drag near a
 * standstill does not make the chrome flicker. [edge] says whether the transcript is inside the
 * zone at its top or bottom ([transcriptEdge]):
 *  - TOP: the chrome shows and never hides there.
 *  - BOTTOM: the chrome shows and STAYS until you scroll back up. Without that latch it would
 *    flicker: the composer coming back shrinks the viewport, which pushes you out of the zone, so
 *    the next bit of downward scroll would hide it again, and so on to the end.
 */
@Stable
class ChatChromeAutoHide(private val thresholdPx: Float, private val edge: () -> TranscriptEdge) {
    var hidden by mutableStateOf(false)

    /** Accumulated user scroll in the current direction; negative = moving down the transcript. */
    private var travel = 0f

    /** Shown by the bottom zone; downward scroll cannot hide it until an upward one clears this. */
    private var latchedAtBottom = false

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            if (dy == 0f && available.y == 0f) return Offset.Zero
            when (edge()) {
                TranscriptEdge.TOP -> { show(); return Offset.Zero }
                TranscriptEdge.BOTTOM -> { show(); latchedAtBottom = true; return Offset.Zero }
                TranscriptEdge.NONE -> {}
            }
            if (dy == 0f) return Offset.Zero
            if ((dy < 0f) != (travel < 0f)) travel = 0f
            travel += dy
            when {
                travel <= -thresholdPx && !latchedAtBottom -> hidden = true
                travel >= thresholdPx -> { hidden = false; latchedAtBottom = false }
            }
            return Offset.Zero
        }
    }

    fun show() {
        hidden = false
        travel = 0f
    }
}

enum class TranscriptEdge { NONE, TOP, BOTTOM }

/**
 * Which end of [state] is within [zonePx], if either. The end is measured from the last item's
 * bottom edge (plus the list's after-content padding) to the viewport's end, so it is exact once
 * the last item is laid out and NONE while it is still off screen.
 */
fun transcriptEdge(state: LazyListState, zonePx: Float): TranscriptEdge {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return TranscriptEdge.TOP
    if (last.index == info.totalItemsCount - 1) {
        val remaining = last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
        if (remaining < zonePx) return TranscriptEdge.BOTTOM
    }
    if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset < zonePx) return TranscriptEdge.TOP
    return TranscriptEdge.NONE
}

/**
 * Collapse this element's height to `fraction()` of its measured height, clipping what no longer
 * fits. [slideUp] = the element leaves through its top edge (a header); otherwise through its
 * bottom edge (a composer). Content stays COMPOSED at every fraction, so a hidden composer keeps
 * its staged attachments and a hidden header keeps its open state.
 *
 * `fraction` is read in the layout phase, so an animation drives it without recomposing.
 */
fun Modifier.collapseVertically(fraction: () -> Float, slideUp: Boolean): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val f = fraction().coerceIn(0f, 1f)
        if (f >= 1f) {
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            val h = (placeable.height * f).roundToInt()
            layout(placeable.width, h) { placeable.place(0, if (slideUp) h - placeable.height else 0) }
        }
    }
