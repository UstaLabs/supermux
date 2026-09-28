package dev.supermux.editor.compose

import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A dispatch made while the view runs a key binding, an input handler, a menu command or a plugin's
 * click handler is LOCAL input, whatever its userEvent (or none): it never takes a piece of a fold.
 * Only the exempt userEvents and [EditorAnnotations.remote] pass.
 */
class CommandPolicingTest {
    private val text = "fun f() {\n  body 1\n  body 2\n}\nend"
    private val foldFrom = text.indexOf("{") + 1
    private val foldTo = text.indexOf("}\nend")

    private class Folds(range: Pair<Int, Int>) {
        val unfold = StateEffectType<Int>("unfold")
        var reveals = 0
        val field: StateField<RangeSet<Decoration>> = StateField(
            "folds",
            { RangeSet.of(listOf(Ranged(range.first, range.second, Decoration.Replace(WidgetKey("fold", "1"), fold = true) as Decoration))) },
            { v, tr ->
                var out = v.map(tr.changes)
                for (e in tr.effects) e.valueIf(unfold)?.let { at -> out = out.update(filter = { it.from != at }) }
                out
            },
            { f -> decorationsFacet.compute(FacetDep.field(f)) { it.field(f) } },
        )
        val extension = extensionOf(field, revealFacet.of(RevealHandler { t, from, _ ->
            reveals++
            t.dispatch(TransactionSpec(effects = listOf(unfold.of(from))))
            true
        }))
    }

    /** A plugin command deleting the fold's last hidden character, with no userEvent. */
    private fun unlabeled(at: Int) = Command { t -> t.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at - 1, at)))); true }

    @Test fun aKeyBoundCommandWithoutAUserEventCannotTakePartOfAFold() {
        val folds = Folds(foldFrom to foldTo)
        val before = EditorDiagnostics.unlabeledCommandEdits
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(foldTo), extensionOf(folds.extension, keymapOf(KeyBinding("Ctrl-d", unlabeled(foldTo))))))
        assertTrue(runBindings(view, KeyChord("d", ctrl = true), apple = false))
        assertEquals(text, view.state.doc.toString(), "the command deleted hidden text")
        assertEquals(1, folds.reveals, "the fold was not unfolded (the policy did not run)")
        assertEquals(before + 1, EditorDiagnostics.unlabeledCommandEdits, "the unlabeled edit was not reported")
    }

    @Test fun anInputHandlerWithoutAUserEventIsPolicedToo() {
        val folds = Folds(foldFrom to foldTo)
        val handler = InputHandler { t, _, _, _ -> t.dispatch(TransactionSpec(changes = listOf(ChangeSpec(foldTo - 1, foldTo)))); true }
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(foldTo), extensionOf(folds.extension, inputHandlerFacet.of(handler))))
        view.typeText("x")
        assertEquals(text, view.state.doc.toString())
        assertEquals(1, folds.reveals)
    }

    @Test fun aClickHandlerAndAMenuCommandArePoliced() {
        val folds = Folds(foldFrom to foldTo)
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(foldTo), folds.extension))
        view.runningCommand { unlabeled(foldTo).run(view) }
        assertEquals(text, view.state.doc.toString())
        assertEquals(1, folds.reveals)
    }

    @Test fun exemptEventsAndProgrammaticDispatchesStillPass() {
        // Outside any command, a dispatch without a userEvent is programmatic (a host's, a plugin's effect).
        val f1 = Folds(foldFrom to foldTo)
        val v1 = EditorView(EditorState.create(text, EditorSelection.cursor(foldTo), f1.extension))
        unlabeled(foldTo).run(v1)
        assertEquals(text.removeRange(foldTo - 1, foldTo), v1.state.doc.toString())
        // Inside a command, undo / redo / remote pass as before.
        for (spec in listOf(
            TransactionSpec(changes = listOf(ChangeSpec(foldTo - 1, foldTo)), userEvent = "undo"),
            TransactionSpec(changes = listOf(ChangeSpec(foldTo - 1, foldTo)), userEvent = "redo"),
            TransactionSpec(changes = listOf(ChangeSpec(foldTo - 1, foldTo)), annotations = listOf(EditorAnnotations.remote.of(true))),
        )) {
            val f = Folds(foldFrom to foldTo)
            val v = EditorView(EditorState.create(text, EditorSelection.cursor(foldTo), f.extension))
            val before = EditorDiagnostics.unlabeledCommandEdits
            v.runningCommand(key = true) { v.dispatch(spec) }
            assertEquals(text.removeRange(foldTo - 1, foldTo), v.state.doc.toString(), "$spec was policed")
            assertEquals(0, f.reveals)
            assertEquals(before, EditorDiagnostics.unlabeledCommandEdits, "an exempt edit was reported as unlabeled")
        }
    }

    @Test fun aThrowingInputHandlerFallsBackToPlainInput() {
        val bad = InputHandler { t, _, _, _ -> t.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 2), ChangeSpec(1, 3)))); true }
        val before = EditorDiagnostics.pluginFailures
        val view = EditorView(EditorState.create("abcdef", EditorSelection.cursor(6), inputHandlerFacet.of(bad)))
        assertTrue(view.typeText("x"))
        assertEquals("abcdefx", view.state.doc.toString(), "the typed text was lost")
        assertEquals(before + 1, EditorDiagnostics.pluginFailures)
    }
}
