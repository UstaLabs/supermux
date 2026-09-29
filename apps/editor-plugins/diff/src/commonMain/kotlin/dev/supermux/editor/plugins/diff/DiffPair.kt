package dev.supermux.editor.plugins.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.LinkedScroll
import dev.supermux.editor.compose.LinkedSide
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.StateEffect
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf

/**
 * The side-by-side diff (VS Code's, CM6's MergeView): [base] (A, on the left, read-only) and
 * [working] (B, on the right, editable unless [DiffConfig.editable] is false), two linked views.
 *
 * The pair sets both views up (`StateEffect.appendConfig`): B gets the diff (its state holds the
 * model; an edit re-diffs at once, or in the background when too big, and the line mapping follows
 * in `lineMappingFacet`, so M3c's alignment pads the rows from both sides' measured heights), A gets
 * the base side (tints, character marks, the same folded runs, fed from B's model after every B
 * transaction). Expanding a folded run on either side expands both. Revert arrows are in B's gutter;
 * review threads go on B ([review]). Show it with [SideBySideDiff].
 *
 * **History.** A is read-only: create its state WITHOUT `history()` (a read-only view drops undo and
 * redo anyway, but it should not keep a stack). B's state carries it, and a revert is one undo step.
 */
class DiffPair(
    val base: EditorView,
    val working: EditorView,
    config: DiffConfig = DiffConfig(),
    host: DiffHost? = null,
) {
    /** The shared position both editors follow. */
    val link: LinkedScroll = LinkedScroll()

    private val removers = ArrayList<() -> Unit>()

    init {
        // A base view shown by another pair before (the host reuses it): that pair lets go of it.
        pairs[base]?.dispose()
        pairs[base] = this
        if (Diff.model(working.state) == null) working.dispatch(TransactionSpec(effects = listOf(StateEffect.appendConfig.of(
            Diff.extension(base.state.doc.toString(), config, host, inline = false),
        ))))
        val forward = Diff.forwardFacet.of { spec -> working.dispatch(spec) }
        base.dispatch(TransactionSpec(effects = listOf(
            if (base.state.fieldOrNull(Diff.baseField) == null && base.state.facet(Diff.forwardFacet) == null)
                StateEffect.appendConfig.of(extensionOf(Diff.baseSide, forwardSlot.of(forward)))
            // Wired to another working view before: this pair's working view now gets A's requests.
            else forwardSlot.reconfigure(forward),
        )))
        removers += working.addListener { push() }
        removers += working.addReplaceListener { push() }
        push()
    }

    /** The model B shows, now. */
    val model: DiffModel? get() = Diff.model(working.state)

    private fun push() {
        val m = Diff.model(working.state) ?: return
        val pinned = working.state.facet(Diff.pinnedLinesFacet)
        val was = base.state.fieldOrNull(Diff.baseField)
        if (was == null || was.model !== m || was.pinned != pinned) base.dispatch(TransactionSpec(effects = listOf(Diff.pushModel.of(Diff.Pushed(m, pinned)))))
    }

    /**
     * New texts (another file, another base): A's document and B's replaced (userEvent `disk`: never
     * an undo step), a new slice (what was expanded folds again).
     */
    fun load(baseText: String, workingText: String) {
        val a = base.state.doc
        if (a.toString() != baseText) base.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, a.length, baseText)), selection = EditorSelection.cursor(0), userEvent = "disk"))
        Diff.load(working, baseText, workingText)
    }

    /** Stop following B (the views keep their configuration). */
    fun dispose() {
        for (r in removers) r()
        removers.clear()
        if (pairs[base] === this) pairs.remove(base)
    }

    private companion object {
        /** Where A's requests go: one slot, re-wired when a new pair takes the base view. */
        val forwardSlot = dev.supermux.editor.core.Compartment("diff.forward")

        /** The live pair of each base view (a new pair on the same base disposes the old one). */
        val pairs = HashMap<EditorView, DiffPair>()
    }
}

/**
 * The two editors of [pair], linked: A (read-only) left, B right, a hairline between. [widgets]
 * must hold the diff's (and the threads') widgets ([rememberDiffWidgets]).
 */
@Composable
fun SideBySideDiff(
    pair: DiffPair,
    modifier: Modifier = Modifier,
    theme: EditorTheme = EditorTheme.default(),
    widgets: WidgetRegistry = rememberDiffWidgets(),
    lineWrap: Boolean = false,
    onFontSize: (Float) -> Unit = {},
    baseLabel: String = "Base",
    workingLabel: String = "Working copy",
    onPaint: (() -> Unit)? = null,
    /** Null: the working side is read-only unless [DiffConfig.editable]; true: read-only anyway. */
    readOnly: Boolean? = null,
) {
    val editable = pair.model?.config?.editable ?: true
    Row(modifier) {
        Editor(pair.base, Modifier.weight(1f).fillMaxHeight(), theme = theme, readOnly = true, lineWrap = lineWrap,
            onFontSize = onFontSize, label = baseLabel, linked = pair.link, linkedSide = LinkedSide.A, widgets = widgets)
        Box(Modifier.width(1.dp).fillMaxHeight().background(theme.gutterForeground.copy(alpha = 0.5f)))
        Editor(pair.working, Modifier.weight(1f).fillMaxHeight(), theme = theme, readOnly = readOnly ?: !editable, lineWrap = lineWrap,
            onFontSize = onFontSize, label = workingLabel, linked = pair.link, linkedSide = LinkedSide.B, widgets = widgets, onPaint = onPaint)
    }
}
