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
 * - [range]: a walkthrough STEP's lines (0-based, of the working copy): only they (± [context]) are
 *   shown, everything before and after folds behind an expander (↑ / ↓ reveal the lines next to the
 *   step, today's `diffContextBefore/After`), whether it changed or not; nothing folds inside. Inline
 *   views; the range is not mapped through edits (a read-only step).
 * - [plain]: no diff at all (today's `not_in_diff` step): the text as it is, no tints, no widgets,
 *   no markers, only [range]'s folding.
 * - [unfoldComments]: the lines of review threads and of the composer (± [context]) never fold (else
 *   a folded run says how many threads it hides). A [range] step never unfolds them: its folded runs
 *   count them.
 * - [syncLines]: a whole diff of at most this many lines (both sides) is computed at once, in the
 *   transaction; a bigger one runs in the background ([DiffJobs]: a worker thread, slices on the web)
 *   and the view shows the previous diff (or none yet) meanwhile. A base over [syncChars] is split
 *   into lines in the background too.
 * - [recomputeDelayMs]: after an edit too big to re-diff at once, the background diff waits this long
 *   for the typing to settle.
 * - [idleRediffMs]: this long after the last edit, a diff shaped by region re-diffs (minimal per
 *   region, not always overall) is redone whole in the background; 0: never.
 * - [offThread]: false runs background diffs on the view's scope (tests on a virtual clock).
 */
data class DiffConfig(
    val context: Int = 3,
    val expandStep: Int = 20,
    val collapseUnchanged: Boolean = true,
    val editable: Boolean = true,
    val range: IntRange? = null,
    val plain: Boolean = false,
    val unfoldComments: Boolean = true,
    val options: DiffOptions = DiffOptions(),
    val syncLines: Int = 3_000,
    val syncChars: Int = 300_000,
    val recomputeDelayMs: Long = 150,
    val idleRediffMs: Long = 500,
    val offThread: Boolean = true,
) {
    init { require(context >= 1 && expandStep >= 1) { "context and expandStep must be at least 1" } }
}

/** Which way an expander of a folded unchanged run reveals: ↑ its last lines, ↓ its first, or all. */
enum class Expand { UP, DOWN, ALL }

/**
 * A folded run: the working copy's lines [bFrom, bTo), paired with the base's [aFrom, aFrom + lines)
 * (unchanged runs; a [DiffConfig.range] step's outside runs may hold changes: [hasChanges]).
 * [atStart] / [atEnd]: it begins the document / ends it (only one way to expand then: ↑ at the
 * start, ↓ at the end). [comments]: review threads on its lines (hidden with them).
 */
data class CollapsedRun(
    val bFrom: Int,
    val bTo: Int,
    val aFrom: Int,
    val atStart: Boolean,
    val atEnd: Boolean,
    val comments: Int = 0,
    val hasChanges: Boolean = false,
) {
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
    val base: String,
    /** The base's lines: empty until split (a big base is split in the background; [ready] implies split). */
    internal val baseLines: List<String>,
    val hunks: List<DiffHunk>,
    val ready: Boolean,
    val pending: Boolean,
    internal val revealed: List<IntRange>,
    val slice: Int,
    val config: DiffConfig,
    /** B's line count (the model follows B's document). */
    val lineCount: Int,
    /** The hunks were shaped by region re-diffs since the last whole diff (the idle re-diff redoes it). */
    internal val spliced: Boolean = false,
) {
    /** The folded runs with no review lines to keep open (see [collapsedFor]). */
    val collapsed: List<CollapsedRun> get() = collapsedFor(Pins())

    private var lastPinned: Pins? = null
    private var lastRuns: List<CollapsedRun> = emptyList()

    /**
     * The folded runs, in order, given the review's [pinned] lines: a thread's is kept open ± context
     * when [DiffConfig.unfoldComments] (never in a [DiffConfig.range] step: counted in
     * [CollapsedRun.comments] instead); the open composer's always is, in a step too.
     */
    internal fun collapsedFor(pinned: Pins): List<CollapsedRun> {
        if (lastPinned == pinned) return lastRuns
        val r = computeCollapsed(pinned)
        lastPinned = pinned; lastRuns = r
        return r
    }

    val lineMapping: dev.supermux.editor.core.LineMapping by lazy { hunks.toLineMapping() }

    internal fun copy(
        hunks: List<DiffHunk> = this.hunks,
        ready: Boolean = this.ready,
        pending: Boolean = this.pending,
        revealed: List<IntRange> = this.revealed,
        lineCount: Int = this.lineCount,
        spliced: Boolean = this.spliced,
        baseLines: List<String> = this.baseLines,
    ) = DiffModel(base, baseLines, hunks, ready, pending, revealed, slice, config, lineCount, spliced)

    private fun computeCollapsed(pinned: Pins): List<CollapsedRun> {
        if (!config.collapseUnchanged || !ready) return emptyList()
        val ctx = config.context
        val range = config.range
        val out = ArrayList<CollapsedRun>()
        val sortedPins = pinned.threads.sorted()
        fun count(f: Int, t: Int) = sortedPins.count { it in f until t }
        fun changes(f: Int, t: Int) = hunks.any { it.bFrom < t && maxOf(it.bTo, it.bFrom + 1) > f }
        // What stays open inside a gap: what the user revealed, and (unless a step) the review's lines ± context.
        val open = ArrayList<IntRange>(revealed)
        if (range == null && config.unfoldComments) for (p in sortedPins) open += maxOf(0, p - ctx)..(p + ctx)
        // The composer (someone is writing there) never folds, in a walkthrough step too.
        for (p in pinned.composer) open += maxOf(0, p - ctx)..(p + ctx)
        open.sortBy { it.first }
        fun gap(g0: Int, g1: Int, a0: Int, keepStart: Boolean, keepEnd: Boolean) {
            val h0 = if (!keepStart) g0 else g0 + ctx
            val h1 = if (!keepEnd) g1 else g1 - ctx
            if (h1 - h0 < MIN_COLLAPSE) return
            var from = h0
            fun piece(f: Int, t: Int, end: Boolean) {
                if (t - f >= MIN_COLLAPSE) out += CollapsedRun(f, t, a0 + (f - g0), f == 0, end, count(f, t), range != null && changes(f, t))
            }
            for (r in open) {
                if (r.last < from || r.first >= h1) continue
                piece(from, r.first, false)
                from = maxOf(from, r.last + 1)
            }
            piece(from, h1, h1 == lineCount)
        }
        if (range != null) {
            // A walkthrough step: only its lines (± context) are shown.
            val lo = (range.first - ctx).coerceIn(0, lineCount)
            val hi = (range.last + 1 + ctx).coerceIn(lo, lineCount)
            gap(0, lo, Splice.aLineOf(hunks, 0), keepStart = false, keepEnd = false)
            gap(hi, lineCount, Splice.aLineOf(hunks, hi), keepStart = false, keepEnd = false)
            return out
        }
        if (hunks.isEmpty()) return emptyList()
        gap(0, hunks[0].bFrom, 0, keepStart = false, keepEnd = true)
        for (i in 1 until hunks.size) gap(hunks[i - 1].bTo, hunks[i].bFrom, hunks[i - 1].aTo, keepStart = true, keepEnd = true)
        val last = hunks.last()
        gap(last.bTo, lineCount, last.aTo, keepStart = true, keepEnd = false)
        return out
    }

    /** The collapsed run [id] names (a widget's), or null. */
    internal fun collapsedRun(id: String, pinned: Pins): CollapsedRun? = collapsedFor(pinned).firstOrNull { it.id == id }

    /** The hunk whose markers sit on B's [line] (its first line; a deletion's line below it, or the last line). */
    fun hunkAtLine(line: Int): DiffHunk? = hunks.firstOrNull { anchorLine(it) == line }

    /** The hunk covering B's [line] (a deletion covers the line below it), or null. */
    fun hunkCovering(line: Int): DiffHunk? = hunks.firstOrNull { line in it.bFrom until maxOf(it.bTo, it.bFrom + 1) || anchorLine(it) == line }

    /** B's line a hunk's markers and widgets hang on: its first line; a deletion at the end: the last line. */
    fun anchorLine(h: DiffHunk): Int = minOf(h.bFrom, lineCount - 1)

    companion object {
        /** Fewer hidden lines than this are shown instead (a "⋯ 1 unchanged line" saves nothing). */
        const val MIN_COLLAPSE = 3

        internal fun create(base: String, doc: Rope, config: DiffConfig, slice: Int): DiffModel {
            val lines = doc.lineCount
            if (config.plain) return DiffModel(base, emptyList(), emptyList(), ready = true, pending = false, revealed = emptyList(), slice = slice, config = config, lineCount = lines)
            val small = base.length <= config.syncChars && doc.length <= config.syncChars
            val baseLines = if (small) LineDiff.lines(base) else emptyList()
            val m = DiffModel(base, baseLines, emptyList(), ready = false, pending = true, revealed = emptyList(), slice = slice, config = config, lineCount = lines)
            if (!small || baseLines.size + lines > config.syncLines) return m
            return m.copy(hunks = LineDiff.diff(baseLines, LineDiff.lines(doc), config.options).hunks, ready = true, pending = false)
        }
    }
}

/** A diff computed off the transaction (the background job), for B's document [doc] of [slice]; [baseLines] split there too. */
internal class Computed(val doc: Rope, val slice: Int, val result: DiffResult, val baseLines: List<String>)

/** A new base (a new slice): [working] too when the host replaces both (the doc change is in the same transaction). */
internal class SetBase(val base: String, val config: DiffConfig? = null)

/** Expand the run [bFrom, bTo) as the widget saw it (with the review's [pinned] lines then). */
internal class ExpandRun(val bFrom: Int, val bTo: Int, val dir: Expand, val pinned: Pins = Pins())

/** The review's lines the diff keeps open: the threads', and the open composer's. */
internal data class Pins(val threads: List<Int> = emptyList(), val composer: List<Int> = emptyList())

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
        m = if (m.ready && !m.config.plain) {
            val s = Splice.apply(m.baseLines, m.hunks, tr.startState.doc, doc, tr.changes, m.config.options)
            m.copy(hunks = s.hunks, pending = m.pending || !s.exact, revealed = revealed, lineCount = doc.lineCount, spliced = true)
        } else m.copy(revealed = revealed, lineCount = doc.lineCount)
    }
    for (e in tr.effects) {
        e.valueIf(DiffEffects.setBase)?.let { m = DiffModel.create(it.base, doc, it.config ?: m.config, DiffEffects.nextSlice()) }
        e.valueIf(DiffEffects.computed)?.let { c ->
            if (c.doc === doc && c.slice == m.slice) m = m.copy(hunks = c.result.hunks, ready = true, pending = false, spliced = false, baseLines = c.baseLines)
        }
        e.valueIf(DiffEffects.expand)?.let { x -> m = m.expand(x) }
    }
    return m
}

private fun DiffModel.expand(x: ExpandRun): DiffModel {
    val run = collapsedFor(x.pinned).firstOrNull { it.bFrom == x.bFrom && it.bTo == x.bTo } ?: return this
    val step = config.expandStep
    val shown = when (x.dir) {
        Expand.ALL -> run.bFrom until run.bTo
        Expand.UP -> maxOf(run.bFrom, run.bTo - step) until run.bTo
        Expand.DOWN -> run.bFrom until minOf(run.bTo, run.bFrom + step)
    }
    // Pieces left under MIN_COLLAPSE lines stay open (computeCollapsed).
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
