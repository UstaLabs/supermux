package dev.supermux.editor.compose

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS's space-bar trackpad (the floating cursor) moves the editor's caret by the finger's travel. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class FloatingCursorTest {
    private val text = (0 until 60).joinToString("\n") { "line $it with some text" }

    @Test fun theCaretFollowsTheFingersTravelThroughTheEditorsLayout() = editorTest(EditorState.create(text)) { f ->
        val start = f.view.state.doc.lineStart(3) + 5
        runOnUiThread { f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(start))) }
        waitForIdle()
        val d = f.controller.densityValue
        val lineDp = f.controller.layouts.lineHeightPx / d
        val cellDp = (f.geometry.rectFor(start + 1).left - f.geometry.rectFor(start).left) / d
        val fc = f.controller.floatingCursor
        // UIKit's points are anywhere (the input view's own space): only the travel counts.
        runOnUiThread { fc.begin(DpOffset(500.dp, 900.dp)) }
        runOnUiThread { fc.update(DpOffset(500.dp, (900 + 2 * lineDp).dp)) }
        waitForIdle()
        assertEquals(f.view.state.doc.lineStart(5) + 5, f.view.state.selection.main.head, "two lines of travel down")
        runOnUiThread { fc.update(DpOffset((500 + 3 * cellDp).dp, (900 + 2 * lineDp).dp)) }
        waitForIdle()
        assertEquals(f.view.state.doc.lineStart(5) + 8, f.view.state.selection.main.head, "three cells right")
        runOnUiThread { fc.end() }
        runOnUiThread { fc.update(DpOffset(500.dp, 900.dp)) }
        waitForIdle()
        assertEquals(f.view.state.doc.lineStart(5) + 8, f.view.state.selection.main.head, "an update after the end moves nothing")
        assertEquals(text, f.view.state.doc.toString(), "the floating cursor changed the text")
        assertEquals(true, f.view.state.selection.main.empty)
    }

    @Test fun anUpdateWithoutABeginMovesNothing() = editorTest(EditorState.create(text, EditorSelection.cursor(7))) { f ->
        runOnUiThread { f.controller.floatingCursor.update(DpOffset(0.dp, 400.dp)) }
        waitForIdle()
        assertEquals(7, f.view.state.selection.main.head)
    }
}
