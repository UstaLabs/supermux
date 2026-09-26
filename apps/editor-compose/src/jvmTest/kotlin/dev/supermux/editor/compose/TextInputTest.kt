package dev.supermux.editor.compose

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Typing through the REAL hidden field and the real key dispatch. Compose's test harness can commit
 * text to a field but cannot open a composing region, so composition itself is covered at the
 * [FieldSync] level (FieldSyncTest); here the field, the keymap and the transactions meet.
 */
@OptIn(ExperimentalTestApi::class)
class TextInputTest {
    private fun androidx.compose.ui.test.ComposeUiTest.field() = onNode(hasSetTextAction())

    private fun androidx.compose.ui.test.ComposeUiTest.focus() {
        field().requestFocus()
        waitForIdle()
    }

    @Test fun typingAsciiTurkishAndANewlineThroughTheField() = editorTest(EditorState.create("    fun main", EditorSelection.cursor(12))) { f ->
        val seen = ArrayList<Transaction>()
        f.view.addListener { seen += it }
        focus()
        field().performTextInput("() {")
        waitForIdle()
        field().performTextInput("\n")
        waitForIdle()
        field().performTextInput("ğüşıöç İ")
        waitForIdle()
        assertEquals("    fun main() {\n    ğüşıöç İ", f.view.state.doc.toString())
        assertEquals(f.view.state.doc.length, f.view.state.selection.main.head)
        assertTrue(seen.filter { it.docChanged }.all { it.isUserEvent("input") }, "every edit is user input")
    }

    @Test fun hardwareKeysRunTheKeymapAndTheFieldFollows() = editorTest(EditorState.create("abc\ndef", EditorSelection.cursor(3))) { f ->
        focus()
        field().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(EditorSelection.cursor(4), f.view.state.selection)
        field().performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertEquals("abcdef", f.view.state.doc.toString())
        field().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("abc\ndef", f.view.state.doc.toString())
        // The field's text and caret follow the key commands, so the next typed character lands right.
        field().performTextInput("X")
        waitForIdle()
        assertEquals("abc\nXdef", f.view.state.doc.toString())
        field().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) } }
        waitForIdle()
        assertEquals(SelectionRange(5, 4), f.view.state.selection.main)
    }

    @Test fun typedTextReplacesTheSelection() = editorTest(EditorState.create("hello world", EditorSelection.single(6, 11))) { f ->
        focus()
        field().performTextInput("there")
        waitForIdle()
        assertEquals("hello there", f.view.state.doc.toString())
        assertEquals(EditorSelection.cursor(11), f.view.state.selection)
    }

    @Test fun typingWithSeveralCursorsInsertsAtEach() = editorTest(
        EditorState.create("a\nb\nc", EditorSelection.create(listOf(SelectionRange(1), SelectionRange(3), SelectionRange(5)))),
    ) { f ->
        focus()
        field().performTextInput(";")
        waitForIdle()
        assertEquals("a;\nb;\nc;", f.view.state.doc.toString())
        field().performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertEquals("a\nb\nc", f.view.state.doc.toString())
    }

    @Test fun selectAllThenTypeReplacesEverything() = editorTest(EditorState.create((0 until 500).joinToString("\n") { "line $it" })) { f ->
        focus()
        field().performKeyInput { withKeyDown(if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.A) } }
        waitForIdle()
        assertEquals(EditorSelection.single(0, f.view.state.doc.length), f.view.state.selection)
        field().performTextInput("x")
        waitForIdle()
        assertEquals("x", f.view.state.doc.toString())
    }

    @Test fun theFieldHoldsAWindowOfTheRealTextAroundTheCaret() = editorTest(EditorState.create("one two three\nfour", EditorSelection.cursor(8))) { f ->
        focus()
        val config = field().fetchSemanticsNode().config
        val text = config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        val sel = config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        val base = f.controller.fieldSync.window.base
        assertEquals(f.view.state.doc.slice(base, base + text.length), text)
        assertEquals(TextRange(8 - base), sel)
    }

    @Test fun readOnlyTakesNoEditsButStillMoves() = editorTest(EditorState.create("abc", EditorSelection.cursor(3)), readOnly = true) { f ->
        onNodeWithTag(EDITOR_TAG).performMouseInput { click(f.controller.caretRectOnScreen(3).center) }
        waitForIdle()
        assertTrue(f.view.focused, "a read-only editor still takes focus")
        // A read-only field has no SetText semantics: keys go to the focused editor.
        onNodeWithTag(EDITOR_TAG).performKeyInput { pressKey(Key.Backspace) }
        onNodeWithTag(EDITOR_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        assertEquals("abc", f.view.state.doc.toString())
        assertEquals(EditorSelection.cursor(2), f.view.state.selection)
    }
}
