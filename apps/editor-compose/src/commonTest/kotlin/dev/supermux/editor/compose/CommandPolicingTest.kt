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

    @Test fun readOnlyDropsTheUsersLspActionsButNotTheServers() {
        val view = EditorView(EditorState.create("abc", EditorSelection.cursor(0)))
        view.readOnly = true
        for (e in listOf("edit.rename", "edit.format", "input.complete")) view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 1, "X")), userEvent = e))
        assertEquals("abc", view.state.doc.toString())
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 1, "S")), userEvent = "lsp"))
        assertEquals("Sbc", view.state.doc.toString())
    }

    @Test fun aHostListenerDispatchingDuringACommandIsNotTheCommands() {
        // The command's own edit is policed; a host listener that reacts to it synchronously with a
        // programmatic edit is the host's (not policed, not an unlabeled command edit).
        val folds = Folds(foldFrom to foldTo)
        val labeled = Command { t -> t.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "// ")), userEvent = "input")); true }
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(0), extensionOf(folds.extension, keymapOf(KeyBinding("Ctrl-d", labeled)))))
        var reacted = false
        view.addListener { tr ->
            if (!reacted && tr.docChanged) {
                reacted = true
                val at = tr.state.doc.toString().indexOf("}\nend")
                view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at - 1, at))))
            }
        }
        val before = EditorDiagnostics.unlabeledCommandEdits
        runBindings(view, KeyChord("d", ctrl = true), apple = false)
        assertTrue(reacted)
        assertEquals(0, folds.reveals, "the host's programmatic edit was policed as the command's")
        assertEquals(before, EditorDiagnostics.unlabeledCommandEdits)
        assertEquals(("// " + text).length - 1, view.state.doc.length)
    }

    @Test fun aThrowingCommandNeverEscapesAKeyAMenuOrAClick() {
        val boom = Command { error("a plugin bug") }
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(0), keymapOf(KeyBinding("Ctrl-d", boom))))
        val before = EditorDiagnostics.pluginFailures
        assertTrue(runBindings(view, KeyChord("d", ctrl = true), apple = false), "the key was not consumed")
        assertEquals(text, view.state.doc.toString())
        assertEquals(before + 1, EditorDiagnostics.pluginFailures)
        // The same guard for menus and click handlers.
        assertEquals(false, view.guarded("a click handler", false) { boom.run(view) })
        assertEquals(before + 2, EditorDiagnostics.pluginFailures)
    }
}
