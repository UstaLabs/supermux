package dev.supermux.editor.compose

import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [EditorEffects.scrollTo]: a position other than the main cursor's is scrolled into view. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ScrollToTest {
    @Test fun aRangeAddedBesideTheMainOneIsScrolledIntoView() {
        val text = (0 until 500).joinToString("\n") { "line $it" }
        editorTest(EditorState.create(text, EditorSelection.cursor(0))) { f ->
            val far = f.view.state.doc.lineStart(400)
            f.view.dispatch(TransactionSpec(
                selection = f.view.state.selection.addRange(SelectionRange(far, far + 4), makeMain = false),
                effects = listOf(EditorEffects.scrollTo.of(far + 4)),
            ))
            waitForIdle()
            val line = f.geometry.heights.lineAt(f.controller.scroll.y)
            assertTrue(line in 370..400, "line $line at the top: the new range is not on screen")
            assertEquals(0, f.view.state.selection.mainIndex)
        }
    }

    /** [EditorEffects.scrollToCenter]: the line lands in the middle of the viewport, from above or below. */
    @Test fun aRevealedLineIsCentred() {
        val text = (0 until 500).joinToString("\n") { "line $it" }
        editorTest(EditorState.create(text, EditorSelection.cursor(0))) { f ->
            for (target in listOf(300, 120)) {
                val pos = f.view.state.doc.lineStart(target)
                f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(pos), effects = listOf(EditorEffects.scrollToCenter.of(pos))))
                waitForIdle()
                val h = f.geometry.heights
                val y = f.controller.scroll.y
                val top = h.lineAt(y)
                val middle = h.lineAt(y + f.controller.viewportHeightPx / 2)
                assertTrue(middle in target - 1..target + 1, "line $target is ${target - top} rows below the top, not centred (the middle row shows $middle)")
            }
        }
    }
}
