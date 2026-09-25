package dev.supermux.editor.core

/** One selection range. [anchor] stays put while extending; [head] is where the cursor is drawn. */
data class SelectionRange(val anchor: Int, val head: Int = anchor) {
    val from: Int get() = minOf(anchor, head)
    val to: Int get() = maxOf(anchor, head)
    val empty: Boolean get() = anchor == head

    fun map(changes: ChangeSet, assoc: Int = -1): SelectionRange {
        val a = changes.mapPos(anchor, assoc)
        return if (empty) SelectionRange(a) else SelectionRange(a, changes.mapPos(head, assoc))
    }
}

/**
 * The selection: one or more ranges, sorted and non-overlapping, with one [main] range.
 *
 * Multiple ranges exist from day one (column selection needs them); a single cursor is just the
 * one-range case, so nothing downstream special-cases it.
 */
class EditorSelection private constructor(val ranges: List<SelectionRange>, val mainIndex: Int) {
    val main: SelectionRange get() = ranges[mainIndex]

    fun map(changes: ChangeSet, assoc: Int = -1): EditorSelection =
        create(ranges.map { it.map(changes, assoc) }, mainIndex)

    fun addRange(range: SelectionRange, makeMain: Boolean = true): EditorSelection =
        create(ranges + range, if (makeMain) ranges.size else mainIndex)

    override fun equals(other: Any?) = other is EditorSelection && other.ranges == ranges && other.mainIndex == mainIndex
    override fun hashCode() = ranges.hashCode() * 31 + mainIndex
    override fun toString() = ranges.mapIndexed { i, r -> (if (i == mainIndex) "*" else "") + "${r.anchor}-${r.head}" }.joinToString(",")

    companion object {
        fun cursor(pos: Int) = EditorSelection(listOf(SelectionRange(pos)), 0)
        fun single(anchor: Int, head: Int = anchor) = EditorSelection(listOf(SelectionRange(anchor, head)), 0)

        /** Sorts [ranges] by position and merges overlapping (or touching, when non-empty) ones. */
        fun create(ranges: List<SelectionRange>, mainIndex: Int = 0): EditorSelection {
            require(ranges.isNotEmpty()) { "a selection needs at least one range" }
            require(mainIndex in ranges.indices) { "main index $mainIndex out of range" }
            val main = ranges[mainIndex]
            val sorted = ranges.sortedWith(compareBy({ it.from }, { it.to }))
            val merged = ArrayList<SelectionRange>(sorted.size)
            var newMain = 0
            for (r in sorted) {
                val last = merged.lastOrNull()
                // A cursor touching a range joins it; two non-empty ranges that only touch stay apart.
                if (last != null && (if (r.empty) r.from <= last.to else r.from < last.to)) {
                    val from = minOf(last.from, r.from); val to = maxOf(last.to, r.to)
                    val forward = last.head >= last.anchor
                    merged[merged.size - 1] = if (forward) SelectionRange(from, to) else SelectionRange(to, from)
                    if (r == main) newMain = merged.size - 1
                } else {
                    merged += r
                    if (r == main) newMain = merged.size - 1
                }
            }
            return EditorSelection(merged, newMain)
        }
    }
}
