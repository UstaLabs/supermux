package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Android's and iOS's rule (`inputOnAnyFocus = false`): the hidden field's input session, which is
 * what raises a soft keyboard, starts only for a touch. Compose's `SoftwareKeyboardController.show()`
 * does nothing for a `BasicTextField(TextFieldState)`, so `showKeyboardOnFocus` IS the request.
 */
@OptIn(ExperimentalTestApi::class)
class KeyboardRequestTest {
    private val text = (0 until 200).joinToString("\n") { "line $it has some words" }
    private fun SurfaceFixture.at(offset: Int): Offset = controller.caretRectOnScreen(offset).center

    @Test fun aMouseClickNeverRequestsTheKeyboard() = editorTest(EditorState.create(text), inputOnAnyFocus = false) { f ->
        onNodeWithTag(EDITOR_TAG).performMouseInput { click(f.at(12)) }
        waitForIdle()
        assertTrue(f.view.focused)
        assertEquals(false, f.controller.keyboardOnFocus, "a mouse click asked for a keyboard")
        assertEquals(0, f.keyboard.shows.get())
    }

    @Test fun aProgrammaticFocusNeverRequestsIt() = editorTest(EditorState.create(text), inputOnAnyFocus = false) { f ->
        runOnUiThread { f.view.focus() }
        waitForIdle()
        assertTrue(f.view.focused)
        assertEquals(false, f.controller.keyboardOnFocus)
    }

    @Test fun everyTapRequestsItAgain() = editorTest(EditorState.create(text), inputOnAnyFocus = false) { f ->
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(f.at(12)) }
        waitForIdle()
        val first = f.controller.keyboardOnFocus
        assertNotEquals(false, first, "a tap did not ask for the keyboard")
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(f.at(30)) }
        waitForIdle()
        val second = f.controller.keyboardOnFocus
        assertNotEquals(false, second)
        // A different value restarts the field's input session: a dismissed keyboard comes back.
        assertNotEquals(first, second, "a second tap would not restart the input session")
    }

    @Test fun aFingerScrollDoesNotRequestIt() = editorTest(EditorState.create(text), inputOnAnyFocus = false) { f ->
        onNodeWithTag(EDITOR_TAG).performTouchInput {
            down(Offset(200f, 250f))
            repeat(10) { moveBy(Offset(0f, -15f)); advanceEventTime(16) }
            up()
        }
        waitForIdle()
        assertTrue(f.controller.scroll.y > 0f)
        assertEquals(false, f.controller.keyboardOnFocus, "a scroll asked for a keyboard")
    }

    @Test fun onTheDesktopAndWebEveryFocusStartsASession() = editorTest(EditorState.create(text), inputOnAnyFocus = true) { f ->
        onNodeWithTag(EDITOR_TAG).performMouseInput { click(f.at(12)) }
        waitForIdle()
        assertEquals(true, f.controller.keyboardOnFocus, "no input session for a mouse click (web: no TEXTAREA, no IME)")
    }
}
