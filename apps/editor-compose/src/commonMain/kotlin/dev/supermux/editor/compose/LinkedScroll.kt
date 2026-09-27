package dev.supermux.editor.compose

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/** Which of two linked editors a surface is ([LinkedScroll]): A (a diff's base) or B (its working copy). */
enum class LinkedSide { A, B }

/**
 * Which lines of view A correspond to which of view B: the diff's changed runs ([Hunk]s, sorted,
 * not overlapping); everything between them is equal runs, line for line. (The diff plugin, M4,
 * builds it; a test or a demo can write one by hand.)
 */
@Immutable
class LineMapping(hunks: List<Hunk>) {
    /** A changed run: A's lines [aFrom, aTo) stand where B's [bFrom, bTo) are (either may be empty). */
    @Immutable
    data class Hunk(val aFrom: Int, val aTo: Int, val bFrom: Int, val bTo: Int) {
        init { require(aFrom in 0..aTo && bFrom in 0..bTo) { "bad hunk $this" } }
    }

    val hunks: List<Hunk> = hunks.sortedBy { it.aFrom }

    /**
     * The pair of lines [line] of [side] aligns by: in an equal run (or a changed run of the same
     * length on both sides), its counterpart line, aligned at their TEXT tops; in a changed run of
     * different lengths, the run's first lines, aligned at their BOX tops (so a gap widget above the
     * shorter side's next line fills the difference). Returns (a line, b line, is a run's start pair).
     */
    fun pair(side: LinkedSide, line: Int): Triple<Int, Int, Boolean> {
        val fromOf = { h: Hunk -> if (side == LinkedSide.A) h.aFrom else h.bFrom }
        val toOf = { h: Hunk -> if (side == LinkedSide.A) h.aTo else h.bTo }
        // The last hunk starting at or before the line (an empty one at the line counts as before it).
        var lo = 0
        var hi = hunks.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (fromOf(hunks[mid]) <= line) lo = mid + 1 else hi = mid }
        var i = lo - 1
        // Among hunks starting at this line, the one that contains it.
        while (i > 0 && fromOf(hunks[i - 1]) == fromOf(hunks[i]) && line >= toOf(hunks[i])) i--
        val h = hunks.getOrNull(i) ?: return Triple(line, line, false)
        val otherFrom = if (side == LinkedSide.A) h.bFrom else h.aFrom
        val otherTo = if (side == LinkedSide.A) h.bTo else h.aTo
        val mine = line - fromOf(h)
        val other = if (line < toOf(h)) {
            if (toOf(h) - fromOf(h) == otherTo - otherFrom) otherFrom + mine
            else return if (side == LinkedSide.A) Triple(h.aFrom, h.bFrom, true) else Triple(h.aFrom, h.bFrom, true)
        } else otherTo + (line - toOf(h))
        return if (side == LinkedSide.A) Triple(line, other, false) else Triple(other, line, false)
    }

    override fun equals(other: Any?) = other is LineMapping && other.hunks == hunks
    override fun hashCode() = hunks.hashCode()
    override fun toString() = "LineMapping($hunks)"

    companion object {
        /** Every line is its counterpart (two views of the same text). */
        val IDENTITY = LineMapping(emptyList())
    }
}

/**
 * Where two linked views are: a pair of corresponding lines ([a] in A, [b] in B) and how far below
 * their reference top the viewport starts ([px]); [hunk] says the reference is the lines' box tops
 * (a changed run's start pair), else their text tops.
 */
@Immutable
internal data class LinkedAnchor(val a: Int, val b: Int, val hunk: Boolean, val px: Float)

/**
 * Keeps two editors aligned line by line for a side-by-side diff: `Editor(viewA, linked = s,
 * linkedSide = A)` and `Editor(viewB, linked = s, linkedSide = B)`. Scrolling either (wheel, fling,
 * drag, scroll-into-view, page keys) scrolls the other so corresponding lines stay at the same y;
 * horizontal scrolling is shared too. The position is kept as a pair of corresponding LINES, so
 * measuring lines (wrapping, a widget's real height) moves nothing on screen: that is each side's
 * scroll anchoring while linked.
 *
 * Rows line up exactly when a changed run has the same height on both sides: the diff plugin puts
 * a gap block widget (a `BlockWidget` whose type nobody registered: empty space of exactly its
 * lines) ABOVE the line after the shorter side's run (below the last line when the run ends the
 * document), [LineMapping.Hunk]s describing the runs. A is the side that does not change while
 * linked (the base); after an edit of B, give the new [mapping].
 */
@Stable
class LinkedScroll(mapping: LineMapping = LineMapping.IDENTITY) {
    private var mappingState by mutableStateOf(mapping)

    /** The line correspondence; setting it keeps the position (re-derived from A's line). */
    var mapping: LineMapping
        get() = mappingState
        set(m) {
            if (m == mappingState) return
            mappingState = m
            val old = Snapshot.withoutReadObservation { anchor }
            val (na, nb, hunk) = m.pair(LinkedSide.A, old.a)
            val ctrl = sides[0]
            val px = if (ctrl == null) old.px else old.px + (ctrl.linkReference(old.a, old.hunk) ?: 0f) - (ctrl.linkReference(na, hunk) ?: 0f)
            anchor = LinkedAnchor(na, nb, hunk, px)
        }

    /** Observed by both sides' layout passes: each puts its scroll where this says. */
    internal var anchor: LinkedAnchor by mutableStateOf(LinkedAnchor(0, 0, false, 0f))

    /** The shared horizontal scroll. */
    internal var x: Float by mutableFloatStateOf(0f)

    internal val sides = arrayOfNulls<EditorController>(2)

    internal fun attach(side: LinkedSide, c: EditorController) { sides[side.ordinal] = c }
    internal fun detach(side: LinkedSide, c: EditorController) { if (sides[side.ordinal] === c) sides[side.ordinal] = null }

    /** Side [side] scrolled on its own (a gesture, a key): the pair of lines at its new top. */
    internal fun scrolledBy(side: LinkedSide, c: EditorController) {
        val y = c.scroll.y
        val line = c.lineAtScroll(y) ?: return
        val (a, b, hunk) = mappingState.pair(side, line)
        val ref = c.linkReference(if (side == LinkedSide.A) a else b, hunk) ?: return
        anchor = LinkedAnchor(a, b, hunk, y - ref)
        x = c.scroll.x
    }

    /** Side [side]'s document changed: its line of the pair follows the edit (until a new [mapping]). */
    internal fun followEdit(side: LinkedSide, tr: dev.supermux.editor.core.Transaction) {
        val cur = Snapshot.withoutReadObservation { anchor }
        val before = tr.startState.doc
        val mine = if (side == LinkedSide.A) cur.a else cur.b
        if (mine >= before.lineCount) return
        val moved = tr.state.doc.lineIndexAt(tr.changes.mapPos(before.lineStart(mine), -1))
        if (moved != mine) anchor = if (side == LinkedSide.A) cur.copy(a = moved) else cur.copy(b = moved)
    }
}
