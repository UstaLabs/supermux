package dev.supermux.editor.plugins.lint

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.Hover
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.FoldRange
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.Panel
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.gutterMarkersFacet
import dev.supermux.editor.core.panelsFacet
import dev.supermux.editor.core.runKey
import dev.supermux.editor.plugins.fold.Fold
import dev.supermux.editor.plugins.fold.fold
import dev.supermux.editor.plugins.history.History
import dev.supermux.editor.plugins.history.history
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LintTest {
    private val text = "val foo = bar\nval baz = qux\nfun f() {\n  one\n  two\n}\n"
    private fun view(sel: Int = 0, vararg ext: dev.supermux.editor.core.Extension) = EditorView(EditorState.create(text, EditorSelection.cursor(sel), extensionOf(lint(), *ext)))
    private fun at(s: String) = text.indexOf(s)
    private fun diag(s: String, sev: Severity = Severity.ERROR, msg: String = "bad $s", actions: List<DiagnosticAction> = emptyList()) =
        Diagnostic(at(s), at(s) + s.length, sev, msg, source = "test", actions = actions)
    private fun key(v: EditorView, spec: String) = runKey(v, KeyChord.parse(spec, isApplePlatform), isApplePlatform)
    private fun EditorView.set(vararg d: Diagnostic) = dispatch(Lint.setDiagnostics(state, d.toList()))
    private val EditorView.ranges get() = Lint.diagnostics(state).map { state.sliceDoc(it.from, it.to) }

    @Test fun diagnosticsMoveWithEditsAndGoWithTheirText() {
        val v = view()
        v.set(diag("bar"), diag("qux", Severity.WARNING))
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "// head\n")), userEvent = "input"))
        assertEquals(listOf("bar", "qux"), v.ranges)
        val q = v.state.doc.toString().indexOf("qux")
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(q, q + 3)), userEvent = "delete"))
        assertEquals(listOf("bar"), v.ranges, "its text deleted: gone")
        val b = v.state.doc.toString().indexOf("bar")
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(b + 1, b + 1, "aa")), userEvent = "input"))
        assertEquals(listOf("baaar"), v.ranges, "typing inside grows it")
    }

    @Test fun anUpdateReplacesTheOldDiagnostics() {
        val v = view()
        v.set(diag("bar"), diag("qux"))
        v.set(diag("baz", Severity.INFO))
        assertEquals(listOf("baz"), v.ranges)
        v.set()
        assertTrue(Lint.diagnostics(v.state).isEmpty())
        assertTrue(v.state.facet(gutterMarkersFacet).all { it.isEmpty })
    }

    @Test fun marksAndGutterMarkersShowTheWorstSeverityFirst() {
        val v = view()
        v.set(diag("foo", Severity.WARNING, "w"), diag("bar", Severity.ERROR, "e"), diag("qux", Severity.HINT, "h"), Diagnostic(at("baz"), at("baz"), Severity.INFO, "point"))
        val marks = v.state.facet(decorationsFacet).flatMap { set -> set.map { Triple(it.from, it.to, (it.value as? Decoration.Mark)?.classes) } }
        assertTrue(Triple(at("bar"), at("bar") + 3, setOf("lint-error")) in marks)
        assertTrue(Triple(at("baz"), at("baz") + 1, setOf("lint-info")) in marks, "a point diagnostic marks one character")
        val markers = v.state.facet(gutterMarkersFacet).flatMap { it.toList() }
        assertEquals(listOf("lint-error", "lint-info"), markers.map { it.value.kind }, "per line: its worst")
        assertEquals("e\nw", markers[0].value.tooltip, "worst message first")
        assertEquals(listOf("e", "w"), Lint.at(v.state, 0, at("\n")).map { it.message })
    }

    @Test fun overlappingDiagnosticsDrawTheWorstSeverityOnTop() {
        val v = view()
        v.set(Diagnostic(0, 10, Severity.WARNING, "w"), Diagnostic(4, 6, Severity.ERROR, "e"), Diagnostic(8, 12, Severity.HINT, "h"))
        val marks = v.state.facet(decorationsFacet).flatMap { set -> set.map { Triple(it.from, it.to, (it.value as Decoration.Mark).classes.single()) } }
        assertEquals(listOf(Triple(0, 4, "lint-warning"), Triple(4, 6, "lint-error"), Triple(6, 10, "lint-warning"), Triple(10, 12, "lint-hint")), marks)
        // Mapped through an edit, not rebuilt.
        v.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "xx")), userEvent = "input"))
        val moved = v.state.facet(decorationsFacet).flatMap { set -> set.map { it.from to it.to } }
        assertEquals(listOf(2 to 6, 6 to 8, 8 to 12, 12 to 14), moved)
    }

    @Test fun f8AndShiftF8WalkTheDiagnosticsWrappingAndShowTheTooltip() {
        val v = view()
        v.set(diag("bar"), diag("qux"), diag("two", Severity.WARNING))
        assertTrue(key(v, "F8"))
        assertEquals("bar", v.state.sliceDoc(v.state.selection.main.from, v.state.selection.main.to))
        assertNotNull(Hover.shown(v.state, Lint.HOVER_ID), "the tooltip shows")
        key(v, "F8"); key(v, "F8")
        assertEquals("two", v.state.sliceDoc(v.state.selection.main.from, v.state.selection.main.to))
        key(v, "F8")
        assertEquals("bar", v.state.sliceDoc(v.state.selection.main.from, v.state.selection.main.to), "wraps")
        assertTrue(key(v, "Shift-F8"))
        assertEquals("two", v.state.sliceDoc(v.state.selection.main.from, v.state.selection.main.to), "back wraps to the last")
        assertTrue(key(v, "Shift-F8"))
        assertEquals("qux", v.state.sliceDoc(v.state.selection.main.from, v.state.selection.main.to))
        v.set()
        assertFalse(key(v, "F8"), "none: the key goes on")
    }

    @Test fun thePanelOpensClosesAndGoesToADiagnosticInsideAFold() {
        val v = view(0, fold())
        v.set(diag("one", Severity.WARNING))
        // Fold the function body; the diagnostic is hidden inside it.
        v.dispatch(TransactionSpec(effects = listOf(Fold.foldEffect.of(FoldRange(at("{") + 1, at("}"))))))
        assertTrue(v.state.field(Fold.field).size == 1)
        assertTrue(key(v, "Mod-Shift-m"))
        assertEquals(listOf(Panel(Lint.PANEL, top = false)), v.state.facet(panelsFacet))
        Lint.goTo(v, Lint.diagnostics(v.state)[0])
        assertEquals("one", v.state.sliceDoc(v.state.selection.main.from, v.state.selection.main.to))
        assertEquals(0, v.state.field(Fold.field).size, "the fold holding it opened")
        assertTrue(Lint.closeLintPanel.run(v))
        assertTrue(v.state.facet(panelsFacet).isEmpty())
        assertFalse(Lint.closeLintPanel.run(v))
    }

    @Test fun anActionRunsAsACodeActionOneUndoStep() {
        var seen: String? = null
        val fix = DiagnosticAction("Rename to baz") { t, from, to ->
            t.dispatch(TransactionSpec(changes = listOf(ChangeSpec(from, to, "baz"))))
        }
        val v = view(0, history())
        v.addListener { tr -> if (tr.docChanged) seen = tr.annotation(dev.supermux.editor.core.Transaction.userEvent) }
        v.set(diag("bar", actions = listOf(fix)))
        val d = Lint.diagnostics(v.state)[0]
        Lint.runAction(v, d, d.actions[0])
        assertEquals("val foo = baz", v.state.doc.lineAt(0).text)
        assertEquals("edit.codeAction", seen)
        assertTrue(History.undo.run(v))
        assertEquals("val foo = bar", v.state.doc.lineAt(0).text)
    }

    @Test fun theHoverSourceFindsTheDiagnosticsUnderThePointer() {
        val v = view()
        v.set(diag("bar"), diag("foo", Severity.WARNING))
        val h = Lint.hover(v.state, at("bar") + 1, 1)
        assertNotNull(h)
        assertEquals(at("bar"), h.from)
        assertNull(Lint.hover(v.state, at("val"), 1))
        assertNull(Lint.hover(v.state, at("bar") + 3, 1), "just after it, on the right side: not it")
    }
}
