package dev.supermux.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/**
 * Card #121: the one owner of a chat transcript's scroll position. Nothing else in the chat moves it.
 *
 * The reader is either FOLLOWING ([following]: the end of the transcript is on screen) or READING (the row at the
 * top of the screen stays where it is). Only the reader changes the mode, by scrolling ([watchReader]), or an
 * explicit jump he asked for ([jump]: find, reveal, a subagent card, the jump button). Rows arriving, the history
 * landing, rows changing height, the keyboard and the composer never change it.
 *
 * READING needs nothing from here: the list is top-anchored and keeps its first visible row by key through rows
 * inserted above, rows growing below and the viewport changing. FOLLOWING is held by [keepEnd], called after
 * anything that can move the end: from composition (it lands in the same frame's measure, so nothing is ever drawn
 * off the end), and after a layout that left the end off screen ([holdEnd]; a row that measured taller on its own,
 * the viewport shrinking under the keyboard), one frame later. Never with an animated scroll: a reply being
 * written is the one thing the end eases after ([glide]).
 */
internal class TranscriptScroll(
    val list: LazyListState,
    private val place: TranscriptPlace,
    private val slopPx: Float,
) {
    /** The mode. Kept in [place], so a rebuilt panel (a rotation, a width change) keeps it (#89). */
    var following: Boolean
        get() = place.autoFollow
        set(value) { place.autoFollow = value }

    /** Scrolls this owner runs itself (a jump, a glide): what they pass through is not the reader's scrolling. */
    private var ours by mutableIntStateOf(0)

    fun atEnd(): Boolean = transcriptAtEnd(list, slopPx)

    /** FOLLOWING: put the end of [lastIndex] on screen in the next measure. Never while the reader is scrolling. */
    fun keepEnd(lastIndex: Int) {
        if (!following || lastIndex < 0 || ours > 0 || list.isScrollInProgress) return
        list.requestScrollToItem(lastIndex, END_OFFSET)
    }

    /** An explicit jump: animated, and the mode afterwards is wherever it landed. */
    suspend fun jump(block: suspend LazyListState.() -> Unit) {
        ours++
        try {
            list.block()
        } finally {
            ours--
            following = atEnd()
        }
    }

    /** A reply being written while FOLLOWING: ease after its end instead of jumping a line at a time. */
    suspend fun glide() {
        if (!following) return
        ours++
        try {
            glideToEnd(list)
        } finally {
            ours--
        }
    }

    /** The reader's own scrolling sets the mode: at the end → FOLLOWING, anywhere else → READING. */
    suspend fun watchReader() {
        var reading = false
        snapshotFlow { Triple(list.isScrollInProgress, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
            .collect { (scrolling, _, _) ->
                if (scrolling && ours == 0) {
                    reading = true
                    following = atEnd()
                } else if (!scrolling && reading) {
                    reading = false
                    following = atEnd()
                }
            }
    }

    /**
     * FOLLOWING: after a layout that left the end off screen without the reader scrolling (a row measuring taller on
     * its own, the viewport shrinking), put it back. [glideNow] says a reply is being written, which eases instead.
     */
    suspend fun holdEnd(lastIndex: () -> Int, glideNow: () -> Boolean) {
        snapshotFlow { list.layoutInfo }.collect {
            // The exact end, not [atEnd]'s slop: that slop decides the MODE; holding the end leaves nothing hidden.
            if (!following || ours > 0 || list.isScrollInProgress || distanceToEnd(list) <= 0.5f) return@collect
            if (glideNow()) glide() else keepEnd(lastIndex())
        }
    }

    private var measuredHeight = -1

    /**
     * FOLLOWING through a change of the transcript's own height (the keyboard, the composer growing, the chrome
     * sliding): the end is put back before the list measures, in the same layout pass, so no frame shows it gone.
     */
    fun holdEndThroughResize(lastIndex: () -> Int): Modifier = Modifier.layout { measurable, constraints ->
        if (constraints.maxHeight != measuredHeight) {
            if (measuredHeight >= 0) keepEnd(lastIndex())
            measuredHeight = constraints.maxHeight
        }
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    private companion object {
        /** Past any row's height: the list settles the last row's end at the viewport's end. */
        const val END_OFFSET = Int.MAX_VALUE / 4
    }
}
