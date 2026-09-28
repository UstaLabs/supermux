package dev.supermux.editor.plugins.lint

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lint tooltip and panel in a composed Editor. */
@OptIn(ExperimentalTestApi::class)
class LintSurfaceTest {
    private val text = (0 until 30).joinToString("\n") { "val v$it = TODO" }
    private val mod = if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft

    private fun ComposeUiTest.editor(): EditorView {
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(0), lint()))
        val registry = WidgetRegistry().also { Lint.registerWidgets(it) }
        setContent { Box(Modifier.size(600.dp, 400.dp)) { Editor(view, Modifier.fillMaxSize(), widgets = registry) } }
        waitForIdle()
        val fix = DiagnosticAction("Replace") { t, from, to -> t.dispatch(TransactionSpec(changes = listOf(ChangeSpec(from, to, "42")))) }
        val ds = listOf(3, 12, 20).map { line ->
            val l = view.state.doc.line(line + 1)
            Diagnostic(l.to - 4, l.to, if (line == 12) Severity.WARNING else Severity.ERROR, "TODO on line ${line + 1}", "test", listOf(fix))
        }
        view.dispatch(Lint.setDiagnostics(view.state, ds))
        waitForIdle()
        return view
    }

    private fun ComposeUiTest.field() = onNode(hasSetTextAction() and !SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))

    @Test fun f8ShowsTheTooltipAndATapOnItsActionFixesItKeepingTheFocus() = runComposeUiTest {
        val view = editor()
        field().requestFocus(); waitForIdle()
        field().performKeyInput { pressKey(Key.F8) }
        waitForIdle()
        onNodeWithTag(LintTags.TOOLTIP).fetchSemanticsNode()
        onNodeWithTag(LintTags.action("Replace")).performTouchInput { click() }
        waitForIdle()
        assertEquals("val v3 = 42", view.state.doc.line(4).text)
        assertTrue(view.focused, "the editor kept the focus")
        assertEquals(0, onAllNodesWithTag(LintTags.TOOLTIP).fetchSemanticsNodes().size)
    }

    @Test fun modShiftMOpensThePanelArrowsAndEnterGoEscapeCloses() = runComposeUiTest {
        val view = editor()
        field().requestFocus(); waitForIdle()
        field().performKeyInput { withKeyDown(mod) { withKeyDown(Key.ShiftLeft) { pressKey(Key.M) } } }
        waitForIdle()
        onNodeWithTag(LintTags.PANEL).assertIsFocused()
        onNodeWithTag(LintTags.PANEL).performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag(LintTags.PANEL).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        val sel = view.state.selection.main
        assertEquals("TODO", view.state.sliceDoc(sel.from, sel.to))
        assertEquals(12, view.state.doc.lineIndexAt(sel.from), "the second row: line 13")
        assertTrue(view.focused, "Enter hands the focus to the editor")
        // A tap on a row goes there too.
        onNodeWithTag(LintTags.row(2)).performTouchInput { click() }
        waitForIdle()
        assertEquals(20, view.state.doc.lineIndexAt(view.state.selection.main.from))
        field().performKeyInput { withKeyDown(mod) { withKeyDown(Key.ShiftLeft) { pressKey(Key.M) } } }
        waitForIdle()
        onNodeWithTag(LintTags.PANEL).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(LintTags.PANEL).fetchSemanticsNodes().size)
        assertTrue(view.focused)
    }
}
