package dev.supermux.editor.plugins.diff

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.Transaction

/**
 * How a diff view behaves.
 * - [context]: equal lines kept around every change; a longer unchanged run is folded behind
 *   "⋯ N unchanged lines" ([collapseUnchanged]). At least 1. GitHub and VS Code show 3; today's
 *   walkthrough shows 20 (M5 passes that).
 * - [expandStep]: lines one ↑ / ↓ press reveals (today's walkthrough: 20).
 * - [editable]: the working copy may be edited: the revert arrows are offered. False is the
 *   read-only walkthrough: the host shows it with `Editor(readOnly = true)` ([InlineDiffEditor] does).
 * - [syncLines]: a whole diff of at most this many lines (both sides) is computed at once, in the
 *   transaction; a bigger one runs in the background ([DiffJobs]: a worker thread, slices on the web)
 *   and the view shows the previous diff (or none yet) meanwhile.
 * - [recomputeDelayMs]: after an edit too big to re-diff at once, the background diff waits this long
 *   for the typing to settle.
 * - [offThread]: false runs background diffs on the view's scope (tests on a virtual clock).
 */
data class DiffConfig(
    val context: Int = 3,
    val expandStep: Int = 20,
    val collapseUnchanged: Boolean = true,
    val editable: Boolean = true,
    val options: DiffOptions = DiffOptions(),
    val syncLines: Int = 3_000,
    val recomputeDelayMs: Long = 150,
    val offThread: Boolean = true,
) {
    init { require(context >= 1 && expandStep >= 1) { "context and expandStep must be at least 1" } }
}

/** Which way an expander of a folded unchanged run reveals: ↑ its last lines, ↓ its first, or all. */
enum class Expand { UP, DOWN, ALL }

/**
 * A folded run of unchanged lines: the working copy's lines [bFrom, bTo), paired with the base's
 * [aFrom, aFrom + lines). [atStart] / [atEnd]: it begins the document / ends it (only one way to
 * expand then: ↑ at the start, ↓ at the end).
 */
data class CollapsedRun(val bFrom: Int, val bTo: Int, val aFrom: Int, val atStart: Boolean, val atEnd: Boolean) {
    val lines: Int get() = bTo - bFrom
    val aTo: Int get() = aFrom + lines
    internal val id: String get() = "$bFrom-$bTo"
}

/**
 * What the diff views show, as data, in the working copy's state (B): the base text, the [hunks]
 * aligned with B's current text, the unchanged runs the user expanded, and where the diff is in its
 * life ([ready]: a diff exists; [pending]: a background re-diff is due, the hunks around the last
 * big edit are one coarse run meanwhile).
 *
 * [slice] names the (base, working) pair: a new one (a host loading other texts) resets what was
 * expanded (today's walkthrough rule: expanded context resets only when the slice changes; threads
 * never touch it).
 */
class DiffModel internal constructor(
    val base: Rope,
    internal val baseLines: List<String>,
    val hunks: List<DiffHunk>,
    val ready: Boolean,
    val pending: Boolean,
    internal val revealed: List<IntRange>,
    val slice: Int,
    val config: DiffConfig,
    /** B's line count (the model follows B's document). */
    val lineCount: Int,
) {
    /** The folded unchanged runs, in order. */
    val collapsed: List<CollapsedRun> by lazy { computeCollapsed() }

    val lineMapping: dev.supermux.editor.core.LineMapping by lazy { hunks.toLineMapping() }

    internal fun copy(
        hunks: List<DiffHunk> = this.hunks,
        ready: Boolean = this.ready,
        pending: Boolean = this.pending,
        revealed: List<IntRange> = this.revealed,
        lineCount: Int = this.lineCount,
    ) = DiffModel(base, baseLines, hunks, ready, pending, revealed, slice, config, lineCount)

    private fun computeCollapsed(): List<CollapsedRun> {
        if (!config.collapseUnchanged || !ready || hunks.isEmpty()) return emptyList()
        val ctx = config.context
        val out = ArrayList<CollapsedRun>()
        fun gap(g0: Int, g1: Int, a0: Int) {
            val start = g0 == 0
            val end = g1 == lineCount
            val h0 = if (start) g0 else g0 + ctx
            val h1 = if (end) g1 else g1 - ctx
            if (h1 - h0 < MIN_COLLAPSE) return
            // What the user revealed is taken out; each piece left of at least two lines folds.
            var from = h0
            for (r in revealed) {
                if (r.last < from || r.first >= h1) continue
                if (r.first - from >= MIN_COLLAPSE) out += CollapsedRun(from, r.first, a0 + (from - g0), start && from == 0, false)
                from = maxOf(from, r.last + 1)
            }
            if (h1 - from >= MIN_COLLAPSE) out += CollapsedRun(from, h1, a0 + (from - g0), start && from == 0, end)
        }
        gap(0, hunks[0].bFrom, 0)
        for (i in 1 until hunks.size) gap(hunks[i - 1].bTo, hunks[i].bFrom, hunks[i - 1].aTo)
        val last = hunks.last()
        gap(last.bTo, lineCount, last.aTo)
        return out
    }

    /** The collapsed run [id] names (a widget's), or null. */
    internal fun collapsedRun(id: String): CollapsedRun? = collapsed.firstOrNull { it.id == id }

    /** The hunk whose markers sit on B's [line] (its first line; a deletion's line below it, or the last line). */
    fun hunkAtLine(line: Int): DiffHunk? = hunks.firstOrNull { anchorLine(it) == line }

    /** The hunk covering B's [line] (a deletion covers the line below it), or null. */
    fun hunkCovering(line: Int): DiffHunk? = hunks.firstOrNull { line in it.bFrom until maxOf(it.bTo, it.bFrom + 1) || anchorLine(it) == line }

    /** B's line a hunk's markers and widgets hang on: its first line; a deletion at the end: the last line. */
    fun anchorLine(h: DiffHunk): Int = minOf(h.bFrom, lineCount - 1)

    companion object {
        /** Fewer hidden lines than this are shown instead (a "⋯ 1 unchanged line" saves nothing). */
        const val MIN_COLLAPSE = 2

        internal fun create(base: String, doc: Rope, config: DiffConfig, slice: Int): DiffModel {
            val baseLines = LineDiff.lines(base)
            val m = DiffModel(Rope.of(base), baseLines, emptyList(), ready = false, pending = true, revealed = emptyList(), slice = slice, config = config, lineCount = doc.lineCount)
            if (baseLines.size + doc.lineCount > config.syncLines) return m
            return m.copy(hunks = LineDiff.diff(baseLines, LineDiff.lines(doc), config.options).hunks, ready = true, pending = false)
        }
    }
}

/** A diff computed off the transaction (the background job), for B's document [doc] of [slice]. */
internal class Computed(val doc: Rope, val slice: Int, val result: DiffResult)

/** A new base (a new slice): [working] too when the host replaces both (the doc change is in the same transaction). */
internal class SetBase(val base: String)

internal class ExpandRun(val bFrom: Int, val bTo: Int, val dir: Expand)

internal object DiffEffects {
    val setBase = StateEffectType<SetBase>("diff.setBase")
    val computed = StateEffectType<Computed>("diff.computed")
    val expand = StateEffectType<ExpandRun>("diff.expand")
    private var slices = 0
    fun nextSlice() = ++slices
}

/** The model through [tr]: re-diffed where B changed, then the effects. */
internal fun DiffModel.update(tr: Transaction): DiffModel {
    var m = this
    val doc = tr.state.doc
    if (tr.docChanged) {
        val (l0, l1) = touchedLines(tr.changes, tr.startState.doc)
        val delta = doc.lineCount - tr.startState.doc.lineCount
        val revealed = if (m.revealed.isEmpty()) m.revealed else m.revealed.mapNotNull { r ->
            when {
                r.last < l0 -> r
                r.first > l1 -> (r.first + delta)..(r.last + delta)
                else -> (r.first..(r.last + delta)).takeIf { !it.isEmpty() }
            }
        }
        m = if (m.ready) {
            val s = Splice.apply(m.baseLines, m.hunks, tr.startState.doc, doc, tr.changes, m.config.options)
            m.copy(hunks = s.hunks, pending = m.pending || !s.exact, revealed = revealed, lineCount = doc.lineCount)
        } else m.copy(revealed = revealed, lineCount = doc.lineCount)
    }
    for (e in tr.effects) {
        e.valueIf(DiffEffects.setBase)?.let { m = DiffModel.create(it.base, doc, m.config, DiffEffects.nextSlice()) }
        e.valueIf(DiffEffects.computed)?.let { c ->
            if (c.doc === doc && c.slice == m.slice) m = m.copy(hunks = c.result.hunks, ready = true, pending = false)
        }
        e.valueIf(DiffEffects.expand)?.let { x -> m = m.expand(x) }
    }
    return m
}

private fun DiffModel.expand(x: ExpandRun): DiffModel {
    val run = collapsed.firstOrNull { it.bFrom == x.bFrom && it.bTo == x.bTo } ?: return this
    val step = config.expandStep
    val shown = when (x.dir) {
        Expand.ALL -> run.bFrom until run.bTo
        Expand.UP -> maxOf(run.bFrom, run.bTo - step) until run.bTo
        Expand.DOWN -> run.bFrom until minOf(run.bTo, run.bFrom + step)
    }
    // A piece left under two lines would stay open anyway: reveal it too.
    val revealed = (revealed + listOf(shown)).sortedBy { it.first }
    val merged = ArrayList<IntRange>()
    for (r in revealed) {
        val last = merged.lastOrNull()
        if (last != null && r.first <= last.last + 1) merged[merged.size - 1] = last.first..maxOf(last.last, r.last) else merged += r
    }
    return copy(revealed = merged)
}

/** The first and last OLD lines [changes] touch. */
internal fun touchedLines(changes: ChangeSet, old: Rope): Pair<Int, Int> {
    var l0 = Int.MAX_VALUE
    var l1 = -1
    for (c in changes.iterChanges()) {
        l0 = minOf(l0, old.lineIndexAt(c.fromA))
        l1 = maxOf(l1, old.lineIndexAt(c.toA))
    }
    return l0 to l1
}

/** The start of B's line [line] and the end of its text, in [state]. */
internal fun lineEnd(doc: Rope, line: Int): Int = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) - 1 else doc.length

internal fun EditorState.lineOf(pos: Int): Int = doc.lineIndexAt(pos)
