package dev.supermux.editor.plugins.diff

import dev.supermux.editor.compose.EditorViewport
import dev.supermux.editor.compose.GutterClickHandler
import dev.supermux.editor.compose.RevealHandler
import dev.supermux.editor.compose.ViewPlugin
import dev.supermux.editor.compose.ViewPluginHost
import dev.supermux.editor.compose.ViewPluginInstance
import dev.supermux.editor.compose.WidgetClickHandler
import dev.supermux.editor.compose.gutterClickFacet
import dev.supermux.editor.compose.revealFacet
import dev.supermux.editor.compose.viewPluginsFacet
import dev.supermux.editor.compose.widgetClickFacet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Facet
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.GutterMarker
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.LineMapping
import dev.supermux.editor.core.lineMappingFacet
import dev.supermux.editor.core.NamedCommand
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.commandsFacet
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.gutterMarkersFacet
import dev.supermux.editor.core.keymapFacet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a diff view tells its host (data in, events out; M5 maps `:ui`'s walkthrough and diff
 * callbacks onto it). Every method has a default: implement what the host needs. Lines are 0-based
 * lines of the working copy (the host's 1-based line − 1).
 */
interface DiffHost {
    /** The composer's text was submitted for [line] (the composer closes; the host posts the comment). */
    fun onCommentSubmit(line: Int, text: String) {}

    /** A reply to thread [threadId]. */
    fun onReply(threadId: String, text: String) {}

    /** The user resolved thread [threadId] (the host marks it and pushes the threads again). */
    fun onResolve(threadId: String) {}

    /**
     * The user opened the composer on [line] (a gutter tap, the comment command; today's
     * `onDiffLineClick`). A host that kept a draft for that line hands it back with
     * `Review.setComposer(ReviewComposer(line, draft))` (taken while the new composer is still empty).
     */
    fun onComposerOpen(line: Int) {}

    /** The composer's draft for [line] changed (the host keeps it, to restore it with a `ReviewComposer`). */
    fun onComposerDraft(line: Int, text: String) {}

    /** The composer was closed without submitting (today's `onComposerState(0, "")`: the host drops the draft). */
    fun onComposerClosed() {}

    /** A hunk was reverted in the working copy (after the edit). */
    fun onRevert(hunk: DiffHunk) {}
}

/** The host of the diff views of this state (the first given). */
val diffHostFacet: Facet<DiffHost, DiffHost?> = Facet.define("diffHost") { it.firstOrNull() }

/**
 * The inline diff (one column, today's walkthrough renderer): the working copy (this state's
 * document) against [base]. Changed and added lines are tinted, the characters that changed inside
 * them marked, the deleted lines shown as a read-only block widget above their place (`diff:deleted`),
 * gutter bars (`diff` column), unchanged runs folded behind "⋯ N unchanged lines" (`diff:collapsed`,
 * with ↑ / ↓ / all), revert arrows (`revert` column, [DiffConfig.editable] only) and next/previous
 * hunk (F7 / Shift-F7, and VS Code's Alt-F5 / Shift-Alt-F5). The widgets' content: [Diff.registerWidgets].
 */
fun inlineDiff(base: String, config: DiffConfig = DiffConfig(), host: DiffHost? = null): Extension =
    Diff.extension(base, config, host, inline = true)

/**
 * The diff plugin's state, commands and queries (both modes). The inline mode is [inlineDiff]; the
 * side-by-side mode is [DiffPair].
 */
object Diff {
    /** Widget types: the folded unchanged run's expander, and (inline) a hunk's deleted lines. */
    const val COLLAPSED = "diff:collapsed"
    const val DELETED = "diff:deleted"

    /** The gutter columns: the change bars, the revert arrows. */
    const val DIFF_COLUMN = "diff"
    const val REVERT_COLUMN = "revert"

    /** The userEvent of a revert (an undo step of its own; read-only views drop it). */
    const val REVERT_EVENT = "edit.revert"

    // ---------------------------------------------------------------- the working copy (B) --

    internal fun extension(base: String, config: DiffConfig, host: DiffHost?, inline: Boolean): Extension {
        val f = StateField("diff.working", { st -> DiffModel.create(base, st.doc, config, DiffEffects.nextSlice()) }, { m: DiffModel, tr -> m.update(tr) })
        return extensionOf(
            workingField.of(f),
            f,
            EditorViewport.extension,
            decorationsFacet.compute(FacetDep.field(f), FacetDep.field(EditorViewport.field)) { st -> visibleDecorations(st, st.field(f), Side.B, inline) },
            decorationsFacet.compute(FacetDep.field(f)) { st -> blockDecorations(st.doc, st.field(f), Side.B, inline) },
            gutterMarkersFacet.compute(FacetDep.field(f), FacetDep.field(EditorViewport.field)) { st -> markers(st, st.field(f), Side.B, inline) },
            // Side by side: which lines pair, for the linked views (none yet: line for line).
            if (inline) extensionOf() else lineMappingFacet.compute(FacetDep.field(f)) { st -> st.field(f).let { m -> if (m.ready) m.lineMapping else LineMapping.IDENTITY } },
            if (host != null) diffHostFacet.of(host) else extensionOf(),
            common,
            viewPluginsFacet.of(worker),
        )
    }

    /** Which field holds the model in a state (the working side's; one per state). */
    internal val workingField: Facet<StateField<DiffModel>, StateField<DiffModel>?> = Facet.define("diff.workingField") { it.firstOrNull() }

    /** The model of the working copy in [state] (B), or null when this state shows no diff. */
    fun model(state: EditorState): DiffModel? = state.facet(workingField)?.let { state.fieldOrNull(it) }

    /** The hunks shown now (aligned with the working copy's text). */
    fun hunks(state: EditorState): List<DiffHunk> = sideModel(state)?.hunks.orEmpty()

    // ---------------------------------------------------------------- effects --

    /** A new base for the working copy in [target] (a new slice: what was expanded is folded again). */
    fun setBase(target: CommandTarget, base: String) {
        target.dispatch(TransactionSpec(effects = listOf(DiffEffects.setBase.of(SetBase(base)))))
    }

    /**
     * Load a new pair: [base] and the working text [working] (replacing the document, userEvent
     * `disk`: never an undo step), one transaction. A new slice.
     */
    fun load(target: CommandTarget, base: String, working: String) {
        val doc = target.state.doc
        val same = doc.length == working.length && doc.toString() == working
        target.dispatch(TransactionSpec(
            changes = if (same) emptyList() else listOf(ChangeSpec(0, doc.length, working)),
            selection = if (same) null else EditorSelection.cursor(0),
            effects = listOf(DiffEffects.setBase.of(SetBase(base))),
            userEvent = if (same) null else "disk",
        ))
    }

    /** Reveal (part of) the folded unchanged run [run]: ↑ its last [DiffConfig.expandStep] lines, ↓ its first, or all. */
    fun expand(target: CommandTarget, run: CollapsedRun, dir: Expand) {
        val spec = TransactionSpec(effects = listOf(DiffEffects.expand.of(ExpandRun(run.bFrom, run.bTo, dir))))
        val forward = target.state.facet(forwardFacet)
        if (forward != null) forward(spec) else target.dispatch(spec)
    }

    /** Where A's (the base's) expand requests go: the pair's working view. */
    internal val forwardFacet: Facet<(TransactionSpec) -> Unit, ((TransactionSpec) -> Unit)?> = Facet.define("diff.forward") { it.firstOrNull() }

    // ---------------------------------------------------------------- revert --

    /** Put [h]'s base lines back in the working copy in [target], ONE transaction (`edit.revert`: one undo step). */
    fun revert(target: CommandTarget, h: DiffHunk): Boolean {
        val state = target.state
        val m = model(state) ?: return false
        if (!m.config.editable) return false
        val doc = state.doc
        val baseText = m.baseLines.subList(h.aFrom, h.aTo).joinToString("\n")
        val change = when {
            h.bFrom < h.bTo && h.aFrom < h.aTo -> ChangeSpec(doc.lineStart(h.bFrom), lineEnd(doc, h.bTo - 1), baseText)
            h.bFrom == h.bTo -> if (h.bFrom < doc.lineCount) ChangeSpec(doc.lineStart(h.bFrom), doc.lineStart(h.bFrom), baseText + "\n")
                else ChangeSpec(doc.length, doc.length, "\n" + baseText)
            h.bTo < doc.lineCount -> ChangeSpec(doc.lineStart(h.bFrom), doc.lineStart(h.bTo))
            h.bFrom > 0 -> ChangeSpec(lineEnd(doc, h.bFrom - 1), doc.length)
            else -> ChangeSpec(0, doc.length)
        }
        val at = minOf(change.from, doc.length)
        target.dispatch(TransactionSpec(changes = listOf(change), selection = EditorSelection.cursor(at), userEvent = REVERT_EVENT))
        state.facet(diffHostFacet)?.onRevert(h)
        return true
    }

    /** Revert the hunk at the main cursor's line. */
    val revertHunk: Command = Command { t ->
        val m = model(t.state) ?: return@Command false
        val h = m.hunkCovering(t.state.lineOf(t.state.selection.main.head)) ?: return@Command false
        revert(t, h)
    }

    // ---------------------------------------------------------------- next / previous --

    /** The main cursor to the start of the next hunk (wrapping around), scrolled into view. */
    val nextHunk: Command = Command { t -> goToHunk(t, 1) }
    val prevHunk: Command = Command { t -> goToHunk(t, -1) }

    private fun goToHunk(t: CommandTarget, dir: Int): Boolean {
        val st = t.state
        val side = sideModel(st) ?: return false
        if (side.hunks.isEmpty()) return false
        val a = st.facet(sideFacet) == Side.A
        fun line(h: DiffHunk) = if (a) minOf(h.aFrom, st.doc.lineCount - 1) else side.anchorLine(h)
        val cur = st.lineOf(st.selection.main.head)
        val h = if (dir > 0) side.hunks.firstOrNull { line(it) > cur } ?: side.hunks.first()
            else side.hunks.lastOrNull { line(it) < cur } ?: side.hunks.last()
        val pos = st.doc.lineStart(line(h))
        t.dispatch(TransactionSpec(selection = EditorSelection.cursor(pos), scrollIntoView = true, userEvent = "select.diff"))
        return true
    }

    /** F7 / Shift-F7 (VS Code's diff review) and Alt-F5 / Shift-Alt-F5 (VS Code's "next change"). */
    val keymap: List<KeyBinding> = listOf(
        KeyBinding("F7", nextHunk), KeyBinding("Shift-F7", prevHunk),
        KeyBinding("Alt-F5", nextHunk), KeyBinding("Shift-Alt-F5", prevHunk),
    )

    val commands: List<NamedCommand> = listOf(
        NamedCommand("diff.nextHunk", "Next change", nextHunk),
        NamedCommand("diff.prevHunk", "Previous change", prevHunk),
        NamedCommand("diff.revertHunk", "Revert this change", revertHunk),
    )

    // ---------------------------------------------------------------- shared by both sides --

    internal enum class Side { A, B }

    /** Which side this state is: B (the working copy, the default) or A (a pair's base). */
    internal val sideFacet: Facet<Side, Side> = Facet.first("diff.side", Side.B)

    /** A's copy of the working side's model (the pair pushes it). */
    internal val baseField: StateField<DiffModel?> = StateField(
        "diff.base",
        { null },
        { m, tr ->
            var out = m
            for (e in tr.effects) e.valueIf(pushModel)?.let { out = it }
            out
        },
    )
    internal val pushModel = dev.supermux.editor.core.StateEffectType<DiffModel>("diff.pushModel")

    /** The model this side shows (B: its own; A: the one its pair pushed). */
    internal fun sideModel(state: EditorState): DiffModel? = model(state) ?: state.fieldOrNull(baseField)

    /** Gutter, chip, reveal: the same on both sides. */
    private val common: Extension = extensionOf(
        keymapFacet.of(keymap),
        commandsFacet.of(commands),
        gutterClickFacet.of(GutterClickHandler { t, column, line, _ ->
            if (column != REVERT_COLUMN) return@GutterClickHandler false
            val m = model(t.state) ?: return@GutterClickHandler false
            val h = m.hunkAtLine(line) ?: return@GutterClickHandler false
            revert(t, h)
            true
        }),
        widgetClickFacet.of(WidgetClickHandler { t, key, _, _ ->
            if (key.type != COLLAPSED) return@WidgetClickHandler false
            val run = sideModel(t.state)?.collapsedRun(key.id) ?: return@WidgetClickHandler false
            expand(t, run, Expand.ALL)
            true
        }),
        // A search match or a definition inside a folded run: open that run.
        revealFacet.of(RevealHandler { t, from, to ->
            val st = t.state
            val m = sideModel(st) ?: return@RevealHandler false
            val a = st.facet(sideFacet) == Side.A
            val l0 = st.lineOf(from)
            val l1 = st.lineOf(to)
            val run = m.collapsed.firstOrNull { r -> val f = if (a) r.aFrom else r.bFrom; l1 >= f && l0 < f + r.lines } ?: return@RevealHandler false
            expand(t, run, Expand.ALL)
            true
        }),
    )

    internal val baseSide: Extension = extensionOf(
        baseField,
        sideFacet.of(Side.A),
        EditorViewport.extension,
        decorationsFacet.compute(FacetDep.field(baseField), FacetDep.field(EditorViewport.field)) { st -> st.field(baseField)?.let { visibleDecorations(st, it, Side.A, false) } ?: RangeSet.empty() },
        decorationsFacet.compute(FacetDep.field(baseField)) { st -> st.field(baseField)?.let { blockDecorations(st.doc, it, Side.A, false) } ?: RangeSet.empty() },
        gutterMarkersFacet.compute(FacetDep.field(baseField), FacetDep.field(EditorViewport.field)) { st -> st.field(baseField)?.let { markers(st, it, Side.A, false) } ?: RangeSet.empty() },
        common,
    )

    // ---------------------------------------------------------------- decorations --

    private val LINE_ADD = Decoration.LineStyle(setOf("diff-add"))
    private val LINE_CHANGE = Decoration.LineStyle(setOf("diff-change"))
    private val LINE_REMOVE = Decoration.LineStyle(setOf("diff-remove"))
    private val MARK_ADD = Decoration.Mark(setOf("diff-add-text"))
    private val MARK_REMOVE = Decoration.Mark(setOf("diff-remove-text"))

    private fun from(h: DiffHunk, side: Side) = if (side == Side.A) h.aFrom else h.bFrom
    private fun to(h: DiffHunk, side: Side) = if (side == Side.A) h.aTo else h.bTo

    /** The hunks with a line in lines [l0, l1] of [side]. */
    private fun hunksIn(m: DiffModel, side: Side, l0: Int, l1: Int): List<DiffHunk> {
        val hs = m.hunks
        var i = Splice.lowerBound(hs) { to(it, side) >= l0 }
        val out = ArrayList<DiffHunk>()
        while (i < hs.size && from(hs[i], side) <= l1) { out += hs[i]; i++ }
        return out
    }

    private fun windowLines(st: EditorState): Pair<Int, Int> {
        val r = EditorViewport.rangeOf(st)
        val doc = st.doc
        return doc.lineIndexAt(minOf(r.first, doc.length)) to doc.lineIndexAt(minOf(maxOf(r.first, r.last), doc.length))
    }

    /** Line tints and character marks for the lines around the viewport. */
    internal fun visibleDecorations(st: EditorState, m: DiffModel, side: Side, inline: Boolean): RangeSet<Decoration> {
        if (!m.ready) return RangeSet.empty()
        val doc = st.doc
        val (l0, l1) = windowLines(st)
        val out = ArrayList<Ranged<Decoration>>()
        for (h in hunksIn(m, side, l0, l1)) {
            val f = from(h, side)
            val t = to(h, side)
            if (f == t || t > doc.lineCount) continue
            val line = when { side == Side.A -> LINE_REMOVE; h.isInsert -> LINE_ADD; else -> LINE_CHANGE }
            for (l in maxOf(f, l0)..minOf(t - 1, l1)) { val s = doc.lineStart(l); out += Ranged(s, s, line) }
            val chars = h.chars ?: continue
            val start = doc.lineStart(f)
            val end = lineEnd(doc, t - 1)
            for (c in chars) {
                val cf = start + if (side == Side.A) c.aFrom else c.bFrom
                val ct = start + if (side == Side.A) c.aTo else c.bTo
                if (ct > cf && ct <= end) out += Ranged(cf, ct, if (side == Side.A) MARK_REMOVE else MARK_ADD)
            }
        }
        return RangeSet.of(out)
    }

    /** The folded unchanged runs (whole document: they change its height) and, inline, the deleted lines' widgets. */
    internal fun blockDecorations(doc: Rope, m: DiffModel, side: Side, inline: Boolean): RangeSet<Decoration> {
        if (!m.ready) return RangeSet.empty()
        val out = ArrayList<Ranged<Decoration>>()
        for (r in m.collapsed) {
            val f = if (side == Side.A) r.aFrom else r.bFrom
            val t = f + r.lines
            if (t > doc.lineCount) continue
            out += Ranged(doc.lineStart(f), lineEnd(doc, t - 1), Decoration.Replace(WidgetKey(COLLAPSED, r.id), atomic = true))
        }
        if (inline) for (h in m.hunks) {
            if (h.aFrom == h.aTo) continue
            val below = h.bFrom >= doc.lineCount
            val line = minOf(h.bFrom, doc.lineCount - 1)
            val pos = doc.lineStart(line)
            out += Ranged(pos, pos, Decoration.BlockWidget(WidgetKey(DELETED, "d${h.aFrom}"), above = !below, estimatedHeightLines = minOf(h.aTo - h.aFrom, DeletedLines.MAX_SHOWN + 1).toFloat()))
        }
        return RangeSet.of(out)
    }

    /** Gutter bars (and on the working side, when editable, revert arrows) for the lines around the viewport. */
    internal fun markers(st: EditorState, m: DiffModel, side: Side, inline: Boolean): RangeSet<GutterMarker> {
        if (!m.ready) return RangeSet.empty()
        val doc = st.doc
        val (l0, l1) = windowLines(st)
        val out = ArrayList<Ranged<GutterMarker>>()
        val revert = side == Side.B && m.config.editable
        for (h in hunksIn(m, side, l0 - 1, l1 + 1)) {
            val f = from(h, side)
            val t = to(h, side)
            if (f == t) {
                // A deletion (B) or an insertion (A): a bar on the line after the gap, inline only on B.
                if (side == Side.B && inline) {
                    val line = m.anchorLine(h)
                    val s = doc.lineStart(line)
                    val n = h.aTo - h.aFrom
                    out += Ranged(s, s, GutterMarker(DIFF_COLUMN, "diff-remove", "$n ${if (n == 1) "line" else "lines"} deleted ${if (h.bFrom >= doc.lineCount) "below" else "above"}"))
                }
            } else if (t <= doc.lineCount) {
                val kind = when { side == Side.A -> "diff-remove"; h.isInsert -> "diff-add"; else -> "diff-change" }
                val tip = when { side == Side.A -> "removed line"; h.isInsert -> "added line"; else -> "changed line" }
                for (l in maxOf(f, l0)..minOf(t - 1, l1)) { val s = doc.lineStart(l); out += Ranged(s, s, GutterMarker(DIFF_COLUMN, kind, tip)) }
            }
            if (revert) {
                val s = doc.lineStart(m.anchorLine(h))
                out += Ranged(s, s, GutterMarker(REVERT_COLUMN, "diff-revert", "Revert this change"))
            }
        }
        return RangeSet.of(out)
    }

    // ---------------------------------------------------------------- the background re-diff --

    /** The view plugin that runs a due whole diff ([DiffModel.pending]) off the transaction. */
    private val worker = ViewPlugin { host -> Worker(host) }

    private class Worker(private val host: ViewPluginHost) : ViewPluginInstance {
        private var job: Job? = null
        private var jobDoc: Rope? = null
        private var jobSlice = -1

        init { check(host.target.state) }

        override fun update(tr: Transaction) = check(tr.state)

        private fun check(state: EditorState) {
            val m = model(state) ?: return
            if (!m.pending) { if (job?.isActive == true && jobSlice != m.slice) job?.cancel(); return }
            if (job?.isActive == true && jobDoc === state.doc && jobSlice == m.slice) return
            job?.cancel()
            val doc = state.doc
            val slice = m.slice
            val first = !m.ready
            jobDoc = doc
            jobSlice = slice
            job = host.scope.launch {
                if (!first) delay(m.config.recomputeDelayMs)
                val r = DiffJobs.diff(m.baseLines, LineDiff.lines(doc), m.config.options, offThread = m.config.offThread)
                val now = host.target.state
                if (now.doc === doc && model(now)?.slice == slice) host.target.dispatch(TransactionSpec(effects = listOf(DiffEffects.computed.of(Computed(doc, slice, r)))))
            }
        }

        override fun destroy() { job?.cancel() }
    }

    /** True when no background diff is due (tests, a host's "computing…" line). */
    fun idle(state: EditorState): Boolean = model(state)?.pending != true
}

/** The deleted lines' widget's limits. */
internal object DeletedLines {
    /** Lines shown at once; more behind "show N more". */
    const val MAX_SHOWN = 400
}
