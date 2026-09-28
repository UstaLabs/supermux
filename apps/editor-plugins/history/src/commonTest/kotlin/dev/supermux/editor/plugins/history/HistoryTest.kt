package dev.supermux.editor.plugins.history

import dev.supermux.editor.compose.AtomicDelete
import dev.supermux.editor.compose.DefaultCommands
import dev.supermux.editor.compose.EditorAnnotations
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.atomicDeleteFacet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.runKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryTest {
    /** A view with history on a fake clock: [at] moves the time. */
    private class H(text: String, cursor: Int = 0, depth: Int = 100, extra: Extension = extensionOf(), selection: EditorSelection? = null) {
        var now = 0L
        val view = EditorView(EditorState.create(text, selection ?: EditorSelection.cursor(cursor), extensionOf(
            History.extension(HistoryConfig(depth = depth, clock = { now })), extra,
        )))
        val doc get() = view.state.doc.toString()
        fun at(t: Long): H { now = t; return this }
        fun type(s: String) { for (c in s) view.typeText(c.toString()) }
        fun undo() = History.undo.run(view)
        fun redo() = History.redo.run(view)
        fun move(pos: Int) = view.dispatch(TransactionSpec(selection = EditorSelection.cursor(pos), userEvent = "select"))
    }

    @Test fun typingInOneBurstIsOneStep() {
        val h = H("", 0)
        for ((i, c) in "hello".withIndex()) { h.at(i * 100L); h.type(c.toString()) }
        assertEquals("hello", h.doc)
        assertTrue(h.undo())
        assertEquals("", h.doc)
        assertEquals(0, h.view.state.selection.main.head)
        assertFalse(h.undo(), "nothing left to undo")
    }

    @Test fun aPauseLongerThanTheGroupDelayStartsANewStep() {
        val h = H("", 0)
        h.at(0).type("ab")
        h.at(600).type("cd")
        h.undo()
        assertEquals("ab", h.doc)
        h.undo()
        assertEquals("", h.doc)
    }

    @Test fun typingSomewhereElseStartsANewStep() {
        val h = H("0123456789", 2)
        h.at(0).type("a")
        h.move(8)
        h.at(50).type("b")
        h.undo()
        assertEquals("01a23456789", h.doc)
        // A cursor move alone is not a step of its own for undo.
        h.undo()
        assertEquals("0123456789", h.doc)
    }

    @Test fun backspacesJoinTheirBurst() {
        val h = H("abcdef", 6)
        repeat(3) { i -> h.at(i * 50L); DefaultCommands.deleteBackward.run(h.view) }
        assertEquals("abc", h.doc)
        h.undo()
        assertEquals("abcdef", h.doc)
    }

    private fun ime(h: H, at: Long, from: Int, to: Int, text: String, join: Boolean = false) {
        h.at(at).view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(from, to, text)), selection = EditorSelection.cursor(from + text.length),
            userEvent = if (at == 0L && !join) "input" else "input.ime", annotations = if (join) listOf(EditorAnnotations.imeJoinPrevious.of(true)) else emptyList()))
    }

    @Test fun anImeCompositionIsOneStepEvenPastTheTypingDelay() {
        val h = H("", 0)
        // The composition's first character arrives as plain input, the next steps as input.ime
        // (the second one saying it joins the previous), more than the 500 ms typing delay apart.
        ime(h, 0, 0, 0, "k")
        ime(h, 800, 0, 1, "か", join = true)
        ime(h, 1600, 0, 1, "漢")
        assertEquals("漢", h.doc)
        h.undo()
        assertEquals("", h.doc)
    }

    @Test fun anImeGroupBreaksAfterTwoSecondsOrANewline() {
        val h = H("", 0)
        ime(h, 0, 0, 0, "k")
        ime(h, 800, 0, 1, "か", join = true)
        ime(h, 2500, 1, 1, "な") // 2.5 s after the group started: a new step
        assertEquals("かな", h.doc)
        h.undo(); assertEquals("か", h.doc)
        h.undo(); assertEquals("", h.doc)
        val n = H("", 0)
        ime(n, 0, 0, 0, "a")
        ime(n, 100, 0, 1, "ab", join = true)
        ime(n, 200, 2, 2, "\n") // a newline composed: a new step
        n.undo(); assertEquals("ab", n.doc)
    }

    @Test fun pasteIsItsOwnStep() {
        val h = H("", 0)
        h.at(0).type("ab")
        h.at(100).view.paste("XYZ")
        h.at(200).type("c")
        assertEquals("abXYZc", h.doc)
        h.undo(); assertEquals("abXYZ", h.doc)
        h.undo(); assertEquals("ab", h.doc)
        h.undo(); assertEquals("", h.doc)
    }

    @Test fun enterStartsANewStepAndTheLineTypedAfterItJoinsIt() {
        val h = H("", 0)
        h.at(0).type("ab")
        h.at(100); DefaultCommands.insertNewline.run(h.view)
        h.at(200).type("cd")
        assertEquals("ab\ncd", h.doc)
        h.undo(); assertEquals("ab", h.doc)
        h.undo(); assertEquals("", h.doc)
    }

    @Test fun commandsAreStepsOfTheirOwn() {
        val h = H("line", 4)
        h.at(0).type("s")
        h.at(10); DefaultCommands.indentMore.run(h.view)
        h.at(20).type("t")
        h.undo(); assertEquals("    lines", h.doc)
        h.undo(); assertEquals("lines", h.doc)
        h.undo(); assertEquals("line", h.doc)
    }

    @Test fun undoAfterARemoteInsertBeforeTheLocalEditMapsCorrectly() {
        val h = H("0123456789", 5)
        h.at(0).type("ab")
        // A collaborator (or the disk) inserts before the local edit; that is not ours to undo.
        h.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(1, 1, "RR")), userEvent = "remote"))
        h.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "D")), userEvent = "disk"))
        h.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "Q")), userEvent = "input", annotations = listOf(EditorAnnotations.remote.of(true))))
        assertEquals("QD0RR1234ab56789", h.doc)
        h.undo()
        assertEquals("QD0RR123456789", h.doc, "the local edit was not undone where it now is")
        assertEquals(9, h.view.state.selection.main.head, "the cursor did not go back to where it was, mapped")
        assertFalse(h.undo(), "a remote change was undone")
        h.redo()
        assertEquals("QD0RR1234ab56789", h.doc)
    }

    @Test fun anUndoInsideTextAnAgentRewroteIsDropped() {
        // The user deleted "34"; then an agent rewrote the whole region around it. Undo must not put
        // "34" back into the middle of the agent's output.
        val h = H("0123456789", 5)
        h.at(0); DefaultCommands.deleteBackward.run(h.view); DefaultCommands.deleteBackward.run(h.view)
        assertEquals("01256789", h.doc)
        h.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(1, 6, "AGENT")), userEvent = "agent"))
        assertEquals("0AGENT89", h.doc)
        assertFalse(h.undo(), "undo resurrected text into the agent's rewrite")
        assertEquals("0AGENT89", h.doc)
        // An edit outside the rewritten text still undoes.
        val k = H("0123456789", 9)
        k.at(0).type("x")
        k.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(1, 4, "AGENT")), userEvent = "agent"))
        assertTrue(k.undo())
        assertEquals("0AGENT456789", k.doc)
    }

    @Test fun userTriggeredLspActionsAreUndoableAndServerEditsAreNot() {
        // The contract: `lsp` / `lsp.*` is server-initiated (not recorded, not policed); a completion
        // accepted, a rename, a code action, a format are the user's (recorded, undoable).
        for (event in listOf("input.complete", "edit.rename", "edit.codeAction", "edit.format")) {
            val h = H("val x = 1", 9)
            h.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(4, 5, "renamed")), userEvent = event))
            assertTrue(h.undo(), "$event is not undoable")
            assertEquals("val x = 1", h.doc)
        }
        val s = H("val x = 1", 9)
        s.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(4, 5, "pushed")), userEvent = "lsp"))
        s.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "// ")), userEvent = "lsp.workspaceEdit"))
        assertFalse(s.undo(), "a server-initiated lsp edit was undone")
    }

    @Test fun aRemoteEditThatDeletesTheLocalEditLeavesNothingToUndo() {
        val h = H("0123456789", 5)
        h.at(0).type("ab")
        h.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(3, 9)), userEvent = "remote"))
        assertEquals("012789", h.doc)
        assertFalse(h.undo())
        assertEquals("012789", h.doc)
    }

    @Test fun multiCursorUndoRestoresEveryCursor() {
        val sel = EditorSelection.create(listOf(SelectionRange(1), SelectionRange(7), SelectionRange(12)), 1)
        val h = H("aaaa\nbbbb\ncccc", selection = sel)
        h.at(0).type("x")
        assertEquals("axaaa\nbbxbb\nccxcc", h.doc)
        h.undo()
        assertEquals("aaaa\nbbbb\ncccc", h.doc)
        assertEquals(sel, h.view.state.selection)
        h.redo()
        assertEquals(3, h.view.state.selection.ranges.size)
        assertEquals("axaaa\nbbxbb\nccxcc", h.doc)
    }

    @Test fun redoAfterUndoAndANewEditClearsRedo() {
        val h = H("", 0)
        h.at(0).type("one")
        h.at(1000).type(" two")
        h.undo()
        assertEquals("one", h.doc)
        assertEquals(1, History.redoDepth(h.view.state))
        h.redo()
        assertEquals("one two", h.doc)
        h.undo()
        h.at(3000).type("!")
        assertEquals("one!", h.doc)
        assertFalse(h.redo(), "a new edit must clear redo")
        assertEquals("one!", h.doc)
    }

    @Test fun theDepthIsCappedAt100ByDefaultAndConfigurable() {
        val h = H("", 0)
        repeat(130) { i -> h.at(i * 1000L).type("x") }
        assertEquals(100, History.undoDepth(h.view.state))
        val small = H("", 0, depth = 3)
        repeat(5) { i -> small.at(i * 1000L).type("${i}") }
        var n = 0
        while (small.undo()) n++
        assertEquals(3, n)
        assertEquals("01", small.doc)
    }

    @Test fun undoSelectionStepsBackThroughCursorMoves() {
        val h = H("0123456789", 0)
        h.at(0).move(3); h.at(1000).move(6)
        assertTrue(History.undoSelection.run(h.view))
        assertEquals(3, h.view.state.selection.main.head)
        History.undoSelection.run(h.view)
        assertEquals(0, h.view.state.selection.main.head)
        assertEquals("0123456789", h.doc)
        History.redoSelection.run(h.view)
        assertEquals(3, h.view.state.selection.main.head)
    }

    @Test fun quickMovesOfOneKindAreOneSelectionStep() {
        // CM6: a run of `select` moves within the group delay is remembered once.
        val h = H("0123456789", 0)
        h.at(0).move(3); h.at(100).move(6); h.at(200).move(8)
        History.undoSelection.run(h.view)
        assertEquals(0, h.view.state.selection.main.head)
    }

    @Test fun undoAndRedoAreBoundToTheirKeys() {
        val h = H("", 0)
        h.type("ab")
        assertTrue(runKey(h.view, KeyChord("z", ctrl = true), apple = false))
        assertEquals("", h.doc)
        assertTrue(runKey(h.view, KeyChord("y", ctrl = true), apple = false))
        assertEquals("ab", h.doc)
        runKey(h.view, KeyChord("z", meta = true), apple = true)
        assertEquals("", h.doc)
        assertTrue(runKey(h.view, KeyChord("z", meta = true, shift = true), apple = true))
        assertEquals("ab", h.doc)
        runKey(h.view, KeyChord("z", ctrl = true), apple = false)
        assertTrue(runKey(h.view, KeyChord("z", ctrl = true, shift = true), apple = false))
        assertEquals("ab", h.doc)
    }

    @Test fun undoAndRedoCarryTheirUserEvents() {
        val h = H("", 0)
        val events = ArrayList<String?>()
        h.view.addListener { events.add(it.annotation(dev.supermux.editor.core.Transaction.userEvent)) }
        h.type("a"); h.undo(); h.redo()
        assertEquals<List<String?>>(listOf("input", "undo", "redo"), events)
    }

    @Test fun readOnlyDropsUndo() {
        val h = H("", 0)
        h.type("a")
        h.view.readOnly = true
        h.undo()
        assertEquals("a", h.doc)
    }

    /** A fold deleted whole (CM6's policy, with undo) comes back with its text. */
    @Test fun undoOfAWholeFoldDeleteRestoresItsText() {
        val text = "fun f() {\n  body 1\n  body 2\n}\nend"
        val from = text.indexOf("{") + 1
        val to = text.indexOf("}\nend")
        val folds = StateField<RangeSet<Decoration>>(
            "folds",
            { RangeSet.of(listOf(Ranged(from, to, Decoration.Replace(WidgetKey("fold", "1"), fold = true) as Decoration))) },
            { v, tr -> v.map(tr.changes) },
            { f -> decorationsFacet.compute(FacetDep.field(f)) { it.field(f) } },
        )
        val h = H(text, to, extra = extensionOf(folds, atomicDeleteFacet.of(AtomicDelete.deleteWhole)))
        DefaultCommands.deleteBackward.run(h.view)
        assertEquals(text.removeRange(from, to), h.doc)
        h.undo()
        assertEquals(text, h.doc)
        assertEquals(to, h.view.state.selection.main.head)
    }
}
