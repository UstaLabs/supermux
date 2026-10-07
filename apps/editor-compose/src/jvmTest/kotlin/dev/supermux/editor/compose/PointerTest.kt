package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performTouchInput
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PointerTest {
    private val text = (0 until 200).joinToString("\n") { "line $it has some words" }

    /** The centre of the caret position [offset], in the surface's pixels. */
    private fun SurfaceFixture.at(offset: Int): Offset = controller.caretRectOnScreen(offset).center

    @Test fun aClickPlacesTheCursorAndFocusesWithoutAKeyboard() = editorTest(EditorState.create(text)) { f ->
        val target = f.view.state.doc.lineStart(3) + 5
        onNodeWithTag(EDITOR_TAG).performMouseInput { click(f.at(target)) }
        waitForIdle()
        assertEquals(EditorSelection.cursor(target), f.view.state.selection)
        assertTrue(f.view.focused, "a click did not focus")
        assertEquals(0, f.keyboard.shows.get(), "a mouse click raised a soft keyboard")
    }

    @Test fun shiftClickExtendsTheSelection() = editorTest(EditorState.create(text, EditorSelection.cursor(3))) { f ->
        val target = f.view.state.doc.lineStart(2) + 4
        onNodeWithTag(EDITOR_TAG).performMultiModalInput {
            key { keyDown(Key.ShiftLeft) }
            mouse { click(f.at(target)) }
            key { keyUp(Key.ShiftLeft) }
        }
        waitForIdle()
        assertEquals(EditorSelection.single(3, target), f.view.state.selection)
    }

    @Test fun aDragSelectsAndAutoScrollsAtTheEdge() = editorTest(EditorState.create(text)) { f ->
        val from = f.view.state.doc.lineStart(1) + 2
        val to = f.view.state.doc.lineStart(4) + 7
        onNodeWithTag(EDITOR_TAG).performMouseInput {
            moveTo(f.at(from)); press()
            moveTo(f.at(to))
        }
        waitForIdle()
        assertEquals(EditorSelection.single(from, to), f.view.state.selection)
        // Hold the button below the bottom edge: the view scrolls and the selection follows. The
        // auto-scroll asks for a frame as long as the button is held, so the clock is driven by hand.
        mainClock.autoAdvance = false
        onNodeWithTag(EDITOR_TAG).performMouseInput { moveTo(Offset(100f, 330f)) }
        mainClock.advanceTimeBy(1000)
        assertTrue(f.controller.scroll.y > 0f, "no auto-scroll")
        val head = f.view.state.selection.main.head
        assertTrue(f.view.state.doc.lineIndexAt(head) > 16, "the selection did not follow the scroll (head on line ${f.view.state.doc.lineIndexAt(head)})")
        assertEquals(from, f.view.state.selection.main.anchor)
        onNodeWithTag(EDITOR_TAG).performMouseInput { release() }
        mainClock.advanceTimeBy(100)
        val y = f.controller.scroll.y
        mainClock.advanceTimeBy(500)
        assertEquals(y, f.controller.scroll.y, "auto-scroll outlived the drag")
        mainClock.autoAdvance = true
        waitForIdle()
    }

    @Test fun doubleClickSelectsAWordTripleClickALine() = editorTest(EditorState.create(text)) { f ->
        val line = f.view.state.doc.lineStart(2)
        val inWord = line + "line 2 has s".length // inside "some"
        onNodeWithTag(EDITOR_TAG).performMouseInput { click(f.at(inWord)); click(f.at(inWord)) }
        waitForIdle()
        assertEquals(SelectionRange(line + 11, line + 15), f.view.state.selection.main)
        onNodeWithTag(EDITOR_TAG).performMouseInput { click(f.at(inWord)) }
        waitForIdle()
        assertEquals(SelectionRange(line, f.view.state.doc.lineStart(3)), f.view.state.selection.main)
    }

    @Test fun altDragMakesOneRangePerLine() = editorTest(EditorState.create(text)) { f ->
        val doc = f.view.state.doc
        val from = doc.lineStart(1) + 2
        val to = doc.lineStart(4) + 9
        onNodeWithTag(EDITOR_TAG).performMultiModalInput {
            key { keyDown(Key.AltLeft) }
            mouse { moveTo(f.at(from)); press(); moveTo(f.at(to)); release() }
            key { keyUp(Key.AltLeft) }
        }
        waitForIdle()
        val ranges = f.view.state.selection.ranges
        assertEquals(4, ranges.size, "column selection: $ranges")
        for ((i, r) in ranges.withIndex()) assertEquals(SelectionRange(doc.lineStart(1 + i) + 2, doc.lineStart(1 + i) + 9), r)
    }

    @Test fun aTapPlacesTheCursorFocusesAndRaisesTheKeyboard() = editorTest(EditorState.create(text)) { f ->
        val target = f.view.state.doc.lineStart(2) + 3
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(f.at(target)) }
        waitForIdle()
        assertEquals(EditorSelection.cursor(target), f.view.state.selection)
        assertTrue(f.view.focused)
        assertTrue(f.keyboard.shows.get() >= 1, "a tap did not raise the keyboard")
        // A second tap on an already focused editor raises it again (the user dismissed it).
        val shows = f.keyboard.shows.get()
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(f.at(target + 2)) }
        waitForIdle()
        assertTrue(f.keyboard.shows.get() > shows)
    }

    @Test fun aLongPressSelectsAWord() = editorTest(EditorState.create(text)) { f ->
        val line = f.view.state.doc.lineStart(1)
        onNodeWithTag(EDITOR_TAG).performTouchInput { longClick(f.at(line + 12)) }
        waitForIdle()
        assertEquals(SelectionRange(line + 11, line + 15), f.view.state.selection.main)
    }

    @Test fun aFingerDragScrollsAndNeverSelects() = editorTest(EditorState.create(text)) { f ->
        f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(0)))
        onNodeWithTag(EDITOR_TAG).performTouchInput {
            down(Offset(200f, 250f))
            repeat(10) { moveBy(Offset(0f, -15f)); advanceEventTime(16) }
            repeat(3) { moveBy(Offset.Zero); advanceEventTime(100) }
            up()
        }
        waitForIdle()
        assertTrue(f.controller.scroll.y > 50f, "a finger drag did not scroll")
        assertEquals(EditorSelection.cursor(0), f.view.state.selection, "a finger drag selected")
    }

    /** Mod, as the platform means it: Cmd on Apple, Ctrl elsewhere. */
    private val modKey = if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft

    @Test fun modClickPlacesTheCursorAndGoesToTheHandler() {
        val clicked = ArrayList<Int>()
        val handler = modClickFacet.of(ModClickHandler { _, pos -> clicked += pos; true })
        editorTest(EditorState.create(text, extensions = handler)) { f ->
            val line = f.view.state.doc.lineStart(2)
            val inWord = line + "line 2 has s".length // inside "some"
            // Focused (key events reach the editor), the mouse still over a word, then Mod alone.
            onNodeWithTag(EDITOR_TAG).performMouseInput { click(f.at(0)) }
            onNodeWithTag(EDITOR_TAG).performMouseInput { moveTo(f.at(inWord)) }
            onNode(hasEditorField()).performKeyInput { keyDown(modKey) }
            waitForIdle()
            assertEquals(line + 11 until line + 15, f.controller.modLink, "Mod over a word did not underline it")
            onNode(hasEditorField()).performKeyInput { keyUp(modKey) }
            waitForIdle()
            assertEquals(null, f.controller.modLink, "the underline outlived Mod")
            onNodeWithTag(EDITOR_TAG).performMultiModalInput {
                key { keyDown(modKey) }
                mouse { click(f.at(inWord)) }
                key { keyUp(modKey) }
            }
            waitForIdle()
            assertEquals(listOf(inWord), clicked)
            assertEquals(EditorSelection.cursor(inWord), f.view.state.selection)
            assertEquals(null, f.controller.modLink, "the underline outlived Mod")
        }
    }

    @Test fun modClickWithoutAHandlerIsAPlainClick() = editorTest(EditorState.create(text)) { f ->
        val target = f.view.state.doc.lineStart(3) + 5
        onNodeWithTag(EDITOR_TAG).performMouseInput { click(f.at(0)) }
        onNodeWithTag(EDITOR_TAG).performMultiModalInput {
            key { keyDown(modKey) }
            mouse { moveTo(f.at(target)) }
        }
        waitForIdle()
        assertEquals(null, f.controller.modLink, "underlined with nothing to go to")
        onNodeWithTag(EDITOR_TAG).performMultiModalInput {
            mouse { click(f.at(target)) }
            key { keyUp(modKey) }
        }
        waitForIdle()
        assertEquals(EditorSelection.cursor(target), f.view.state.selection)
    }
}
