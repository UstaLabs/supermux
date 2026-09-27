package dev.supermux.editor.compose

import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet

/** A hidden (replaced) range [from, to) and the widget shown in its place (null: nothing). */
internal class ReplaceRange(val from: Int, val to: Int, val widget: WidgetKey?)

/** An inline widget at [pos]; [side] < 0 draws it before a cursor at [pos], else after. */
internal class InlinePoint(val pos: Int, val key: WidgetKey, val side: Int, val order: Int)

/**
 * One piece of a visual line: document text [from, to), or a widget (an inline widget at a point,
 * or a replaced range's placeholder, [from, to) the range it hides). A widget is one character of
 * the line's layout (covered by a placeholder as wide as the widget), none for a Replace without one.
 */
internal class LinePart(val from: Int, val to: Int, val widget: WidgetKey?, val isText: Boolean, val side: Int = 1) {
    /** Its length in the line's layout. */
    val layoutLength: Int get() = if (isText) to - from else if (widget != null) 1 else 0
}

/**
 * The state's `Replace` and `InlineWidget` decorations as the surface lays them out: a line that
 * holds any is laid out from its [LinePart]s (layout offsets then differ from document offsets:
 * [LineMap]); the lines a Replace spans after its first are HIDDEN (zero height, never laid out,
 * never numbered), their text after the range's end joined to the first line's row (CM6's fold).
 *
 * Overlapping replaces merge (the first one's widget is shown). Lines over [Geometry.LONG_LINE]
 * units are laid out in pieces and show neither: their replaces hide nothing.
 */
internal class Folds private constructor(
    val replaces: List<ReplaceRange>,
    val inline: List<InlinePoint>,
    private val hiddenFrom: IntArray,
    private val hiddenTo: IntArray,
) {
    val isEmpty: Boolean get() = replaces.isEmpty() && inline.isEmpty()

    /** The hidden line ranges, in order. */
    val hidden: List<IntRange> get() = hiddenFrom.indices.map { hiddenFrom[it]..hiddenTo[it] }

    private fun hiddenIndex(line: Int): Int {
        var lo = 0
        var hi = hiddenFrom.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                line < hiddenFrom[mid] -> hi = mid - 1
                line > hiddenTo[mid] -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    fun isHidden(line: Int): Boolean = hiddenFrom.isNotEmpty() && hiddenIndex(line) >= 0

    /** The line whose row shows [line]: itself, or for a hidden line the first line of its fold. */
    fun visualLine(line: Int): Int {
        val i = if (hiddenFrom.isEmpty()) -1 else hiddenIndex(line)
        return if (i < 0) line else hiddenFrom[i] - 1
    }

    /** The last document line [line]'s row shows (the end of a fold that starts on it). */
    fun lastJoined(line: Int): Int {
        if (hiddenFrom.isEmpty() || line + 1 > hiddenTo.last()) return line
        val i = hiddenIndex(line + 1)
        return if (i < 0) line else hiddenTo[i]
    }

    /** The document lines of [range] that are shown (hidden runs skipped without walking them). */
    fun shownLines(range: IntRange): List<Int> {
        if (range.isEmpty()) return emptyList()
        if (hiddenFrom.isEmpty()) return range.toList()
        val out = ArrayList<Int>()
        var l = range.first
        while (l <= range.last) {
            val i = hiddenIndex(l)
            if (i >= 0) { l = hiddenTo[i] + 1; continue }
            out += l
            l++
        }
        return out
    }

    /** The replace strictly containing [pos] (from < pos < to), if any. */
    fun replaceInside(pos: Int): ReplaceRange? {
        if (replaces.isEmpty()) return null
        var lo = 0
        var hi = replaces.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (replaces[mid].from < pos) lo = mid + 1 else hi = mid }
        val r = replaces.getOrNull(lo - 1) ?: return null
        return if (pos > r.from && pos < r.to) r else null
    }

    private fun firstReplaceEndingAtOrAfter(pos: Int): Int {
        var lo = 0
        var hi = replaces.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (replaces[mid].to < pos) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun firstPointAtOrAfter(pos: Int): Int {
        var lo = 0
        var hi = inline.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (inline[mid].pos < pos) lo = mid + 1 else hi = mid }
        return lo
    }

    /** True when document range [from, to] holds a replace or an inline widget (the fast path's test). */
    fun touches(from: Int, to: Int): Boolean {
        val r = replaces.getOrNull(firstReplaceEndingAtOrAfter(from))
        if (r != null && r.from <= to) return true
        val p = inline.getOrNull(firstPointAtOrAfter(from))
        return p != null && p.pos <= to
    }

    /**
     * The parts of the visual row running from document offset [from] to [to] (a line's start to
     * the end of what it joins): text, inline widgets (in their order at a point: side < 0 first),
     * replace placeholders. Null when it has none (laid out as plain text).
     */
    fun parts(from: Int, to: Int): List<LinePart>? {
        if (isEmpty || !touches(from, to)) return null
        val out = ArrayList<LinePart>()
        var pos = from
        var pi = firstPointAtOrAfter(from)
        fun text(upTo: Int) {
            // Inline widgets on the way, each at its point.
            while (pi < inline.size && inline[pi].pos <= upTo) {
                val p = inline[pi++]
                if (p.pos < pos) continue
                if (p.pos > pos) { out += LinePart(pos, p.pos, null, true); pos = p.pos }
                out += LinePart(p.pos, p.pos, p.key, false, p.side)
            }
            if (upTo > pos) { out += LinePart(pos, upTo, null, true); pos = upTo }
        }
        var ri = firstReplaceEndingAtOrAfter(from)
        while (ri < replaces.size && replaces[ri].from <= to) {
            val r = replaces[ri++]
            if (r.from < pos) continue // started before this row: not its own
            text(r.from)
            out += LinePart(r.from, r.to, r.widget, false)
            pos = r.to
            while (pi < inline.size && inline[pi].pos < pos) pi++
        }
        text(to)
        return out
    }

    companion object {
        val EMPTY = Folds(emptyList(), emptyList(), IntArray(0), IntArray(0))

        /**
         * The replaces and inline widgets of [decos] over [doc]; [previous]'s per-set extraction is
         * reused for the sets that are the same instances ([Cache]).
         */
        fun build(doc: Rope, decos: List<RangeSet<Decoration>>, cache: Cache, longLine: (Int) -> Boolean): Folds {
            val per = cache.extract(decos)
            val rs = ArrayList<ReplaceRange>()
            val pts = ArrayList<InlinePoint>()
            var order = 0
            for ((replaces, points) in per) {
                for (r in replaces) rs += ReplaceRange(r.from.coerceIn(0, doc.length), r.to.coerceIn(0, doc.length), r.widget)
                for (p in points) pts += InlinePoint(p.pos.coerceIn(0, doc.length), p.key, p.side, order++)
            }
            if (rs.isEmpty() && pts.isEmpty()) return EMPTY
            rs.sortWith(compareBy({ it.from }, { -it.to }))
            val merged = ArrayList<ReplaceRange>()
            for (r in rs) {
                if (r.from >= r.to) continue
                val last = merged.lastOrNull()
                if (last != null && r.from < last.to) {
                    if (r.to > last.to) merged[merged.size - 1] = ReplaceRange(last.from, r.to, last.widget)
                } else merged += r
            }
            // Replaces on a long line (laid out in pieces) are not shown: they hide nothing.
            val shown = merged.filter { !longLine(doc.lineIndexAt(it.from)) && !longLine(doc.lineIndexAt(it.to)) }
            pts.sortWith(compareBy({ it.pos }, { if (it.side < 0) 0 else 1 }, { it.order }))
            val points = pts.filter { p -> !longLine(doc.lineIndexAt(p.pos)) && shown.none { p.pos > it.from && p.pos < it.to } }
            val hf = ArrayList<Int>()
            val ht = ArrayList<Int>()
            for (r in shown) {
                val first = doc.lineIndexAt(r.from)
                val last = doc.lineIndexAt(r.to)
                if (last <= first) continue
                if (hf.isNotEmpty() && first + 1 <= ht.last() + 1) { if (last > ht.last()) ht[ht.size - 1] = last }
                else { hf += first + 1; ht += last }
            }
            return Folds(shown, points, hf.toIntArray(), ht.toIntArray())
        }

        /** The replaced ranges of [state] (for commands on a target no surface shows). */
        fun of(state: EditorState): Folds = build(state.doc, state.facet(decorationsFacet), Cache()) { false }
    }

    /** Per decoration set, the replaces and inline widgets found in it, kept while the set is the same instance. */
    class Cache {
        private var sets: List<RangeSet<Decoration>> = emptyList()
        private var found: List<Pair<List<ReplaceRange>, List<InlinePoint>>> = emptyList()

        fun extract(decos: List<RangeSet<Decoration>>): List<Pair<List<ReplaceRange>, List<InlinePoint>>> {
            val old = sets
            val oldFound = found
            found = decos.mapIndexed { i, set ->
                if (i < old.size && old[i] === set) oldFound[i] else {
                    val rs = ArrayList<ReplaceRange>()
                    val ps = ArrayList<InlinePoint>()
                    for (r in set) when (val v = r.value) {
                        is Decoration.Replace -> if (r.from < r.to) rs += ReplaceRange(r.from, r.to, v.widget)
                        is Decoration.InlineWidget -> if (r.from == r.to) ps += InlinePoint(r.from, v.key, v.side, 0)
                        else -> Unit
                    }
                    rs to ps
                }
            }
            sets = decos
            return found
        }
    }
}

/**
 * Document offsets <-> layout offsets of one visual row laid out from [parts] (see [Folds]). A
 * position at an inline widget's point is before it when its side is > 0 and after it when < 0; a
 * position inside a replaced range is at its start; the range's end is after its placeholder.
 */
internal class LineMap(val parts: List<LinePart>) {
    val layoutLength: Int = parts.sumOf { it.layoutLength }
    val docFrom: Int = parts.firstOrNull()?.from ?: 0
    val docTo: Int = parts.lastOrNull()?.to ?: 0

    /** The layout offset where a caret at document offset [pos] is drawn. */
    fun toLayout(pos: Int): Int {
        var k = 0
        for ((i, p) in parts.withIndex()) {
            val len = p.layoutLength
            when {
                p.isText -> {
                    if (pos >= p.from && pos < p.to) return k + (pos - p.from)
                    if (pos == p.to) return skipBefore(i + 1, k + len, pos)
                }
                p.from == p.to -> if (pos == p.from) return if (p.side > 0) k else skipBefore(i + 1, k + len, pos)
                else -> {
                    if (pos >= p.from && pos < p.to) return k
                    if (pos == p.to) return skipBefore(i + 1, k + len, pos)
                }
            }
            k += len
        }
        return layoutLength
    }

    /** From part [from] at layout offset [at]: past the inline widgets at [pos] drawn before a caret (side < 0). */
    private fun skipBefore(from: Int, at: Int, pos: Int): Int {
        var k = at
        for (i in from until parts.size) {
            val p = parts[i]
            if (p.isText || p.from != pos || p.to != pos || p.side >= 0) break
            k += p.layoutLength
        }
        return k
    }

    /** The document offset of layout offset [k] (at a widget: its point, or its range's start). */
    fun toDoc(k: Int): Int {
        var k0 = 0
        for (p in parts) {
            val len = p.layoutLength
            if (p.isText) { if (k >= k0 && k < k0 + len) return p.from + (k - k0) }
            else if (len > 0 && k == k0) return p.from
            k0 += len
        }
        return docTo
    }

    /** Each widget part with the layout offset of its character (-1: a Replace without a widget). */
    fun widgetChars(): List<Pair<LinePart, Int>> {
        val out = ArrayList<Pair<LinePart, Int>>()
        var k = 0
        for (p in parts) {
            if (!p.isText) out += p to (if (p.widget != null) k else -1)
            k += p.layoutLength
        }
        return out
    }
}
