package dev.supermux.editor.compose

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M3a's follow-ups: visual-row Home/End, the key path log. */
@OptIn(ExperimentalTestApi::class)
class FollowUpsTest {
    private val long = "    " + (0 until 40).joinToString(" ") { "word$it" }

    @Test fun homeAndEndGoToTheVisualRowFirstThenTheLine() = editorTest(EditorState.create(long + "\nnext"), widthDp = 300, lineWrap = true) { f ->
        val g = f.geometry
        val layout = g.lineLayout(0)
        assertTrue(layout.lineCount >= 3, "the line did not wrap: ${layout.lineCount} rows")
        val rowStart = layout.getLineStart(1)
        val rowEnd = layout.getLineEnd(1, visibleEnd = true)
        f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(rowStart + 3)))
        DefaultCommands.cursorLineStart.run(f.view)
        assertEquals(rowStart, f.view.state.selection.main.head, "Home: the row's start first")
        DefaultCommands.cursorLineStart.run(f.view)
        assertEquals(4, f.view.state.selection.main.head, "Home again: the line's first non-blank")
        f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(rowStart + 3)))
        DefaultCommands.cursorLineEnd.run(f.view)
        assertEquals(rowEnd, f.view.state.selection.main.head, "End: the row's visible end first")
        assertEquals(1, layout.getLineForOffset(rowEnd), "the row's end is still on that row")
        DefaultCommands.cursorLineEnd.run(f.view)
        assertEquals(long.length, f.view.state.selection.main.head, "End again: the line's end")
        // Shift extends the same way.
        f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(rowStart + 3)))
        DefaultCommands.selectLineStart.run(f.view)
        assertEquals(EditorSelection.single(rowStart + 3, rowStart), f.view.state.selection)
    }

    @Test fun withoutWrappingHomeAndEndAreTheLines() = editorTest(EditorState.create(long, EditorSelection.cursor(50)), widthDp = 300) { f ->
        DefaultCommands.cursorLineEnd.run(f.view)
        assertEquals(long.length, f.view.state.selection.main.head)
        DefaultCommands.cursorLineStart.run(f.view)
        assertEquals(4, f.view.state.selection.main.head)
    }

    @Test fun theKeyPathOfEveryKeyIsLogged() = editorTest(EditorState.create("abc", EditorSelection.cursor(1))) { f ->
        val log = ArrayList<Pair<String, KeyPath>>()
        f.view.onKeyPath = { k, p -> log += k to p }
        onNode(hasSetTextAction()).requestFocus()
        waitForIdle()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Q) }
        waitForIdle()
        assertEquals("ArrowRight" to KeyPath.KEYMAP, log.first())
        assertTrue(log.any { it.second == KeyPath.FIELD }, "an unbound letter was not logged as the field's: $log")
    }
}
