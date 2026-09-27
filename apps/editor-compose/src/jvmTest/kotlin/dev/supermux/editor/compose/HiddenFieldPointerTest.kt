package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ahmet's iPhone crash (captured live: `IllegalArgumentException: start and end cannot be negative`
 * in `TransformedTextFieldState.placeCursorBeforeCharAt(-1)` from `moveCaretByLongPress`): a long
 * press on an empty line or a line's end reached the HIDDEN field's own touch-selection handler,
 * because the field sits at the caret and its touch target is expanded to 48 dp. The field must
 * never take a pointer: the editor's gestures handle every touch, and the field's selection is the
 * editor's to set.
 */
@OptIn(ExperimentalTestApi::class)
class HiddenFieldPointerTest {
    private val text = "abc\n\nlonger line here\nend"

    private fun ComposeUiTest.fieldSelection() = onNode(hasEditorField()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)
    private fun ComposeUiTest.hasPaste() = runCatching { onNodeWithText("Paste", useUnmergedTree = true).assertExists() }.isSuccess

    private fun ComposeUiTest.longPressOnTheField(f: SurfaceFixture, caret: Int, nudge: Offset) {
        f.clipboard.text = "CLIP"
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(f.controller.caretRectOnScreen(caret).center) }
        waitForIdle()
        assertEquals(EditorSelection.cursor(caret), f.view.state.selection)
        val before = fieldSelection()
        // Right where the hidden field is: at the caret (the field's top-left, inside the caret's
        // line), and within its 48 dp touch target.
        val at = f.controller.caretRectOnScreen(caret).topLeft + nudge
        val events = ArrayList<String>()
        f.view.addListener { events += "${it.annotation(dev.supermux.editor.core.Transaction.userEvent)}" }
        onNodeWithTag(EDITOR_TAG).performTouchInput { longClick(at) }
        waitForIdle()
        assertTrue(events.none { it == "select" }, "the hidden field moved the selection: $events")
        assertEquals(EditorSelection.cursor(caret), f.view.state.selection, "the editor did not keep the caret")
        assertEquals(TouchHandles.CURSOR, f.controller.handles)
        assertTrue(hasPaste(), "no Paste menu: the editor did not handle the long press")
        assertEquals(before, fieldSelection(), "the hidden field's own long-press handler moved its selection")
    }

    @Test fun aLongPressOnTheFieldOnAnEmptyLine() = editorTest(EditorState.create(text)) { f ->
        longPressOnTheField(f, f.view.state.doc.lineStart(1), Offset(1f, 5f))
    }

    @Test fun aLongPressOnTheFieldAtALineEnd() = editorTest(EditorState.create(text)) { f ->
        longPressOnTheField(f, f.view.state.doc.lineStart(1) - 1, Offset(f.controller.layouts.charWidthPx, 5f)) // just past "abc"
    }

    @Test fun aLongPressNearTheFieldWithinItsTouchTarget() = editorTest(EditorState.create(text)) { f ->
        val px = with(androidx.compose.ui.unit.Density(f.controller.densityValue)) { 12.dp.toPx() }
        longPressOnTheField(f, f.view.state.doc.lineStart(1), Offset(px, px))
    }

    @Test fun noPointerEverReachesTheHiddenField() {
        var reached = 0
        editorTest(EditorState.create(text), fieldPointerSpy = { reached++ }) { f ->
            val caret = f.view.state.doc.lineStart(1)
            onNodeWithTag(EDITOR_TAG).performTouchInput { click(f.controller.caretRectOnScreen(caret).center) }
            waitForIdle()
            val field = f.controller.caretRectOnScreen(caret).topLeft
            val px = with(androidx.compose.ui.unit.Density(f.controller.densityValue)) { 12.dp.toPx() }
            reached = 0
            // Inside the field's own 1 dp, and inside its expanded touch target.
            for (p in listOf(field + Offset(0.4f, 0.4f), field + Offset(px, px))) {
                onNodeWithTag(EDITOR_TAG).performTouchInput { longClick(p) }
                waitForIdle()
                onNodeWithTag(EDITOR_TAG).performTouchInput { click(p) }
                waitForIdle()
            }
            assertEquals(0, reached, "pointer events reached the hidden field (its own touch selection runs: the iPhone crash)")
        }
    }

    @Test fun anInteractiveChildOfTheSurfaceStillGetsPointers() {
        var got = 0
        val child: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) { awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); got++ } }
                },
            )
        }
        editorTest(EditorState.create(text), child = child) { f ->
            // Far from the caret (and so from the shield): the child is hit.
            onNodeWithTag(EDITOR_TAG).performTouchInput { click(Offset(300f, 250f)) }
            waitForIdle()
            assertTrue(got > 0, "the pointer shield took a pointer far from the hidden field")
            assertTrue(f.view.focused)
        }
    }
}
