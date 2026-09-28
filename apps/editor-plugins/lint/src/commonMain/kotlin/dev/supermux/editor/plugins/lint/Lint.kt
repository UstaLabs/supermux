package dev.supermux.editor.plugins.lint

import dev.supermux.editor.compose.GutterClickHandler
import dev.supermux.editor.compose.Hover
import dev.supermux.editor.compose.HoverResult
import dev.supermux.editor.compose.gutterClickFacet
import dev.supermux.editor.compose.hoverTooltip
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.GutterMarker
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.NamedCommand
import dev.supermux.editor.core.Panel
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.commandsFacet
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.gutterMarkersFacet
import dev.supermux.editor.core.keymapOf
import dev.supermux.editor.core.panelsFacet

/** How bad a diagnostic is (CM6's `"hint" | "info" | "warning" | "error"`), in that order. */
enum class Severity(val cls: String) {
    HINT("lint-hint"), INFO("lint-info"), WARNING("lint-warning"), ERROR("lint-error");
}

/**
 * A fix offered with a diagnostic (CM6's `Action`): its [name] on a button in the tooltip and the
 * panel, and [apply], the source's callback (an LSP code action). It runs with a [CommandTarget]
 * whose edits carry userEvent `edit.codeAction` unless they say otherwise (recorded: one undo step).
 */
data class DiagnosticAction(val name: String, val apply: (target: CommandTarget, from: Int, to: Int) -> Unit)

/** One diagnostic (CM6's `Diagnostic`): a range, a severity, a message, the tool it came from, fixes. */
data class Diagnostic(
    val from: Int,
    val to: Int,
    val severity: Severity,
    val message: String,
    val source: String? = null,
    val actions: List<DiagnosticAction> = emptyList(),
    /** The source's identity for it (an LSP client attaches code actions by it, never by position or order). */
    val id: String? = null,
)

/** The plugin's state: the diagnostics (a [RangeSet], mapped through edits), the panel, its selection. */
class LintState(
    internal val set: RangeSet<Diagnostic> = RangeSet.empty(),
    val panelOpen: Boolean = false,
    val panelFocus: Int = 0,
    val selected: Int = -1,
    /** The marks and gutter markers, built when the diagnostics are set and MAPPED through edits after (never rebuilt per keystroke). */
    internal val marks: RangeSet<Decoration> = RangeSet.empty(),
    internal val markers: RangeSet<GutterMarker> = RangeSet.empty(),
) {
    // Identity equality (not data): comparing 10k diagnostics per keystroke to learn "changed" costs
    // more than recomputing what reads them.
    internal fun copy(
        set: RangeSet<Diagnostic> = this.set, panelOpen: Boolean = this.panelOpen, panelFocus: Int = this.panelFocus,
        selected: Int = this.selected, marks: RangeSet<Decoration> = this.marks, markers: RangeSet<GutterMarker> = this.markers,
    ) = LintState(set, panelOpen, panelFocus, selected, marks, markers)

    /** Every diagnostic, in document order (by start, then end), with its current range. */
    val diagnostics: List<Diagnostic> get() = set.map { it.value.copy(from = it.from, to = it.to) }
}

/**
 * Diagnostics as data (CM6's `@codemirror/lint`): [Lint.setDiagnostics] replaces them (an LSP
 * `publishDiagnostics`, any linter), and the plugin shows them:
 *
 * - **Marks** `lint-error` / `lint-warning` / `lint-info` / `lint-hint` on their ranges (squiggles in
 *   the theme: `EditorTheme.lintSquiggles`); a zero-length one marks the character after it.
 * - **Gutter markers** in the `lint` column, the line's worst severity (`lint-error` / `lint-warning`
 *   / `lint-info`), the messages as the marker's tooltip; a click or tap on one shows the line's
 *   diagnostics (touch has no hover).
 * - **A hover tooltip** (`tooltip:lint`) listing the diagnostics under the mouse, worst first, with
 *   their actions as buttons.
 * - **Keys** (CM6's `lintKeymap`): `F8` [nextDiagnostic] (and `Shift-F8` [previousDiagnostic], VS
 *   Code's; CM6 binds none), `Mod-Shift-m` [openLintPanel]; the panel (`panel:lint`) lists them all.
 * - They move with edits; a diagnostic whose text was deleted goes.
 */
object Lint {
    const val TOOLTIP = "tooltip:lint"
    const val PANEL = "lint"
    const val HOVER_ID = "lint"
    const val GUTTER = "lint"

    val setDiagnosticsEffect: StateEffectType<List<Diagnostic>> = StateEffectType("lint.set")
    val togglePanel: StateEffectType<Boolean> = StateEffectType("lint.panel")
    val selectInPanel: StateEffectType<Int> = StateEffectType("lint.select")

    val field: StateField<LintState> = StateField(
        "lint",
        { LintState() },
        { v, tr ->
            var s = if (tr.docChanged) v.copy(set = v.set.map(tr.changes), marks = v.marks.map(tr.changes), markers = v.markers.map(tr.changes)) else v
            for (e in tr.effects) {
                e.valueIf(setDiagnosticsEffect)?.let { list ->
                    val len = tr.state.doc.length
                    val set = RangeSet.of(list.map { d ->
                        val a = d.from.coerceIn(0, len); val b = d.to.coerceIn(a, len)
                        Ranged(a, b, d)
                    })
                    s = s.copy(set = set, selected = -1, marks = buildMarks(set, len), markers = buildMarkers(set, tr.state.doc))
                }
                e.valueIf(togglePanel)?.let { open -> s = s.copy(panelOpen = open, panelFocus = if (open) s.panelFocus + 1 else s.panelFocus) }
                e.valueIf(selectInPanel)?.let { s = s.copy(selected = it) }
            }
            s
        },
    )

    fun state(st: EditorState): LintState = st.fieldOrNull(field) ?: LintState()

    fun diagnostics(st: EditorState): List<Diagnostic> = state(st).diagnostics

    /** CM6's `setDiagnostics(state, list)`: the transaction that replaces the diagnostics. */
    fun setDiagnostics(@Suppress("UNUSED_PARAMETER") st: EditorState, list: List<Diagnostic>): TransactionSpec =
        TransactionSpec(effects = listOf(setDiagnosticsEffect.of(list)))

    /** The diagnostics touching [from, to], worst first (then by position). */
    fun at(st: EditorState, from: Int, to: Int = from): List<Diagnostic> =
        state(st).set.between(from, to).map { it.value.copy(from = it.from, to = it.to) }
            .sortedWith(compareByDescending<Diagnostic> { it.severity }.thenBy { it.from })

    /** Run [action] of a diagnostic: its edits are `edit.codeAction` unless it labels them itself. */
    fun runAction(target: CommandTarget, d: Diagnostic, action: DiagnosticAction) {
        val labelled = object : CommandTarget {
            override val state: EditorState get() = target.state
            override fun dispatch(spec: TransactionSpec) = target.dispatch(if (spec.userEvent == null) spec.copy(userEvent = "edit.codeAction") else spec)
        }
        action.apply(labelled, d.from, d.to)
    }

    private fun showAt(t: CommandTarget, d: Diagnostic) {
        t.dispatch(TransactionSpec(effects = listOf(Hover.set.of(HOVER_ID to HoverResult(d.from, d.to, WidgetKey(TOOLTIP, "${d.from}:${d.to}"), above = false)))))
    }

    /** CM6's `nextDiagnostic`: select the next diagnostic after the selection (wrapping) and show it. */
    val nextDiagnostic: Command = Command { t ->
        val list = diagnostics(t.state)
        if (list.isEmpty()) return@Command false
        val sel = t.state.selection.main
        val next = list.firstOrNull { it.from >= sel.to + 1 } ?: list.first().takeUnless { it.from == sel.from && it.to == sel.to } ?: return@Command false
        t.dispatch(TransactionSpec(selection = EditorSelection.single(next.from, next.to), scrollIntoView = true, userEvent = "select.lint"))
        showAt(t, next)
        true
    }

    /** CM6's `previousDiagnostic`: the one before the selection (wrapping to the last). */
    val previousDiagnostic: Command = Command { t ->
        val list = diagnostics(t.state)
        if (list.isEmpty()) return@Command false
        val sel = t.state.selection.main
        val prev = list.lastOrNull { it.to < sel.to } ?: list.last().takeUnless { it.from == sel.from } ?: return@Command false
        t.dispatch(TransactionSpec(selection = EditorSelection.single(prev.from, prev.to), scrollIntoView = true, userEvent = "select.lint"))
        showAt(t, prev)
        true
    }

    /** Open the diagnostics panel and give it the focus (CM6's `openLintPanel`). */
    val openLintPanel: Command = Command { t ->
        if (t.state.fieldOrNull(field) == null) return@Command false
        t.dispatch(TransactionSpec(effects = listOf(togglePanel.of(true))))
        true
    }

    /** Close the panel; false (the key goes on) when it is closed. */
    val closeLintPanel: Command = Command { t ->
        if (!state(t.state).panelOpen) return@Command false
        t.dispatch(TransactionSpec(effects = listOf(togglePanel.of(false))))
        true
    }

    /** Move the cursor to diagnostic [d] (selected, scrolled into view: a fold holding it opens). */
    fun goTo(t: CommandTarget, d: Diagnostic) {
        t.dispatch(TransactionSpec(selection = EditorSelection.single(d.from, d.to), scrollIntoView = true, userEvent = "select.lint"))
    }

    val keymap: List<KeyBinding> = listOf(
        KeyBinding("Mod-Shift-m", openLintPanel),
        KeyBinding("F8", nextDiagnostic),
        KeyBinding("Shift-F8", previousDiagnostic),
    )

    val commands = listOf(
        NamedCommand("lint.next", "Next diagnostic", nextDiagnostic),
        NamedCommand("lint.previous", "Previous diagnostic", previousDiagnostic),
        NamedCommand("lint.panel", "Show diagnostics", openLintPanel),
    )

    internal fun decorations(st: EditorState): RangeSet<Decoration> = state(st).marks

    internal fun markers(st: EditorState): RangeSet<GutterMarker> = state(st).markers

    /**
     * The squiggle marks: where diagnostics overlap, each piece of text gets ONE mark, its worst
     * severity's (an error's squiggle is never drawn under a warning's). A zero-length diagnostic
     * marks the character after it (before it at the end).
     */
    internal fun buildMarks(set: RangeSet<Diagnostic>, len: Int): RangeSet<Decoration> {
        if (set.isEmpty) return RangeSet.empty()
        class Span(val from: Int, val to: Int, val sev: Severity)
        val spans = set.map { r ->
            var a = r.from; var b = r.to
            if (a == b) { if (b < len) b++ else if (a > 0) a-- }
            Span(a, b, r.value.severity)
        }.filter { it.from < it.to }
        // A sweep over the boundaries: the worst severity covering each piece.
        val points = spans.flatMap { listOf(it.from, it.to) }.distinct().sorted()
        val byStart = spans.sortedBy { it.from }
        val active = ArrayList<Span>()
        var k = 0
        val out = ArrayList<Ranged<Decoration>>()
        var last: Ranged<Decoration>? = null
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            while (k < byStart.size && byStart[k].from <= a) active += byStart[k++]
            active.removeAll { it.to <= a }
            val worst = active.maxOfOrNull { it.sev } ?: continue
            val cls = worst.cls
            val l = last
            if (l != null && l.to == a && (l.value as Decoration.Mark).classes.first() == cls) {
                last = Ranged(l.from, b, l.value); out[out.size - 1] = last
            } else {
                last = Ranged(a, b, Decoration.Mark(setOf(cls)) as Decoration); out += last
            }
        }
        return RangeSet.of(out)
    }

    internal fun buildMarkers(set: RangeSet<Diagnostic>, doc: dev.supermux.editor.core.Rope): RangeSet<GutterMarker> {
        if (set.isEmpty) return RangeSet.empty()
        val byLine = LinkedHashMap<Int, MutableList<Diagnostic>>()
        for (r in set) byLine.getOrPut(doc.lineIndexAt(r.from)) { ArrayList() } += r.value
        return RangeSet.of(byLine.map { (line, ds) ->
            val worst = ds.maxOf { it.severity }
            val kind = when (worst) { Severity.ERROR -> "lint-error"; Severity.WARNING -> "lint-warning"; else -> "lint-info" }
            Ranged(doc.lineStart(line), doc.lineStart(line), GutterMarker(GUTTER, kind, ds.sortedByDescending { it.severity }.joinToString("\n") { it.message }))
        })
    }

    /** Diagnostics under the pointer: their range, as a hover (hover tooltips' source). */
    internal fun hover(st: EditorState, pos: Int, side: Int): HoverResult? {
        val found = state(st).set.between(pos, pos).filter { r ->
            (r.from < pos || r.from == pos && side > 0 || r.from == r.to) && (r.to > pos || r.to == pos && side < 0 || r.from == r.to)
        }
        if (found.isEmpty()) return null
        val from = found.minOf { it.from }; val to = found.maxOf { it.to }
        return HoverResult(from, to, WidgetKey(TOOLTIP, "$from:$to"), above = true)
    }
}

/**
 * The lint plugin: the diagnostics field, their marks, gutter markers, hover tooltip, keymap and
 * panel. Content: [Lint.registerWidgets] (`tooltip:lint`, `panel:lint`).
 */
fun lint(): Extension = extensionOf(
    Lint.field,
    decorationsFacet.compute(FacetDep.field(Lint.field)) { Lint.decorations(it) },
    gutterMarkersFacet.compute(FacetDep.field(Lint.field)) { Lint.markers(it) },
    panelsFacet.compute(FacetDep.field(Lint.field)) { st -> if (Lint.state(st).panelOpen) Panel(Lint.PANEL, top = false) else null },
    hoverTooltip(Lint.HOVER_ID, hideOnChange = true) { st, pos, side -> Lint.hover(st, pos, side) },
    gutterClickFacet.of(GutterClickHandler { t, column, line, _ ->
        if (column != Lint.GUTTER) return@GutterClickHandler false
        val doc = t.state.doc
        val from = doc.lineStart(line)
        val to = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) - 1 else doc.length
        val ds = Lint.at(t.state, from, to).filter { doc.lineIndexAt(it.from) == line }
        if (ds.isEmpty()) return@GutterClickHandler false
        val a = ds.minOf { it.from }; val b = ds.maxOf { it.to }
        t.dispatch(TransactionSpec(effects = listOf(Hover.set.of(Lint.HOVER_ID to HoverResult(a, b, WidgetKey(Lint.TOOLTIP, "$a:$b"), above = false)))))
        true
    }),
    keymapOf(*Lint.keymap.toTypedArray()),
    commandsFacet.of(Lint.commands),
)
