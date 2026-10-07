package dev.supermux.editor.plugins.basics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The soft-keyboard path: text committed to the editor's REAL hidden field (what a soft keyboard
 * does: it edits a text field, it presses no keys), diffed into a transaction, offered to the
 * plugin's input handler; a soft Return runs the keymap's Enter.
 */
@OptIn(ExperimentalTestApi::class)
class SoftKeyboardTest {
    @Test fun bracketsAndEnterThroughTheHiddenField() = runComposeUiTest {
        val view = EditorView(EditorState.create("fun f() ", EditorSelection.cursor(8), basics()))
        setContent { Box(Modifier.size(400.dp, 300.dp)) { Editor(view, Modifier.fillMaxSize()) } }
        waitForIdle()
        val field = onNode(hasSetTextAction() and !SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        field.requestFocus()
        waitForIdle()
        field.performTextInput("{")
        waitForIdle()
        assertEquals("fun f() {}", view.state.doc.toString())
        assertEquals(9, view.state.selection.main.head, "the cursor is not between the braces")
        field.performTextInput("\n")
        waitForIdle()
        assertEquals("fun f() {\n    \n}", view.state.doc.toString())
        // One character per commit, as a keyboard types (a multi-character commit, a suggestion,
        // is text, not a bracket: it is never paired, like CM6).
        field.performTextInput("g")
        waitForIdle()
        field.performTextInput("(")
        waitForIdle()
        assertEquals("fun f() {\n    g()\n}", view.state.doc.toString())
        field.performTextInput(")")
        waitForIdle()
        assertEquals("fun f() {\n    g()\n}", view.state.doc.toString(), "the closer was not stepped over")
        assertEquals("fun f() {\n    g()".length, view.state.selection.main.head)
    }
}
