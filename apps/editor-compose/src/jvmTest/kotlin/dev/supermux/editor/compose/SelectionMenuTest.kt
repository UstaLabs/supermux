package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.SelectionRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The platform toolbar, recorded: what it was last asked to show. */
private class FakeToolbar : TextToolbar {
    var rect: Rect? = null
    var copy: (() -> Unit)? = null
    var paste: (() -> Unit)? = null
    var cut: (() -> Unit)? = null
    var selectAll: (() -> Unit)? = null
    var shows = 0
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        this.rect = rect; copy = onCopyRequested; paste = onPasteRequested; cut = onCutRequested; selectAll = onSelectAllRequested
        shows++
        status = TextToolbarStatus.Shown
    }

    override fun hide() { status = TextToolbarStatus.Hidden }
}

@OptIn(ExperimentalTestApi::class)
class SelectionMenuTest {
    private val text = (0 until 200).joinToString("\n") { "line $it has some words" }

    private fun SurfaceFixture.at(offset: Int): Offset = controller.caretRectOnScreen(offset).center

    private fun ComposeUiTest.longPressSome(f: SurfaceFixture): Int {
        val line = f.view.state.doc.lineStart(1)
        onNodeWithTag(EDITOR_TAG).performTouchInput { longClick(f.at(line + 12)) }
        waitForIdle()
        return line
    }

    private fun ComposeUiTest.menuShown(): Boolean = runCatching { onNodeWithTag(EditorMenu.TAG, useUnmergedTree = true).assertExists() }.isSuccess
    private fun ComposeUiTest.has(label: String): Boolean = runCatching { onNodeWithText(label, useUnmergedTree = true).assertExists() }.isSuccess
    private fun ComposeUiTest.tapItem(label: String) { onNodeWithText(label, useUnmergedTree = true).performClick(); waitForIdle() }

    @Test fun theMenuAppearsAfterALongPressAndCopyCopies() = editorTest(EditorState.create(text)) { f ->
        val line = longPressSome(f)
        assertTrue(menuShown(), "no menu after the long press")
        assertTrue(has("Cut") && has("Copy") && has("Select All"))
        assertFalse(has("Paste"), "Paste with an empty clipboard")
        tapItem("Copy")
        assertEquals("some", f.clipboard.text)
        assertEquals(SelectionRange(line + 11, line + 15), f.view.state.selection.main, "copy changed the selection")
        assertFalse(menuShown(), "the menu stayed after Copy")
    }

    @Test fun cutThenPasteAtTheCaretFromTheCaretHandlesMenu() = editorTest(EditorState.create(text)) { f ->
        val line = longPressSome(f)
        tapItem("Cut")
        assertEquals("some", f.clipboard.text)
        assertEquals("line 1 has  words", f.view.state.doc.lineText(1))
        // A tap places the caret with its handle; a tap on the handle opens the menu there.
        val at = f.view.state.doc.lineStart(3) + 4
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(f.at(at)) }
        waitForIdle()
        assertFalse(menuShown(), "a tap alone opened the menu")
        val spot = f.controller.handleSpots().single()
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(spot.body) }
        waitForIdle()
        assertTrue(menuShown(), "no menu after a tap on the caret handle")
        assertFalse(has("Copy"), "Copy with nothing selected")
        tapItem("Paste")
        assertEquals("linesome 3 has some words", f.view.state.doc.lineText(3))
        assertEquals(1, f.clipboard.syncReads, "Paste did not read inside the menu action")
        assertEquals(0, f.clipboard.reads, "Paste read the clipboard later, from a coroutine (iOS asks every time)")
        assertEquals(EditorSelection.cursor(at + 4), f.view.state.selection)
        assertTrue(line > 0)
    }

    @Test fun selectAllSelectsEverythingAndKeepsTheMenuAndHandles() = editorTest(EditorState.create(text)) { f ->
        longPressSome(f)
        tapItem("Select All")
        assertEquals(SelectionRange(0, f.view.state.doc.length), f.view.state.selection.main)
        assertEquals(TouchHandles.SELECTION, f.controller.handles)
        assertTrue(menuShown(), "the menu went away after Select All")
        assertFalse(has("Select All"), "Select All offered with everything selected")
    }

    @Test fun readOnlyOffersOnlyCopyAndSelectAll() = editorTest(EditorState.create(text), readOnly = true) { f ->
        f.clipboard.text = "x"
        longPressSome(f)
        assertTrue(has("Copy") && has("Select All"))
        assertFalse(has("Cut") || has("Paste"), "an edit offered while read-only")
    }

    @Test fun theMenuHidesWhileScrollingAndComesBack() = editorTest(EditorState.create(text)) { f ->
        longPressSome(f)
        assertTrue(menuShown())
        mainClock.autoAdvance = false
        f.controller.scroll.scrollBy(0f, 10f)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        assertFalse(menuShown(), "the menu stayed up while scrolling")
        mainClock.advanceTimeBy(EditorMenu.SCROLL_SETTLE_MILLIS + 200)
        assertTrue(menuShown(), "the menu did not come back after the scroll")
        mainClock.autoAdvance = true
        waitForIdle()
    }

    @Test fun theMenuHidesWhileAHandleIsDraggedAndReappearsAfter() = editorTest(EditorState.create(text)) { f ->
        longPressSome(f)
        val s = f.controller.handleSpots().single { it.kind == HandleKind.END }
        onNodeWithTag(EDITOR_TAG).performTouchInput { down(s.body); moveBy(Offset(30f, 0f)) }
        waitForIdle()
        assertFalse(menuShown(), "the menu stayed up during a handle drag")
        onNodeWithTag(EDITOR_TAG).performTouchInput { up() }
        waitForIdle()
        assertTrue(menuShown(), "the menu did not come back after the drag")
    }

    @Test fun typingHidesTheMenu() = editorTest(EditorState.create(text)) { f ->
        longPressSome(f)
        f.view.typeText("q")
        waitForIdle()
        assertFalse(menuShown())
    }

    // #138: a right-click did nothing on desktop (onPress returned on any non-primary button).
    @Test fun aRightClickOutsideTheSelectionMovesTheCaretAndOpensTheMenuThere() = editorTest(EditorState.create(text)) { f ->
        val target = f.view.state.doc.lineStart(2) + 5
        onNodeWithTag(EDITOR_TAG).performMouseInput { rightClick(f.at(target)) }
        waitForIdle()
        assertEquals(EditorSelection.cursor(target), f.view.state.selection, "the caret did not move to the right-click")
        assertTrue(menuShown(), "no menu after a right-click")
        assertTrue(has("Select All"))
        val anchor = f.controller.menuAnchor()
        assertTrue((anchor.center - f.at(target)).getDistance() < 1f && anchor.width == 0f, "the menu points at $anchor, not the click")
        onNodeWithTag(EDITOR_TAG).performMouseInput { click(f.at(target + 3)) }
        waitForIdle()
        assertFalse(menuShown(), "a left-click left the menu open")
        assertNull(f.controller.menuPoint)
    }

    @Test fun aRightClickInTheSelectionKeepsItForCopy() = editorTest(EditorState.create(text)) { f ->
        val line = f.view.state.doc.lineStart(1)
        f.view.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.single(line + 11, line + 15)))
        onNodeWithTag(EDITOR_TAG).performMouseInput { rightClick(f.at(line + 13)) }
        waitForIdle()
        assertEquals(SelectionRange(line + 11, line + 15), f.view.state.selection.main, "the right-click dropped the selection")
        assertTrue(has("Cut") && has("Copy"))
        tapItem("Copy")
        assertEquals("some", f.clipboard.text)
    }

    @Test fun thePlatformToolbarGetsTheSameItems() {
        val toolbar = FakeToolbar()
        editorTest(EditorState.create(text), platformMenu = true, toolbar = toolbar) { f ->
            longPressSome(f)
            assertFalse(menuShown(), "the surface drew its own menu on a platform with a toolbar")
            assertEquals(TextToolbarStatus.Shown, toolbar.status)
            assertNotNull(toolbar.copy); assertNotNull(toolbar.cut); assertNotNull(toolbar.selectAll)
            assertNull(toolbar.paste, "Paste with an empty clipboard")
            val r = assertNotNull(toolbar.rect)
            val a = f.controller.menuAnchor()
            assertTrue(r.width > 0f && kotlin.math.abs(r.width - a.width) < 0.5f, "rect $r for anchor $a")
            toolbar.copy!!.invoke()
            waitForIdle()
            assertEquals("some", f.clipboard.text)
            assertEquals(TextToolbarStatus.Hidden, toolbar.status, "the toolbar stayed after Copy")
        }
    }
}

private fun dev.supermux.editor.core.Rope.lineText(n: Int): String {
    val from = lineStart(n)
    val to = if (n + 1 < lineCount) lineStart(n + 1) - 1 else length
    return slice(from, to)
}
