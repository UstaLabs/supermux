package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ahmet's iPhone crash: a long press on an empty area (to paste there). Where there is no word
 * (an empty line, past a line's end, below the last line) a long press places the caret and shows
 * the menu with Paste, and deciding to show Paste never READS the clipboard (on iOS a read shows the
 * paste permission prompt, and did so from inside a frame).
 */
@OptIn(ExperimentalTestApi::class)
class LongPressEmptyTest {
    private val text = "first line\n\nthird line is longer\nlast"

    private fun SurfaceFixture.at(offset: Int): Offset = controller.caretRectOnScreen(offset).center
    private fun ComposeUiTest.hasPaste() = runCatching { onNodeWithText("Paste", useUnmergedTree = true).assertExists() }.isSuccess

    private fun ComposeUiTest.longPressAt(f: SurfaceFixture, p: Offset, caret: Int) {
        f.clipboard.text = "CLIP"
        onNodeWithTag(EDITOR_TAG).performTouchInput { longClick(p) }
        waitForIdle()
        assertEquals(EditorSelection.cursor(caret), f.view.state.selection)
        assertEquals(TouchHandles.CURSOR, f.controller.handles, "no caret handle")
        assertTrue(hasPaste(), "no Paste in the menu")
        assertEquals(0, f.clipboard.reads, "showing the menu read the clipboard")
    }

    @Test fun onAnEmptyLine() = editorTest(EditorState.create(text)) { f ->
        val empty = f.view.state.doc.lineStart(1)
        longPressAt(f, f.at(empty), empty)
    }

    @Test fun pastTheEndOfALine() = editorTest(EditorState.create(text)) { f ->
        val end = f.view.state.doc.lineStart(1) - 1 // "first line|"
        longPressAt(f, f.at(end) + Offset(f.controller.layouts.charWidthPx * 8, 0f), end)
    }

    @Test fun belowTheLastLine() = editorTest(EditorState.create(text)) { f ->
        val docEnd = f.view.state.doc.length
        longPressAt(f, f.at(docEnd) + Offset(0f, f.controller.layouts.lineHeightPx * 4), docEnd)
    }

    @Test fun onAPhoneWidthEditorToo() = editorTest(EditorState.create(text), widthDp = 390, heightDp = 700) { f ->
        val empty = f.view.state.doc.lineStart(1)
        longPressAt(f, f.at(empty), empty)
    }

    @Test fun onAWordItStillSelectsTheWord() = editorTest(EditorState.create(text)) { f ->
        val w = f.view.state.doc.lineStart(2) + 2
        onNodeWithTag(EDITOR_TAG).performTouchInput { longClick(f.at(w)) }
        waitForIdle()
        assertEquals(TouchHandles.SELECTION, f.controller.handles)
        assertEquals(f.view.state.doc.lineStart(2), f.view.state.selection.main.from)
    }
}
