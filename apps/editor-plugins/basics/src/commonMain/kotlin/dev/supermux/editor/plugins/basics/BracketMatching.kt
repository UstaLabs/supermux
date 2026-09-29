package dev.supermux.editor.plugins.basics

import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Facet
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.TokenContext
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.tokenContextFacet

/**
 * [BracketMatching]'s options (CM6's `bracketMatching` config): the bracket pairs, how far a scan
 * goes, and whether a bracket right AFTER the cursor counts too.
 */
data class BracketMatchingConfig(
    val brackets: String = "()[]{}",
    val maxScanDistance: Int = 10_000,
    val afterCursor: Boolean = true,
)

val bracketMatchingConfig: Facet<BracketMatchingConfig, BracketMatchingConfig> = Facet.first("bracketMatching", BracketMatchingConfig())

/** A bracket and its partner: [start] is the one at the cursor, [end] null when the document ended first. */
data class BracketMatch(val start: IntRange, val end: IntRange?, val matched: Boolean)

/**
 * Bracket matching (CM6's `bracketMatching`): with an empty cursor next to a bracket, the bracket and
 * its partner get [MATCHING] marks, or [NONMATCHING] when the partner is of another kind or the
 * document ends first. At each cursor, in CM6's order: a closer before it, an opener before it, then
 * (with [BracketMatchingConfig.afterCursor]) an opener after it, a closer after it.
 *
 * The scan counts only brackets of the SAME token context as the one at the cursor (code, string or
 * comment) when the language layer answers `tokenContextFacet` (editor-syntax does, from its spans),
 * so a `(` in a string never pairs with code; without it, every bracket character counts. A scan
 * stops after [BracketMatchingConfig.maxScanDistance] characters (then nothing is marked), so a big
 * file never stalls.
 */
object BracketMatching {
    const val MATCHING = "matching-bracket"
    const val NONMATCHING = "nonmatching-bracket"

    private val matching: Decoration = Decoration.Mark(setOf(MATCHING))
    private val nonmatching: Decoration = Decoration.Mark(setOf(NONMATCHING))

    val extension: Extension = decorationsFacet.compute(FacetDep.Doc, FacetDep.Selection, FacetDep.facet(tokenContextFacet), FacetDep.facet(bracketMatchingConfig)) { st ->
        val config = st.facet(bracketMatchingConfig)
        val out = ArrayList<Ranged<Decoration>>()
        for (r in st.selection.ranges) {
            if (!r.empty) continue
            val m = matchAt(st, r.head, config) ?: continue
            val deco = if (m.matched) matching else nonmatching
            out += Ranged(m.start.first, m.start.last + 1, deco)
            m.end?.let { out += Ranged(it.first, it.last + 1, deco) }
        }
        RangeSet.of(out)
    }

    fun extension(config: BracketMatchingConfig): Extension = extensionOf(extension, bracketMatchingConfig.of(config))

    /** The match for a cursor at [head] (CM6's four candidates, in its order), or null. */
    fun matchAt(state: EditorState, head: Int, config: BracketMatchingConfig = state.facet(bracketMatchingConfig)): BracketMatch? {
        val len = state.doc.length
        return matchBrackets(state, head, -1, config)
            ?: (if (head > 0) matchBrackets(state, head - 1, 1, config) else null)
            ?: (if (config.afterCursor) matchBrackets(state, head, 1, config) ?: (if (head < len) matchBrackets(state, head + 1, -1, config) else null) else null)
    }

    /**
     * CM6's `matchBrackets`: the bracket at [pos] scanning [dir] (dir < 0: the closer just BEFORE
     * [pos], scanning back; dir > 0: the opener AT [pos], scanning forward) and its partner.
     */
    fun matchBrackets(state: EditorState, pos: Int, dir: Int, config: BracketMatchingConfig = state.facet(bracketMatchingConfig)): BracketMatch? {
        val doc = state.doc
        val at = if (dir < 0) pos - 1 else pos
        if (at < 0 || at >= doc.length) return null
        val bracket = config.brackets.indexOf(doc.charAt(at))
        // An opener (even index) scans forward, a closer backward.
        if (bracket < 0 || (bracket % 2 == 0) != (dir > 0)) return null
        val startCtx = state.facet(tokenContextFacet)?.contextAt(state, at)
        return partner(state, at + dir, dir, bracket, startCtx, config)?.let { (end, matched) -> BracketMatch(at..at, end?.let { it..it }, matched) }
    }

    /**
     * The partner of bracket [bracket] (its index in [BracketMatchingConfig.brackets]), scanning
     * [dir] from [scanFrom] inclusive with the bracket itself already counted: (its position, whether
     * it is the same kind), (null, false) when the document's edge came first, or null past the scan
     * limit. A bracket whose token context differs from [startCtx] is skipped; where either is
     * unknown (null: no language layer, or outside what it has parsed) every bracket counts, as in a
     * plain scan.
     */
    internal fun partner(state: EditorState, scanFrom: Int, dir: Int, bracket: Int, startCtx: TokenContext?, config: BracketMatchingConfig): Pair<Int?, Boolean>? {
        val doc = state.doc
        val brackets = config.brackets
        val ctxProvider = state.facet(tokenContextFacet)
        val limit = config.maxScanDistance
        // The text to scan, at most maxScanDistance characters, as one string.
        val from = if (dir > 0) scanFrom else maxOf(0, scanFrom + 1 - limit)
        val to = if (dir > 0) minOf(doc.length, scanFrom + limit) else scanFrom + 1
        if (from > to) return if (dir > 0) null to false else null
        val text = doc.slice(from, to)
        var depth = 1
        var i = if (dir > 0) 0 else text.length - 1
        while (i in text.indices) {
            val found = brackets.indexOf(text[i])
            if (found >= 0 && (startCtx == null || ctxProvider == null || ctxProvider.contextAt(state, from + i).let { it == null || it == startCtx })) {
                if ((found % 2 == 0) == (dir > 0)) {
                    depth++
                } else if (depth == 1) {
                    return (from + i) to (found shr 1 == bracket shr 1)
                } else {
                    depth--
                }
            }
            i += dir
        }
        // Ran out: at the document's edge it has no partner; past the scan limit nobody knows.
        val reachedEdge = if (dir > 0) to == doc.length else from == 0
        return if (reachedEdge) null to false else null
    }
}
