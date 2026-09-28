package dev.supermux.editor.core

/**
 * What a plugin wants drawn, as DATA. The core never draws; editor-compose maps these to pixels.
 *
 * Styling is by semantic class names (`tok-keyword`, `diff-add`, `search-match`) that the
 * surface's theme resolves, so no Compose type ever enters a plugin. Widgets are referenced by
 * [WidgetKey]; the surface looks up what to render for a key.
 */
sealed class Decoration {
    /** Style a span of text. [inclusiveStart]/[inclusiveEnd]: does text typed at the edge join the mark? */
    data class Mark(
        val classes: Set<String>,
        val inclusiveStart: Boolean = false,
        val inclusiveEnd: Boolean = false,
    ) : Decoration()

    /** Style a whole line (put it at the line's start position, zero length). */
    data class LineStyle(val classes: Set<String>) : Decoration()

    /** A widget inside a line, at a point. [side] < 0 draws it before a cursor at that point. */
    data class InlineWidget(val key: WidgetKey, val side: Int = 1) : Decoration()

    /**
     * A widget with its own height BETWEEN lines: diff alignment gaps, "⋯ 120 unchanged lines",
     * review threads. [above] places it above the line containing the position, else below.
     */
    data class BlockWidget(val key: WidgetKey, val above: Boolean = false, val estimatedHeightLines: Float = 1f) : Decoration()

    /**
     * Hide a range, optionally showing a widget instead (a folded region's "…"). The caret never
     * lands inside it (the surface cannot show it there). [fold]: a fold (the fold plugin's), which
     * is [atomic] by default; any other Replace is atomic only when it opts in (or when its range is
     * in [atomicRangesFacet]).
     */
    data class Replace(val widget: WidgetKey? = null, val fold: Boolean = false, val atomic: Boolean = fold) : Decoration()
}

/** Identifies widget content: [type] picks the renderer, [id] the instance (a thread id, a fold). */
data class WidgetKey(val type: String, val id: String)

/** One decorated range. Point decorations have from == to. */
data class Ranged<T>(val from: Int, val to: Int, val value: T) {
    init { require(from in 0..to) { "invalid range $from..$to" } }
}

/**
 * An immutable, sorted set of ranged values that moves through edits.
 *
 * Sorted by (from, to). A range entirely inside deleted text disappears when mapped, and so does a
 * point strictly inside a deletion (a point exactly at its edge maps by its side); marks grow or
 * not at their edges according to their inclusive flags.
 */
class RangeSet<T> private constructor(internal val ranges: List<Ranged<T>>) : Iterable<Ranged<T>> {
    // The longest range's length: [between] never needs to look further back than this.
    private val maxLength = ranges.maxOfOrNull { it.to - it.from } ?: 0

    val size: Int get() = ranges.size
    val isEmpty: Boolean get() = ranges.isEmpty()
    override fun iterator(): Iterator<Ranged<T>> = ranges.iterator()

    /** Every range overlapping [from, to]; point ranges at the edges count. */
    fun between(from: Int, to: Int): List<Ranged<T>> {
        // An overlapping range ends at or after [from], so it starts at or after from - maxLength.
        var lo = 0; var hi = ranges.size
        val start = from - maxLength
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (ranges[mid].from < start) lo = mid + 1 else hi = mid }
        val out = ArrayList<Ranged<T>>()
        for (i in lo until ranges.size) {
            val r = ranges[i]
            if (r.from > to) break
            if (r.to >= from) out += r
        }
        return out
    }

    /**
     * The last range starting at or before [pos] (binary search, no scan): in a set of
     * NON-overlapping ranges (syntax spans), the only one that can contain [pos].
     */
    fun lastStartingAtOrBefore(pos: Int): Ranged<T>? {
        var lo = 0; var hi = ranges.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (ranges[mid].from <= pos) lo = mid + 1 else hi = mid }
        return if (lo == 0) null else ranges[lo - 1]
    }

    fun map(changes: ChangeSet): RangeSet<T> {
        if (changes.isEmpty) return this
        val out = ArrayList<Ranged<T>>(ranges.size)
        for (r in ranges) {
            val v = r.value
            if (r.from == r.to && changes.deletesAround(r.from)) continue
            val (startAssoc, endAssoc) = if (v is Decoration.Mark) {
                (if (v.inclusiveStart) -1 else 1) to (if (v.inclusiveEnd) 1 else -1)
            } else if (r.from == r.to) {
                val side = (v as? Decoration.InlineWidget)?.side ?: -1
                side to side
            } else 1 to -1
            val from = changes.mapPos(r.from, startAssoc)
            val to = changes.mapPos(r.to, endAssoc)
            // A range whose text was all deleted carries nothing any more.
            if (r.from < r.to && from >= to) continue
            out += Ranged(from, to, v)
        }
        return of(out)
    }

    fun update(add: List<Ranged<T>> = emptyList(), filter: ((Ranged<T>) -> Boolean)? = null): RangeSet<T> =
        of((if (filter == null) ranges else ranges.filter(filter)) + add)

    override fun equals(other: Any?) = other is RangeSet<*> && other.ranges == ranges
    override fun hashCode() = ranges.hashCode()
    override fun toString() = ranges.joinToString { "${it.from}-${it.to}:${it.value}" }

    companion object {
        fun <T> empty(): RangeSet<T> = RangeSet(emptyList())
        fun <T> of(ranges: List<Ranged<T>>): RangeSet<T> =
            RangeSet(ranges.sortedWith(compareBy({ it.from }, { it.to })))
    }
}

/** Decorations from every plugin; the surface draws all of them, in precedence order. */
val decorationsFacet: Facet<RangeSet<Decoration>, List<RangeSet<Decoration>>> = Facet.list("decorations")

/**
 * A plugin's mark in a gutter column, as DATA: put it at a line's start (zero length; a range marks
 * the line it starts on). The surface draws one column per distinct [column] id (`diff`, `lint`,
 * `comment`, `fold`), in precedence order, and its theme resolves [kind] to a shape and a colour
 * (`diff-add`, `diff-remove`, `diff-change`, `lint-error`, `lint-warning`, `comment`, `fold-open`,
 * `fold-closed`). [tooltip] is what a screen reader says for it.
 */
data class GutterMarker(val column: String, val kind: String, val tooltip: String? = null)

/** Gutter markers from every plugin, highest precedence first. */
val gutterMarkersFacet: Facet<RangeSet<GutterMarker>, List<RangeSet<GutterMarker>>> = Facet.list("gutterMarkers")

/**
 * A strip attached to the editor, at the top or the bottom, outside its scrolling area (the search
 * bar): DATA from a plugin; its content is the widget type `panel:<id>` in the surface's widget
 * registry. The editor's viewport shrinks by the panels' heights.
 */
data class Panel(val id: String, val top: Boolean)

/**
 * Every plugin's panels, in precedence order (top ones top to bottom, then the bottom ones). An
 * input may be null, no panel: a plugin shows one from its own state with
 * `panelsFacet.compute(FacetDep.field(f)) { st -> if (open) Panel(...) else null }` (CM6's
 * `showPanel.from(field, v => v.panel)`), no compartment needed.
 */
val panelsFacet: Facet<Panel?, List<Panel>> = Facet.define("panels") { it.filterNotNull() }

/**
 * Ranges that user edits treat as one unit (CM6's atomicRanges): an edit that reaches INTO one
 * (a Backspace at its end, a soft keyboard deleting a character of it) never takes a piece of it;
 * the editor asks its policy instead (the surface's atomic-delete handlers: unfold first, by
 * default, or delete it whole). Folds (`Replace(fold = true)`) are atomic without being listed here.
 * An edit whose selection covered the whole range deletes it as usual.
 */
val atomicRangesFacet: Facet<RangeSet<*>, List<RangeSet<*>>> = Facet.list("atomicRanges")
