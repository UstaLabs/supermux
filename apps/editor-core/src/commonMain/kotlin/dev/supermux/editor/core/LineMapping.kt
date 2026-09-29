package dev.supermux.editor.core

/**
 * Which lines of a side-by-side diff's two documents correspond, as DATA (a sandboxed diff plugin
 * can produce it): the changed runs ([Hunk]s, sorted, not overlapping); every line between them
 * pairs with one line of the other side, in order. A is the base, B the working copy. It says
 * nothing about heights: the surface aligns the paired rows from both sides' measured heights.
 */
data class LineMapping(val hunks: List<Hunk>) {
    /** A changed run: A's 0-based lines [aFrom, aTo) stand where B's [bFrom, bTo) are (either may be empty). */
    data class Hunk(val aFrom: Int, val aTo: Int, val bFrom: Int, val bTo: Int) {
        init { require(aFrom in 0..aTo && bFrom in 0..bTo) { "bad hunk $this" } }
    }

    init {
        for (i in 1 until hunks.size) require(hunks[i].aFrom >= hunks[i - 1].aTo && hunks[i].bFrom >= hunks[i - 1].bTo) { "hunks out of order at $i" }
    }

    companion object {
        /** Every line pairs with the same line (two views of one text). */
        val IDENTITY = LineMapping(emptyList())
    }
}

/**
 * A diff plugin's [LineMapping] for the view pair this state is part of (put it in the working
 * copy's state, B; the base's is read when B has none). The first provider wins.
 */
val lineMappingFacet: Facet<LineMapping, LineMapping?> = Facet.define("lineMapping") { it.firstOrNull() }
