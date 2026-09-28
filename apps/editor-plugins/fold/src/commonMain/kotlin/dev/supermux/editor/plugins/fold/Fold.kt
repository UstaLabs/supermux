package dev.supermux.editor.plugins.fold

import dev.supermux.editor.compose.AtomicDelete
import dev.supermux.editor.compose.AtomicDeleteHandler
import dev.supermux.editor.compose.EditorViewport
import dev.supermux.editor.compose.GutterClickHandler
import dev.supermux.editor.compose.RevealHandler
import dev.supermux.editor.compose.WidgetClickHandler
import dev.supermux.editor.compose.atomicDeleteFacet
import dev.supermux.editor.compose.gutterClickFacet
import dev.supermux.editor.compose.revealFacet
import dev.supermux.editor.compose.tabSizeFacet
import dev.supermux.editor.compose.widgetClickFacet
import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Facet
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.FoldRange
import dev.supermux.editor.core.GutterMarker
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.NamedCommand
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.StateEffect
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.commandsFacet
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.foldServiceFacet
import dev.supermux.editor.core.gutterMarkersFacet
import dev.supermux.editor.core.keymapFacet

/**
 * The fold plugin's options. [deleteFoldWhole]: a Backspace at a fold's end (a Delete at its start,
 * a soft keyboard deleting its placeholder) deletes the whole folded text (CM6's policy; undo brings
 * it back). Off by default: the editor's own policy then unfolds first (JetBrains), and the next
 * Backspace deletes normally. Ahmet has not chosen yet (2026-09-28).
 */
data class FoldConfig(val deleteFoldWhole: Boolean = false)

/**
 * Folding, CM6's `@codemirror/language` folds in Kotlin:
 *
 * - **Ranges** are line-based: a line folds from its end to where its block ends. They come from
 *   editor-core's `foldServiceFacet` (editor-syntax answers it from tree-sitter's `folds.scm` when
 *   highlighting is on), and from INDENTATION for a line no service answers for: the lines after it
 *   that are indented deeper (blank lines between them included).
 * - **State**: [field], a `RangeSet` of `Decoration.Replace(WidgetKey("fold", …), fold = true)`,
 *   mapped through every edit; [foldEffect] / [unfoldEffect]. An edit that touches a fold's hidden
 *   text (only a remote, programmatic or undo edit can: local input is policed by the surface)
 *   unfolds it, as CM6 does; a deletion that covers it removes it.
 * - **Gutter**: `fold-open` / `fold-closed` markers in the `fold` column for the lines in the
 *   viewport (editor-compose's [EditorViewport]); a click toggles.
 * - **Placeholder**: the surface draws the fold's widget as the "⋯" chip (the `fold` type has no
 *   registered content); a click on it unfolds.
 * - **Reveal**: a selection scrolled into view inside a fold (a search match, go-to-definition)
 *   unfolds it (editor-compose's `revealFacet`); a user edit reaching into a fold unfolds it too,
 *   unless [FoldConfig.deleteFoldWhole].
 * - **Commands and keys** (CM6's `foldKeymap`): [foldCode] `Ctrl-Shift-[` (Apple `Cmd-Alt-[`),
 *   [unfoldCode] `Ctrl-Shift-]` (Apple `Cmd-Alt-]`), [foldAll] `Ctrl-Alt-[`, [unfoldAll]
 *   `Ctrl-Alt-]`; also [toggleFold].
 */
object Fold {
    /** The gutter column id and the widget type of the placeholder. */
    const val COLUMN = "fold"
    const val WIDGET_TYPE = "fold"

    /** Fold [FoldRange] (positions in the document after the transaction's changes). */
    val foldEffect: StateEffectType<FoldRange> = StateEffectType("fold.fold", ::mapRange)
    val unfoldEffect: StateEffectType<FoldRange> = StateEffectType("fold.unfold", ::mapRange)

    private fun mapRange(r: FoldRange, c: ChangeSet): FoldRange? {
        val a = c.mapPos(r.from, 1)
        val b = c.mapPos(r.to, -1)
        return if (b > a) FoldRange(a, b) else null
    }

    private val config: Facet<FoldConfig, FoldConfig> = Facet.first("fold.config", FoldConfig())

    private var nextId = 0L

    private fun replaceFor(r: FoldRange): Decoration = Decoration.Replace(WidgetKey(WIDGET_TYPE, "f${nextId++}"), fold = true)

    /** The folded ranges, as the surface draws them. */
    val field: StateField<RangeSet<Decoration>> = StateField(
        "fold",
        { RangeSet.empty() },
        { v, tr ->
            var out = v
            if (tr.docChanged && !out.isEmpty) {
                // A LOCAL edit that reaches a fold's hidden text shows it again (CM6 clears the folds a
                // user delete touches); a remote, agent, LSP, disk, undo or programmatic edit only
                // maps the folds through it, so a collaborator typing inside a fold never opens it.
                if (isLocal(tr)) {
                    val changes = tr.changes.iterChanges()
                    out = out.update(filter = { r -> changes.none { c -> touchesInside(c.fromA, c.toA, r.from, r.to) } })
                }
                out = out.map(tr.changes)
            }
            val add = ArrayList<Ranged<Decoration>>()
            val remove = HashSet<FoldRange>()
            for (e in tr.effects) {
                e.valueIf(foldEffect)?.let { r -> if (r.to > r.from && out.none { it.from == r.from && it.to == r.to } && add.none { it.from == r.from && it.to == r.to }) add += Ranged(r.from, r.to, replaceFor(r)) }
                e.valueIf(unfoldEffect)?.let { r -> remove += r }
            }
            if (remove.isNotEmpty()) {
                out = out.update(filter = { FoldRange(it.from, it.to) !in remove })
                add.removeAll { FoldRange(it.from, it.to) in remove }
            }
            if (add.isNotEmpty()) out = out.update(add = add)
            out
        },
        { f -> decorationsFacet.compute(FacetDep.field(f)) { it.field(f) } },
    )

    /** A transaction of the local user: a userEvent, not one of the exempt ones, not remote-annotated. */
    private fun isLocal(tr: dev.supermux.editor.core.Transaction): Boolean {
        val e = tr.annotation(dev.supermux.editor.core.Transaction.userEvent) ?: return false
        if (tr.annotation(dev.supermux.editor.compose.EditorAnnotations.remote) == true) return false
        return NOT_LOCAL.none { e == it || e.startsWith("$it.") }
    }

    private val NOT_LOCAL = listOf("undo", "redo", "disk", "remote", "agent", "lsp")

    /**
     * Undo brings back a fold a deletion removed (with its text): registered in editor-core's
     * `invertedEffectsFacet`, which the history reads. Only folds a change deleted whole; folding and
     * unfolding themselves are not undo steps (as in CM6).
     */
    private val restoreDeleted: (dev.supermux.editor.core.Transaction) -> List<StateEffect<*>> = { tr ->
        val before = tr.startState.fieldOrNull(field)
        if (!tr.docChanged || before == null || before.isEmpty) emptyList() else {
            val changes = tr.changes.iterChanges()
            before.filter { r -> changes.any { c -> c.toA > c.fromA && c.fromA <= r.from && c.toA >= r.to } }
                .map { foldEffect.of(FoldRange(it.from, it.to)) }
        }
    }

    /** A change of [fromA, toA) reaching the hidden text [from, to) without covering all of it. */
    private fun touchesInside(fromA: Int, toA: Int, from: Int, to: Int): Boolean {
        if (fromA <= from && toA >= to && toA > fromA) return false // covers it: the mapping drops it
        if (fromA == toA) return fromA > from && fromA < to // an insertion strictly inside
        return fromA < to && toA > from
    }

    /** The folded ranges in [state], in document order. */
    fun folded(state: EditorState): List<FoldRange> = state.fieldOrNull(field)?.map { FoldRange(it.from, it.to) }.orEmpty()

    /** The plugin, with [config]. */
    fun extension(config: FoldConfig = FoldConfig()): Extension = extensionOf(
        field,
        this.config.of(config),
        EditorViewport.extension,
        gutterMarkersFacet.compute(FacetDep.Doc, FacetDep.field(field), FacetDep.field(EditorViewport.field), FacetDep.facet(foldServiceFacet), FacetDep.facet(tabSizeFacet)) { markers(it) },
        gutterClickFacet.of(gutterClick),
        widgetClickFacet.of(chipClick),
        revealFacet.of(reveal),
        atomicDeleteFacet.of(atomicDelete),
        dev.supermux.editor.core.invertedEffectsFacet.of(restoreDeleted),
        keymapFacet.of(keymap),
        commandsFacet.of(commands),
    )

    // ------------------------------------------------------------------------ fold ranges --

    /**
     * The range the line [lineFrom, lineTo] folds, or null: the first `foldServiceFacet` answer
     * (the language's), else indentation.
     */
    fun foldable(state: EditorState, lineFrom: Int, lineTo: Int): FoldRange? {
        for (s in state.facet(foldServiceFacet)) s.foldable(state, lineFrom, lineTo)?.let { return it }
        return indentFold(state, lineFrom, lineTo)
    }

    /**
     * Indentation folding: the lines after [lineFrom]'s that are indented deeper than it (blank lines
     * among them included, trailing ones not), from its end to the end of the last of them.
     */
    fun indentFold(state: EditorState, lineFrom: Int, lineTo: Int): FoldRange? {
        val doc = state.doc
        val tab = state.facet(tabSizeFacet)
        val line = doc.lineIndexAt(lineFrom)
        val base = indentOf(doc, line, tab) ?: return null
        var last = -1
        var i = line + 1
        while (i < doc.lineCount) {
            val ind = indentOf(doc, i, tab)
            if (ind != null) {
                if (ind <= base) break
                last = i
            }
            i++
        }
        if (last < 0) return null
        return FoldRange(lineTo, lineEnd(doc, last))
    }

    /** Cheap: does [line] start an indentation fold (the next non-blank line, within 100, is deeper)? */
    private fun indentFoldable(doc: Rope, line: Int, tab: Int): Boolean {
        val base = indentOf(doc, line, tab) ?: return false
        var i = line + 1
        while (i < doc.lineCount && i - line <= 100) {
            val ind = indentOf(doc, i, tab)
            if (ind != null) return ind > base
            i++
        }
        return false
    }

    /** [line]'s indentation in columns, or null when it is blank. Looks at most 1,000 characters in. */
    private fun indentOf(doc: Rope, line: Int, tab: Int): Int? {
        val start = doc.lineStart(line)
        val end = lineEnd(doc, line)
        var col = 0
        var p = start
        val max = minOf(end, start + 1_000)
        while (p < max) {
            when (doc.charAt(p)) {
                ' ' -> col++
                '\t' -> col += tab - col % tab
                '\r' -> Unit
                else -> return col
            }
            p++
        }
        return null
    }

    private fun lineEnd(doc: Rope, line: Int): Int = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) - 1 else doc.length

    /** The fold starting on the line [lineFrom, lineTo], if any (CM6's findFold). */
    private fun foldOnLine(state: EditorState, lineFrom: Int, lineTo: Int): FoldRange? =
        state.fieldOrNull(field)?.between(lineFrom, lineTo)?.firstOrNull { it.from in lineFrom..lineTo }?.let { FoldRange(it.from, it.to) }

    // ----------------------------------------------------------------------------- gutter --

    private val openMarker = GutterMarker(COLUMN, "fold-open", "Fold")
    private val closedMarker = GutterMarker(COLUMN, "fold-closed", "Unfold")

    private fun markers(st: EditorState): RangeSet<GutterMarker> {
        val doc = st.doc
        val range = EditorViewport.rangeOf(st)
        val first = doc.lineIndexAt(minOf(range.first, doc.length))
        val last = doc.lineIndexAt(minOf(range.last, doc.length))
        val tab = st.facet(tabSizeFacet)
        val services = st.facet(foldServiceFacet)
        val folds = st.fieldOrNull(field)
        val out = ArrayList<Ranged<GutterMarker>>()
        for (line in first..last) {
            val lf = doc.lineStart(line)
            val lt = lineEnd(doc, line)
            val closed = folds?.between(lf, lt)?.any { it.from in lf..lt } == true
            val marker = when {
                closed -> closedMarker
                services.any { it.foldable(st, lf, lt) != null } || indentFoldable(doc, line, tab) -> openMarker
                else -> null
            }
            if (marker != null) out += Ranged(lf, lf, marker)
        }
        return RangeSet.of(out)
    }

    private val gutterClick = GutterClickHandler { t, column, line, _ -> column == COLUMN && toggleLine(t, line) }

    private val chipClick = WidgetClickHandler { t, key, from, to ->
        if (key.type != WIDGET_TYPE) false else unfold(t, listOf(FoldRange(from, to)))
    }

    private val reveal = RevealHandler { t, from, to ->
        val hit = folded(t.state).filter { if (from == to) from > it.from && from < it.to else it.from < to && it.to > from }
        unfold(t, hit)
    }

    private val atomicDelete = AtomicDeleteHandler { t, from, to, spec ->
        val st = t.state
        if (!st.facet(config).deleteFoldWhole || folded(st).none { it.from == from && it.to == to }) false
        else AtomicDelete.deleteWhole.deleteInto(t, from, to, spec)
    }

    private fun toggleLine(t: CommandTarget, line: Int): Boolean {
        val st = t.state
        val doc = st.doc
        if (line !in 0 until doc.lineCount) return false
        val lf = doc.lineStart(line)
        val lt = lineEnd(doc, line)
        foldOnLine(st, lf, lt)?.let { return unfold(t, listOf(it)) }
        val r = foldable(st, lf, lt) ?: return false
        t.dispatch(TransactionSpec(effects = listOf(foldEffect.of(r))))
        return true
    }

    private fun unfold(t: CommandTarget, ranges: List<FoldRange>): Boolean {
        val known = folded(t.state)
        val effects = ranges.filter { it in known }.map { unfoldEffect.of(it) }
        if (effects.isEmpty()) return false
        t.dispatch(TransactionSpec(effects = effects))
        return true
    }

    // --------------------------------------------------------------------------- commands --

    /** The lines of the cursors' heads, each once, in order. */
    private fun cursorLines(st: EditorState): List<Int> = st.selection.ranges.map { st.doc.lineIndexAt(it.head) }.distinct()

    /** Fold the first cursor line that can fold (CM6's foldCode). */
    val foldCode: Command = Command { t ->
        val st = t.state
        for (line in cursorLines(st)) {
            val lf = st.doc.lineStart(line)
            val r = foldable(st, lf, lineEnd(st.doc, line)) ?: continue
            if (foldOnLine(st, lf, lineEnd(st.doc, line)) != null) continue
            t.dispatch(TransactionSpec(effects = listOf(foldEffect.of(r))))
            return@Command true
        }
        false
    }

    /** Unfold the folds starting on the cursors' lines (CM6's unfoldCode). */
    val unfoldCode: Command = Command { t ->
        val st = t.state
        unfold(t, cursorLines(st).mapNotNull { line -> foldOnLine(st, st.doc.lineStart(line), lineEnd(st.doc, line)) })
    }

    /** Unfold the main cursor's line when folded, else fold it. */
    val toggleFold: Command = Command { t -> toggleLine(t, t.state.doc.lineIndexAt(t.state.selection.main.head)) }

    /** Fold every top-level range (CM6's foldAll: past each fold it makes). */
    val foldAll: Command = Command { t ->
        val st = t.state
        val doc = st.doc
        val known = folded(st).toSet()
        val effects = ArrayList<StateEffect<*>>()
        var line = 0
        while (line < doc.lineCount) {
            val lf = doc.lineStart(line)
            val lt = lineEnd(doc, line)
            val r = foldable(st, lf, lt)
            if (r == null) { line++; continue }
            if (r !in known) effects += foldEffect.of(r)
            line = doc.lineIndexAt(r.to) + 1
        }
        if (effects.isNotEmpty()) t.dispatch(TransactionSpec(effects = effects))
        effects.isNotEmpty()
    }

    /** Unfold everything. */
    val unfoldAll: Command = Command { t -> unfold(t, folded(t.state)) }

    /** CM6's foldKeymap. */
    val keymap: List<KeyBinding> = listOf(
        KeyBinding("Ctrl-Shift-[", foldCode, mac = "Cmd-Alt-["),
        KeyBinding("Ctrl-Shift-]", unfoldCode, mac = "Cmd-Alt-]"),
        KeyBinding("Ctrl-Alt-[", foldAll),
        KeyBinding("Ctrl-Alt-]", unfoldAll),
    )

    val commands: List<NamedCommand> = listOf(
        NamedCommand("fold.fold", "Fold", foldCode),
        NamedCommand("fold.unfold", "Unfold", unfoldCode),
        NamedCommand("fold.toggle", "Toggle Fold", toggleFold),
        NamedCommand("fold.foldAll", "Fold All", foldAll),
        NamedCommand("fold.unfoldAll", "Unfold All", unfoldAll),
    )
}

/** [Fold.extension]. */
fun fold(config: FoldConfig = FoldConfig()): Extension = Fold.extension(config)
