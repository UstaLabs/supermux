package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ahmet's device report: after switching tabs a few times the caret vanished while typing still
 * worked. A host that shows another document gives the SAME `Editor` a new view: the hidden field
 * keeps the focus, so no focus event arrives for the new view.
 */
@OptIn(ExperimentalTestApi::class)
class DocumentSwitchTest {
    @Test fun theCaretStaysAfterSwitchingDocumentsManyTimes() = runComposeUiTest {
        val views = (0 until 3).map { i -> EditorView(EditorState.create("doc $i\nsecond line $i", EditorSelection.cursor(2))) }
        var shown by mutableStateOf(0)
        val clipboard = FakeClipboard()
        setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides RecordingKeyboard(), LocalEditorCursorBlink provides false) {
                Box(Modifier.size(400.dp, 300.dp)) {
                    Editor(views[shown], Modifier.fillMaxSize().testTag(EDITOR_TAG), clipboard = clipboard)
                }
            }
        }
        waitForIdle()
        onNode(hasEditorField()).requestFocus()
        waitForIdle()
        assertTrue(views[0].focused)
        for (n in 1..7) {
            shown = n % 3
            waitForIdle()
            val v = views[shown]
            assertTrue(v.focused, "switch $n: the shown view does not know it has the focus (no caret is painted)")
            assertTrue(views.filterIndexed { i, _ -> i != shown }.none { it.focused }, "switch $n: a hidden view still thinks it is focused")
            // The caret is painted where the view's selection is.
            val c = v.surface as EditorController
            val r = c.caretRectOnScreen(v.state.selection.main.head)
            val px = onNodeWithTag(EDITOR_TAG).captureToImage().toPixelMap()[r.left.toInt(), r.center.y.toInt()]
            val want = c.theme!!.cursor
            assertTrue(abs(px.red - want.red) < 0.08f && abs(px.green - want.green) < 0.08f, "switch $n: no caret at the selection ($px)")
        }
        // Typing goes where the caret is drawn.
        val v = views[shown]
        val head = v.state.selection.main.head
        onNode(hasEditorField()).performTextInput("Z")
        waitForIdle()
        assertEquals("Z", v.state.doc.slice(head, head + 1), "typing did not land at the caret")
    }

    @Test fun theBlinkRestartsAfterASwitch() = runComposeUiTest {
        val a = EditorView(EditorState.create("alpha"))
        val b = EditorView(EditorState.create("beta"))
        var second by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides RecordingKeyboard()) {
                Box(Modifier.size(300.dp, 200.dp)) { Editor(if (second) b else a, Modifier.fillMaxSize().testTag(EDITOR_TAG), clipboard = FakeClipboard()) }
            }
        }
        waitForIdle()
        onNode(hasEditorField()).requestFocus()
        mainClock.autoAdvance = false
        second = true
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val c = b.surface as EditorController
        val phases = HashSet<Boolean>()
        repeat(12) { mainClock.advanceTimeBy(EditorDefaults.BLINK_MILLIS / 3); phases += c.cursorOn }
        assertEquals(setOf(true, false), phases, "the caret of the switched-to view does not blink")
        mainClock.autoAdvance = true
    }
}

