package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.TransactionSpec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TouchHandlesTest {
    private val text = (0 until 200).joinToString("\n") { "line $it has some words" }

    private fun SurfaceFixture.at(offset: Int): Offset = controller.caretRectOnScreen(offset).center
    private fun SurfaceFixture.tip(offset: Int): Offset = controller.caretRectOnScreen(offset).bottomLeft
    private fun SurfaceFixture.spot(kind: HandleKind) = controller.handleSpots().single { it.kind == kind }

    /** Long-press "some" on line 1 (offsets 11..15 of the line). */
    private fun androidx.compose.ui.test.ComposeUiTest.longPressSome(f: SurfaceFixture): Int {
        val line = f.view.state.doc.lineStart(1)
        onNodeWithTag(EDITOR_TAG).performTouchInput { longClick(f.at(line + 12)) }
        waitForIdle()
        return line
    }

    /** A finger on [kind]'s handle, dragged so its tip lands on [target]'s caret. */
    private fun androidx.compose.ui.test.ComposeUiTest.dragHandle(f: SurfaceFixture, kind: HandleKind, target: Int) {
        val s = f.spot(kind)
        val to = s.body + (f.tip(target) - s.tip)
        onNodeWithTag(EDITOR_TAG).performTouchInput {
            down(s.body)
            moveTo(s.body + (to - s.body) / 2f)
            moveTo(to)
            up()
        }
        waitForIdle()
    }

    @Test fun aLongPressSelectsAWordWithTwoHandles() = editorTest(EditorState.create(text)) { f ->
        val line = longPressSome(f)
        assertEquals(SelectionRange(line + 11, line + 15), f.view.state.selection.main)
        assertEquals(TouchHandles.SELECTION, f.controller.handles)
        val spots = f.controller.handleSpots()
        assertEquals(listOf(HandleKind.START, HandleKind.END), spots.map { it.kind })
        // Each drop points at its end's caret bottom.
        assertTrue((spots[0].tip - f.tip(line + 11)).getDistance() < 0.5f)
        assertTrue((spots[1].tip - f.tip(line + 15)).getDistance() < 0.5f)
    }

    @Test fun handleTargetsAreAtLeast48dp() = editorTest(EditorState.create(text)) { f ->
        longPressSome(f)
        val min = EditorTouch.MIN_TOUCH_DP * f.controller.densityValue
        for (s in f.controller.handleSpots()) {
            assertTrue(s.touch.width >= min - 0.01f && s.touch.height >= min - 0.01f, "${s.kind} target ${s.touch.size}")
        }
    }

    @Test fun draggingTheEndHandleExtendsAndTheStartHandleShrinks() = editorTest(EditorState.create(text)) { f ->
        val line = longPressSome(f)
        dragHandle(f, HandleKind.END, line + 21) // to the end of "words"
        assertEquals(SelectionRange(line + 11, line + 21), f.view.state.selection.main)
        dragHandle(f, HandleKind.START, line + 13)
        assertEquals(SelectionRange(line + 21, line + 13), f.view.state.selection.main, "the end stays the anchor")
        assertEquals(TouchHandles.SELECTION, f.controller.handles, "a handle drag keeps the handles")
        assertEquals(0f, f.controller.scroll.y, "a handle drag never scrolls")
    }

    @Test fun aHandleDraggedDownAcrossLinesExtendsOverThem() = editorTest(EditorState.create(text)) { f ->
        val line = longPressSome(f)
        val target = f.view.state.doc.lineStart(4) + 7
        dragHandle(f, HandleKind.END, target)
        assertEquals(SelectionRange(line + 11, target), f.view.state.selection.main)
    }

    @Test fun aHandleHeldPastTheBottomEdgeAutoScrolls() = editorTest(EditorState.create(text)) { f ->
        val line = longPressSome(f)
        val s = f.spot(HandleKind.END)
        // The auto-scroll asks for a frame while the finger is held: drive the clock by hand.
        mainClock.autoAdvance = false
        onNodeWithTag(EDITOR_TAG).performTouchInput { down(s.body); moveTo(Offset(s.body.x, 330f)) }
        mainClock.advanceTimeBy(1000)
        assertTrue(f.controller.scroll.y > 0f, "no auto-scroll")
        val sel = f.view.state.selection.main
        assertEquals(line + 11, sel.anchor, "the start stays put")
        assertTrue(f.view.state.doc.lineIndexAt(sel.head) > 16, "the end did not follow the scroll")
        onNodeWithTag(EDITOR_TAG).performTouchInput { up() }
        mainClock.advanceTimeBy(100)
        val y = f.controller.scroll.y
        mainClock.advanceTimeBy(500)
        assertEquals(y, f.controller.scroll.y, "auto-scroll outlived the drag")
        mainClock.autoAdvance = true
        waitForIdle()
    }

    @Test fun theHandlesFollowTheSelectionThroughScrollAndEdits() = editorTest(EditorState.create(text)) { f ->
        val line = longPressSome(f)
        val before = f.spot(HandleKind.START).tip
        val lh = f.controller.layouts.lineHeightPx
        f.controller.scroll.scrollBy(0f, lh * 0.5f)
        waitForIdle()
        val scrolled = f.spot(HandleKind.START).tip
        assertTrue(abs(scrolled.y - (before.y - lh * 0.5f)) < 0.5f, "after scrolling: $before -> $scrolled")
        // An edit before the selection on its line: the handles ride along with their text.
        f.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(line, line, "xx"))))
        waitForIdle()
        assertEquals(SelectionRange(line + 13, line + 17), f.view.state.selection.main)
        val edited = f.spot(HandleKind.START).tip
        assertTrue(abs(edited.x - (scrolled.x + 2 * f.controller.layouts.charWidthPx)) < 0.5f, "after the edit: $scrolled -> $edited")
        assertTrue((edited - f.tip(line + 13)).getDistance() < 0.5f)
    }

    @Test fun aTapInsideTheSelectionKeepsItOutsideCollapsesIt() = editorTest(EditorState.create(text)) { f ->
        val line = longPressSome(f)
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(f.at(line + 13)) }
        waitForIdle()
        assertEquals(SelectionRange(line + 11, line + 15), f.view.state.selection.main, "a tap inside collapsed the selection")
        val elsewhere = f.view.state.doc.lineStart(3) + 4
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(f.at(elsewhere)) }
        waitForIdle()
        assertEquals(EditorSelection.cursor(elsewhere), f.view.state.selection)
        assertEquals(TouchHandles.CURSOR, f.controller.handles, "a tap gives the caret its handle")
    }

    @Test fun theCaretHandleMovesTheCaretAndTypingHidesIt() = editorTest(EditorState.create(text)) { f ->
        val start = f.view.state.doc.lineStart(2) + 3
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(f.at(start)) }
        waitForIdle()
        assertEquals(TouchHandles.CURSOR, f.controller.handles)
        val target = f.view.state.doc.lineStart(2) + 16
        dragHandle(f, HandleKind.CURSOR, target)
        assertEquals(EditorSelection.cursor(target), f.view.state.selection)
        f.view.typeText("z")
        waitForIdle()
        assertEquals(TouchHandles.NONE, f.controller.handles, "typing hides the caret's handle")
    }

    @Test fun aMouseClickHidesTheHandles() = editorTest(EditorState.create(text)) { f ->
        longPressSome(f)
        onNodeWithTag(EDITOR_TAG).performMouseInput { click(f.at(f.view.state.doc.lineStart(5))) }
        waitForIdle()
        assertEquals(TouchHandles.NONE, f.controller.handles)
        assertTrue(f.controller.handleSpots().isEmpty())
    }

    @Test fun aKeyboardSelectionChangeHidesThem() = editorTest(EditorState.create(text)) { f ->
        longPressSome(f)
        DefaultCommands.selectRight.run(f.view)
        waitForIdle()
        assertEquals(TouchHandles.NONE, f.controller.handles)
    }

    @Test fun aLongPressThenDragExtendsByWords() = editorTest(EditorState.create(text)) { f ->
        val line = f.view.state.doc.lineStart(1)
        onNodeWithTag(EDITOR_TAG).performTouchInput {
            down(f.at(line + 12))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 50)
            moveTo(f.at(line + 18))
            up()
        }
        waitForIdle()
        assertEquals(SelectionRange(line + 11, line + 21), f.view.state.selection.main, "extended to the whole word 'words'")
    }

    @Test fun theHandlesArePaintedInTheAccentColour() = editorTest(EditorState.create(text)) { f ->
        longPressSome(f)
        val s = f.spot(HandleKind.END)
        val px = onNodeWithTag(EDITOR_TAG).captureToImage().toPixelMap()[s.body.x.toInt(), s.body.y.toInt()]
        val want = f.theme!!.selectionHandle
        assertTrue(abs(px.red - want.red) < 0.06f && abs(px.green - want.green) < 0.06f && abs(px.blue - want.blue) < 0.06f, "handle pixel $px, want $want")
    }

    @Test fun aDoubleTapSelectsTheWordWithHandlesAndMenuATripleTapTheLine() = editorTest(EditorState.create(text)) { f ->
        val line = f.view.state.doc.lineStart(2)
        val p = f.at(line + 12) // inside "some"
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(p) }
        waitForIdle()
        assertEquals(EditorSelection.cursor(line + 12), f.view.state.selection, "the first tap does not place the caret at once")
        onNodeWithTag(EDITOR_TAG).performTouchInput { advanceEventTime(80); click(p) }
        waitForIdle()
        assertEquals(SelectionRange(line + 11, line + 15), f.view.state.selection.main)
        assertEquals(TouchHandles.SELECTION, f.controller.handles)
        assertTrue(f.controller.menuShown, "no menu after a double tap")
        assertTrue(f.keyboard.shows.get() >= 1)
        onNodeWithTag(EDITOR_TAG).performTouchInput { advanceEventTime(80); click(p) }
        waitForIdle()
        assertEquals(SelectionRange(line, f.view.state.doc.lineStart(3)), f.view.state.selection.main, "a triple tap does not select the line")
    }

    @Test fun twoSlowTapsAreTwoSingleTaps() = editorTest(EditorState.create(text)) { f ->
        val line = f.view.state.doc.lineStart(2)
        val p = f.at(line + 12)
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(p) }
        waitForIdle()
        onNodeWithTag(EDITOR_TAG).performTouchInput { advanceEventTime(viewConfiguration.doubleTapTimeoutMillis + 200); click(p) }
        waitForIdle()
        assertEquals(EditorSelection.cursor(line + 12), f.view.state.selection)
        assertEquals(TouchHandles.CURSOR, f.controller.handles)
    }

    @Test fun twoTapsFarApartAreTwoSingleTaps() = editorTest(EditorState.create(text)) { f ->
        val a = f.view.state.doc.lineStart(2) + 12
        val b = f.view.state.doc.lineStart(6) + 3
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(f.at(a)) }
        onNodeWithTag(EDITOR_TAG).performTouchInput { advanceEventTime(80); click(f.at(b)) }
        waitForIdle()
        assertEquals(EditorSelection.cursor(b), f.view.state.selection)
    }
}
