package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ScrollingTest {
    private val lines = (0 until 2000).joinToString("\n") { "line $it" }

    @Test fun theMouseWheelScrollsTheVisibleRange() = editorTest(EditorState.create(lines)) { f ->
        assertEquals(0, f.controller.drawnLines.first)
        // Desktop wheel scrolling is animated: let the animation finish before looking.
        onNodeWithTag(EDITOR_TAG).performMouseInput { scroll(5f) }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        assertTrue(f.controller.scroll.y > 0f, "the wheel did not scroll")
        val first = f.geometry.heights.lineAt(f.controller.scroll.y)
        assertTrue(first > 0, "still at the top")
        onNodeWithTag(EDITOR_TAG).performMouseInput { scroll(-50f) }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        assertEquals(0f, f.controller.scroll.y, "back up, clamped at the top")
    }

    @Test fun typingAtTheBottomEdgeScrollsTheCursorIntoView() = editorTest(EditorState.create(lines)) { f ->
        val lh = f.geometry.layouts.lineHeightPx
        val lastVisible = (300 / lh).toInt() - 1
        f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(f.view.state.doc.lineStart(lastVisible))))
        waitForIdle()
        assertEquals(0f, f.controller.scroll.y)
        repeat(5) { DefaultCommands.insertNewline.run(f.view) }
        waitForIdle()
        val caret = f.controller.caretRectOnScreen(f.view.state.selection.main.head)
        assertTrue(f.controller.scroll.y > 0f, "did not scroll")
        assertTrue(caret.bottom <= 300f && caret.top >= 0f, "the caret $caret is off screen")
        // Minimal: the caret sits a margin above the bottom edge, not in the middle.
        assertTrue(caret.bottom > 300f - 3 * lh, "scrolled further than needed: $caret")
    }

    @Test fun pageDownMovesTheViewAPage() = editorTest(EditorState.create(lines)) { f ->
        DefaultCommands.cursorPageDown.run(f.view)
        waitForIdle()
        assertTrue(f.controller.scroll.y >= 300f - 2 * f.geometry.layouts.lineHeightPx, "scrolled ${f.controller.scroll.y}")
        val caret = f.controller.caretRectOnScreen(f.view.state.selection.main.head)
        assertTrue(caret.top >= 0f && caret.bottom <= 300f)
    }

    @Test fun aLongLineScrollsHorizontallyWhenNotWrapping() = editorTest(EditorState.create("short\n" + "x".repeat(500))) { f ->
        f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(6)))
        DefaultCommands.cursorLineEnd.run(f.view)
        waitForIdle()
        assertTrue(f.controller.scroll.x > 0f, "no horizontal scroll")
        val caret = f.controller.caretRectOnScreen(f.view.state.selection.main.head)
        assertTrue(caret.left in f.controller.textLeft..400f, "caret at ${caret.left}")
        DefaultCommands.cursorLineStart.run(f.view)
        waitForIdle()
        assertEquals(0f, f.controller.scroll.x)
    }

    @Test fun aFlingKeepsScrollingAndDecays() = editorTest(EditorState.create(lines)) { f ->
        mainClock.autoAdvance = false
        onNodeWithTag(EDITOR_TAG).performTouchInput {
            swipeWithVelocity(start = Offset(200f, 250f), end = Offset(200f, 100f), endVelocity = 3000f, durationMillis = 100)
        }
        val atRelease = f.controller.scroll.y
        mainClock.advanceTimeBy(100)
        val early = f.controller.scroll.y
        mainClock.advanceTimeBy(1500)
        val later = f.controller.scroll.y
        mainClock.advanceTimeBy(3000)
        val end = f.controller.scroll.y
        mainClock.advanceTimeBy(1000)
        assertTrue(early > atRelease, "the fling did not continue after release ($atRelease -> $early)")
        assertTrue(later > early && end >= later, "the fling stopped early ($early, $later, $end)")
        assertEquals(end, f.controller.scroll.y, "the fling never stopped")
        assertTrue((early - atRelease) / 100f > (end - later) / 3000f, "the fling did not decay")
    }

    @Test fun scrollingUpInWrapModeMovesTheTextByExactlyTheScrollDelta() {
        // Every third line wraps into several rows: its real height is far above the estimate, and
        // the lines above the viewport are measured only as they scroll into view.
        val text = (0 until 600).joinToString("\n") { if (it % 3 == 0) "long line $it " + "word ".repeat(60) else "line $it" }
        editorTest(EditorState.create(text), lineWrap = true) { f ->
            f.controller.scroll.scrollTo(y = 1e9f) // the end, never having measured what is above
            waitForIdle()
            val d = 7f
            repeat(60) { step ->
                val ref = f.geometry.heights.lineAt(f.controller.scroll.y) + 1
                val before = f.geometry.lineTop(ref) - f.controller.scroll.y
                f.controller.scroll.scrollBy(0f, -d)
                waitForIdle()
                val after = f.geometry.lineTop(ref) - f.controller.scroll.y
                assertEquals(before + d, after, 0.5f, "step $step: line $ref jumped by ${after - before - d} px")
            }
        }
    }

    @Test fun aWiderGutterKeepsTheMeasuredWrappedHeights() {
        // 99 lines -> 100: the gutter grows a digit and the wrap width shrinks by a cell.
        val text = (0 until 99).joinToString("\n") { if (it == 0) "long " + "word ".repeat(80) else "line $it" }
        editorTest(EditorState.create(text), lineWrap = true) { f ->
            val lh = f.geometry.layouts.lineHeightPx
            val wrapped = f.geometry.heights.height(0)
            assertTrue(wrapped > 2 * lh, "line 0 wraps")
            // The caret away from line 0 too (the hidden field sits at the caret and would measure it).
            f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(f.view.state.doc.length)))
            f.controller.scroll.scrollTo(y = 1e9f)
            waitForIdle()
            f.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(f.view.state.doc.length, f.view.state.doc.length, "\nline 99"))))
            waitForIdle()
            assertEquals(100, f.view.state.doc.lineCount)
            assertTrue(f.geometry.heights.height(0) > 2 * lh, "the off-screen wrapped line fell back to a one-row estimate")
        }
    }

    @Test fun anEditAboveTheViewportKeepsTheVisibleTextInPlace() = editorTest(EditorState.create(lines), lineWrap = true) { f ->
        val lh = f.geometry.layouts.lineHeightPx
        f.controller.scroll.scrollTo(y = 500 * lh)
        waitForIdle()
        val first = f.controller.drawnLines.first + EditorDefaults.OVERSCAN_LINES
        val text = f.view.state.doc.line(first + 1).text
        val screenY = f.geometry.lineTop(first) - f.controller.scroll.y
        // A disk reload or another cursor inserts ten lines at the top: no scrollIntoView.
        f.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "new\n".repeat(10)))))
        waitForIdle()
        val moved = f.view.state.doc.lineIndexAt(f.view.state.doc.toString().indexOf("\n$text\n") + 1)
        assertEquals(first + 10, moved)
        assertEquals(screenY, f.geometry.lineTop(moved) - f.controller.scroll.y, 0.5f, "the text under the viewport moved")
    }
}
