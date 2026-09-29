package dev.supermux.editor.plugins.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Undo in a composed editor: text typed through the REAL hidden field (a soft keyboard's path),
 * then the hardware undo / redo chords, which the keymap takes before the field's own undo.
 */
@OptIn(ExperimentalTestApi::class)
class SurfaceUndoTest {
    private val apple = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    @Test fun typedTextIsUndoneAndRedoneByTheKeys() = runComposeUiTest {
        val view = EditorView(EditorState.create("fun f() ", EditorSelection.cursor(8), history()))
        setContent { Box(Modifier.size(400.dp, 300.dp)) { Editor(view, Modifier.fillMaxSize()) } }
        waitForIdle()
        val field = onNode(hasSetTextAction() and !SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        field.requestFocus()
        waitForIdle()
        for (c in "abc") { field.performTextInput(c.toString()); waitForIdle() }
        assertEquals("fun f() abc", view.state.doc.toString())
        val mod = if (apple) Key.MetaLeft else Key.CtrlLeft
        field.performKeyInput { withKeyDown(mod) { pressKey(Key.Z) } }
        waitForIdle()
        assertEquals("fun f() ", view.state.doc.toString(), "Mod-z did not undo the typing")
        field.performKeyInput { withKeyDown(mod) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } } }
        waitForIdle()
        assertEquals("fun f() abc", view.state.doc.toString(), "Mod-Shift-z did not redo")
        // The field shows the document again (the next keystroke lands where the caret is).
        field.performTextInput("d")
        waitForIdle()
        assertEquals("fun f() abcd", view.state.doc.toString())
    }
}
