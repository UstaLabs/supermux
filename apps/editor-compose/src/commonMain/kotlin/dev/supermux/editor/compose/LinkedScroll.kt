package dev.supermux.editor.compose

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.supermux.editor.core.LineMapping
import dev.supermux.editor.core.lineMappingFacet

/** Which of two linked editors a surface is ([LinkedScroll]): A (a diff's base) or B (its working copy). */
enum class LinkedSide { A, B }

/**
 * A point both sides must show at the same y: the text top of a pair of corresponding lines
 * ([TEXT]), the start of a changed run of different lengths ([HUNK]: the box top of its first line,
 * or where the run would be on a side where it is empty), the document's start and end.
 */
internal enum class SyncKind { BEGIN, TEXT, HUNK, END }

@Immutable
internal data class Sync(val kind: SyncKind, val a: Int, val b: Int, val lenA: Int = 0, val lenB: Int = 0) {
    fun pos(side: LinkedSide) = if (side == LinkedSide.A) a else b
    fun len(side: LinkedSide) = if (side == LinkedSide.A) lenA else lenB

    companion object { val BEGIN = Sync(SyncKind.BEGIN, 0, 0) }
}

/** The shared position: [px] below sync point [sync] on both sides. */
@Immutable
internal data class LinkedAnchor(val sync: Sync, val px: Float)

/**
 * Keeps two editors aligned line by line for a side-by-side diff:
 * `Editor(viewA, linked = s, linkedSide = LinkedSide.A)` and `Editor(viewB, linked = s, linkedSide = LinkedSide.B)`.
 *
 * - **Which lines correspond** is data: the [LineMapping] a diff plugin puts in `lineMappingFacet`
 *   (B's state, else A's; none: line for line). The host only pairs the views.
 * - **The heights are the surface's**: every pair of corresponding lines is aligned at its text top,
 *   and every changed run of different lengths at its start, from BOTH sides' measured height maps
 *   (wrapping, zoom, block widgets, folds: whatever each side measured). The shorter side of each
 *   stretch gets alignment padding in its height map; no gap widget is needed (one a plugin adds
 *   anyway is simply part of that side's height).
 * - **Scrolling** either side (wheel, fling, drag, scroll-into-view, page keys) moves the shared
 *   position, a sync point plus pixels; each side's layout pass puts its scroll there, so the other
 *   side follows in the same frame and a height measured anywhere moves nothing on screen (each
 *   side's scroll anchoring while linked). Horizontal scroll is shared.
 * - A mapping that no longer fits a document (B edited, the plugin not caught up) is clamped: lines
 *   past a side's end pair with its end.
 */
@Stable
class LinkedScroll {
    internal val sides = arrayOfNulls<EditorController>(2)

    /** Observed by both layout passes. */
    internal var anchor: LinkedAnchor by mutableStateOf(LinkedAnchor(Sync.BEGIN, 0f))

    /** The shared horizontal scroll. */
    internal var x: Float by mutableFloatStateOf(0f)

    // Bumped when the OTHER side changed this side's alignment padding: this side lays out again.
    private val versions = arrayOf(mutableIntStateOf(0), mutableIntStateOf(0))
    internal fun observe(side: LinkedSide) = versions[side.ordinal].intValue
    private fun bump(side: LinkedSide) = Snapshot.withoutReadObservation { versions[side.ordinal].intValue++ }

    private var lastMapping: LineMapping? = null

    /** Alignment passes that changed some padding (a test hook). */
    var alignments = 0
        private set

    internal fun attach(side: LinkedSide, c: EditorController) {
        sides[side.ordinal] = c
        lastMapping = null
    }

    internal fun detach(side: LinkedSide, c: EditorController) { if (sides[side.ordinal] === c) sides[side.ordinal] = null }

    private fun ctrl(side: LinkedSide) = sides[side.ordinal]
    private fun ready(c: EditorController?) = c != null && c.linkReady()

    /** The diff plugin's mapping: B's state first, then A's; none: line for line. */
    internal fun mapping(): LineMapping =
        ctrl(LinkedSide.B)?.view?.state?.facet(lineMappingFacet) ?: ctrl(LinkedSide.A)?.view?.state?.facet(lineMappingFacet) ?: LineMapping.IDENTITY

    // ------------------------------------------------------------------ sync points --

    /** A boundary between stretches: A line [a], B line [b], and the first hunk not yet walked past. */
    private data class Cursor(val a: Int, val b: Int, val hi: Int)

    private inner class Walker(val m: LineMapping, val nA: Int, val nB: Int) {
        /** The sync point at boundary [c] and the boundary after its stretch (null after the end). */
        fun next(c: Cursor): Pair<Sync, Cursor?> {
            val (a, b) = c
            if (a >= nA && b >= nB) return Sync(SyncKind.END, nA, nB) to null
            var hi = c.hi
            val hs = m.hunks
            while (hi < hs.size && hs[hi].aFrom < a) hi++
            val h = hs.getOrNull(hi)
            if (h != null && h.aFrom == a) {
                val lenA = (minOf(h.aTo, nA) - a).coerceAtLeast(0)
                val lenB = (minOf(b + (h.bTo - h.bFrom), nB) - b).coerceAtLeast(0)
                // A run of different lengths is one stretch; one as long on both sides pairs line by line.
                if (lenA != lenB) return Sync(SyncKind.HUNK, a, b, lenA, lenB) to Cursor(a + lenA, b + lenB, hi + 1)
                if (lenA == 0) return next(Cursor(a, b, hi + 1))
            }
            if (a < nA && b < nB) return Sync(SyncKind.TEXT, a, b) to Cursor(a + 1, b + 1, hi)
            // A stale mapping: one side ended; the rest of the other is one run.
            return Sync(SyncKind.HUNK, a, b, nA - a, nB - b) to Cursor(nA, nB, hs.size)
        }

        /** The boundary starting the stretch that holds [line] of [side]. */
        fun cursorFor(side: LinkedSide, line: Int): Cursor {
            val hs = m.hunks
            val isA = side == LinkedSide.A
            var lo = 0
            var hi = hs.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if ((if (isA) hs[mid].aFrom else hs[mid].bFrom) <= line) lo = mid + 1 else hi = mid }
            val idx = lo - 1
            val h = hs.getOrNull(idx)
            val c = if (h == null) Cursor(line, line, 0) else {
                val from = if (isA) h.aFrom else h.bFrom
                val to = if (isA) h.aTo else h.bTo
                val oFrom = if (isA) h.bFrom else h.aFrom
                val oTo = if (isA) h.bTo else h.aTo
                val (mine, other, next) = when {
                    line >= to -> Triple(line, oTo + (line - to), idx + 1)
                    to - from == oTo - oFrom -> Triple(line, oFrom + (line - from), idx)
                    else -> Triple(from, oFrom, idx)
                }
                if (isA) Cursor(mine, other, next) else Cursor(other, mine, next)
            }
            return Cursor(c.a.coerceIn(0, nA), c.b.coerceIn(0, nB), c.hi)
        }

        fun syncFor(side: LinkedSide, line: Int): Sync = next(cursorFor(side, line)).first

        /** The sync point before [s] on [side] (walking from the line before it), or null at the start. */
        fun prev(side: LinkedSide, s: Sync): Sync? {
            if (s.kind == SyncKind.BEGIN) return null
            val p = s.pos(side)
            if (p <= 0) return Sync.BEGIN
            var cur: Cursor? = cursorFor(side, p - 1)
            var last: Sync? = null
            var guard = 0
            while (cur != null && guard++ < 10_000) {
                val (sync, after) = next(cur)
                if (sync == s || sync.pos(side) > p) return last ?: Sync.BEGIN
                last = sync
                cur = after
            }
            return last
        }
    }

    private fun walker(): Walker? {
        val a = ctrl(LinkedSide.A) ?: return null
        val b = ctrl(LinkedSide.B) ?: return null
        return Walker(mapping(), a.view.state.doc.lineCount, b.view.state.doc.lineCount)
    }

    /** Where [s] is on [side] (content y), or null while that side cannot say. */
    internal fun yOf(side: LinkedSide, s: Sync): Float? {
        val c = ctrl(side)?.takeIf { it.linkReady() } ?: return null
        val hm = c.linkHeights
        val n = hm.lineCount
        val p = s.pos(side).coerceIn(0, n)
        return when (s.kind) {
            SyncKind.BEGIN -> 0f
            SyncKind.END -> hm.totalHeight
            SyncKind.TEXT -> if (p < n) c.geometry.lineTop(p) else hm.totalHeight - hm.endPadding
            SyncKind.HUNK -> when {
                p >= n -> hm.totalHeight - hm.endPadding
                s.len(side) > 0 -> hm.boxTop(p)
                else -> hm.top(p)
            }
        }
    }

    /** The padding slot right before [s] on [side]: what the alignment stretches. */
    private sealed class Slot {
        data class Line(val line: Int, val kind: HeightMap.Pad) : Slot()
        data object Top : Slot()
        data object End : Slot()
    }

    private fun slotOf(side: LinkedSide, s: Sync, n: Int): Slot? {
        val p = s.pos(side)
        return when (s.kind) {
            SyncKind.BEGIN -> null
            SyncKind.END -> Slot.End
            SyncKind.TEXT -> if (p < n) Slot.Line(p, HeightMap.Pad.BEFORE_TEXT) else Slot.End
            SyncKind.HUNK -> when {
                s.len(side) > 0 && p < n -> Slot.Line(p, HeightMap.Pad.BEFORE_BOX)
                p > 0 -> Slot.Line(minOf(p, n) - 1, HeightMap.Pad.AFTER_BOX)
                else -> Slot.Top
            }
        }
    }

    private fun HeightMap.padAt(slot: Slot): Float = when (slot) {
        is Slot.Line -> pad(slot.line, slot.kind)
        Slot.Top -> topPadding
        Slot.End -> endPadding
    }

    private fun HeightMap.setPadAt(slot: Slot, v: Float) = when (slot) {
        is Slot.Line -> setPad(slot.line, slot.kind, v)
        Slot.Top -> setTopPad(v)
        Slot.End -> setEndPad(v)
    }

    // ------------------------------------------------------------------ alignment --

    /** A new mapping: the old padding is dropped and the position re-taken from A's scroll. */
    private fun followMapping() {
        val m = mapping()
        if (m == lastMapping) return
        val ca = ctrl(LinkedSide.A)?.takeIf { it.linkReady() } ?: return
        val cb = ctrl(LinkedSide.B)?.takeIf { it.linkReady() } ?: return
        val first = lastMapping == null
        lastMapping = m
        val y = ca.scroll.y
        val line = ca.linkHeights.lineAt(y)
        val off = y - ca.geometry.lineTop(line)
        if (!first) {
            ca.linkHeights.clearPads()
            cb.linkHeights.clearPads()
        }
        val w = walker() ?: return
        val y2 = ca.geometry.lineTop(line) + off
        val s = syncAtOrBefore(w, LinkedSide.A, y2)
        anchor = LinkedAnchor(s, y2 - (yOf(LinkedSide.A, s) ?: 0f))
        bump(LinkedSide.A)
        bump(LinkedSide.B)
    }

    private fun syncAtOrBefore(w: Walker, side: LinkedSide, y: Float): Sync {
        val c = ctrl(side) ?: return Sync.BEGIN
        var s = w.syncFor(side, c.linkHeights.lineAt(y))
        var guard = 0
        while (guard++ < 64) {
            val sy = yOf(side, s) ?: break
            if (sy <= y) break
            s = w.prev(side, s) ?: break
        }
        return s
    }

    /**
     * Align the rows both sides show (and a screen around them): measure the lines in the window on
     * both sides, then pad the shorter side before each sync point so that consecutive sync points
     * are as far apart on both. Called from a side's layout pass, before it puts its scroll at the
     * anchor. True when [caller]'s own padding changed.
     */
    internal fun align(caller: LinkedSide): Boolean {
        val ca = ctrl(LinkedSide.A)
        val cb = ctrl(LinkedSide.B)
        if (!ready(ca) || !ready(cb)) return false
        ca!!; cb!!
        followMapping()
        val w = walker() ?: return false
        val a = Snapshot.withoutReadObservation { anchor }
        // The window on each side: a screen above and below what the anchor puts on screen.
        val windows = arrayOf(ca, cb).mapIndexed { i, c ->
            val side = LinkedSide.entries[i]
            val y = (yOf(side, a.sync) ?: 0f) + a.px
            val vh = maxOf(c.viewportSize.height, c.layouts.lineHeightPx)
            val hm = c.linkHeights
            val from = hm.lineAt(y - vh)
            val to = hm.lineAt(y + 2 * vh)
            for (l in from..to) c.geometry.measure(l)
            from to to
        }
        val (fa, ta) = windows[0]
        val (fb, tb) = windows[1]
        // From the earlier start of the two windows (the document's start when both reach it).
        val ka = w.cursorFor(LinkedSide.A, fa)
        val kb = w.cursorFor(LinkedSide.B, fb)
        var cur: Cursor?
        var prev: Sync
        if (fa == 0 && fb == 0) {
            prev = Sync.BEGIN
            cur = Cursor(0, 0, 0)
        } else {
            val start = if (ka.a <= kb.a && ka.b <= kb.b) ka else if (kb.a <= ka.a && kb.b <= ka.b) kb else if (ka.a <= kb.a) ka else kb
            val (s0, after0) = w.next(start)
            prev = s0
            cur = after0
        }
        val pads = HashMap<Pair<LinkedSide, Slot>, Float>()
        val maps = arrayOf(ca.linkHeights, cb.linkHeights)
        var guard = 0
        while (guard++ < 5_000) {
            val c0 = cur ?: break
            val (s, after) = w.next(c0)
            val nat = FloatArray(2)
            val slots = arrayOfNulls<Slot>(2)
            var ok = true
            for (i in 0..1) {
                val side = LinkedSide.entries[i]
                val slot = slotOf(side, s, maps[i].lineCount) ?: run { ok = false; null }
                slots[i] = slot
                val y1 = yOf(side, s)
                val y0 = yOf(side, prev)
                if (y1 == null || y0 == null || slot == null) { ok = false; continue }
                nat[i] = y1 - y0 - maps[i].padAt(slot)
            }
            if (ok) {
                val target = maxOf(nat[0], nat[1], 0f)
                for (i in 0..1) {
                    val side = LinkedSide.entries[i]
                    val key = side to slots[i]!!
                    // Two sync points sharing a slot (adjacent empty runs) add up.
                    pads[key] = (pads[key] ?: 0f) + (target - nat[i]).coerceAtLeast(0f)
                }
            }
            prev = s
            cur = after
            if (s.kind == SyncKind.END || (s.a > ta && s.b > tb)) break
        }
        val changed = BooleanArray(2)
        for ((key, v) in pads) {
            val (side, slot) = key
            val hm = maps[side.ordinal]
            if (kotlin.math.abs(hm.padAt(slot) - v) > 0.01f) { hm.setPadAt(slot, v); changed[side.ordinal] = true }
        }
        if (changed[0] || changed[1]) alignments++
        val other = if (caller == LinkedSide.A) LinkedSide.B else LinkedSide.A
        if (changed[other.ordinal]) bump(other)
        return changed[caller.ordinal]
    }

    /** Side [side] scrolled on its own (a gesture, a key): the sync point at its new top, and the pixels past it. */
    internal fun scrolledBy(side: LinkedSide, c: EditorController) {
        if (!c.linkReady()) return
        followMapping()
        val w = walker() ?: return
        val y = c.scroll.y
        val s = syncAtOrBefore(w, side, y)
        val sy = yOf(side, s) ?: return
        anchor = LinkedAnchor(s, y - sy)
        x = c.scroll.x
    }

    /** Side [side]'s document changed: its end of the anchor follows the edit (until a new mapping). */
    internal fun followEdit(side: LinkedSide, tr: dev.supermux.editor.core.Transaction) {
        val cur = Snapshot.withoutReadObservation { anchor }
        if (cur.sync.kind == SyncKind.BEGIN || cur.sync.kind == SyncKind.END) return
        val before = tr.startState.doc
        val mine = cur.sync.pos(side)
        val moved = if (mine >= before.lineCount) tr.state.doc.lineCount else tr.state.doc.lineIndexAt(tr.changes.mapPos(before.lineStart(mine), -1))
        if (moved != mine) anchor = cur.copy(sync = if (side == LinkedSide.A) cur.sync.copy(a = moved) else cur.sync.copy(b = moved))
    }
}
