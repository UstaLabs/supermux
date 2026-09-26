package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What hosts (M3b's menus, M3c's linked views, M5's tabs) build on. */
@OptIn(ExperimentalTestApi::class)
class PublicApiTest {
    private val lines = (0 until 2000).joinToString("\n") { "line $it" }

    @Test fun theScrollPositionIsSavedAndRestoredByDocumentPosition() = editorTest(EditorState.create(lines)) { f ->
        val lh = f.geometry.layouts.lineHeightPx
        f.view.scrollState.scrollTo(y = 500 * lh + 5)
        waitForIdle()
        val saved = f.view.scrollPosition
        assertEquals(f.view.state.doc.lineStart(500), saved.anchor)
        assertEquals(5f, saved.offsetPx, 0.5f)
        f.view.scrollState.scrollTo(y = 0f)
        waitForIdle()
        f.view.restoreScroll(saved)
        waitForIdle()
        assertEquals(500 * lh + 5, f.view.scrollState.y, 0.5f)
    }

    @Test fun aScrollPositionSetBeforeTheFirstPaintIsApplied() {
        val view = EditorView(EditorState.create(lines))
        view.restoreScroll(EditorScrollPosition(anchor = view.state.doc.lineStart(300), offsetPx = 0f))
        runComposeUiTest {
            setContent { CompositionLocalProvider(LocalEditorCursorBlink provides false) { Editor(view, Modifier.size(300.dp, 200.dp)) } }
            waitForIdle()
            val lh = (view.surface as EditorController).layouts.lineHeightPx
            assertEquals(300 * lh, view.scrollState.y, 0.5f)
        }
    }

    @Test fun focusRaisesTheKeyboardOnlyWhenAsked() = editorTest(EditorState.create("abc")) { f ->
        assertTrue(f.view.focus())
        waitForIdle()
        assertTrue(f.view.focused)
        assertEquals(0, f.keyboard.shows.get(), "a programmatic focus raised the keyboard")
        f.view.focus(showKeyboard = true)
        waitForIdle()
        assertEquals(1, f.keyboard.shows.get())
    }

    @Test fun coordsAtPosGiveTheCaretRectInTheSurface() = editorTest(EditorState.create("abc\ndef")) { f ->
        val r = assertNotNull(f.view.coordsAtPos(5))
        assertEquals(f.controller.caretRectOnScreen(5), r)
        assertTrue(r.top > 0f && r.left > f.controller.textLeft)
        assertNull(EditorView(EditorState.create("x")).coordsAtPos(0), "not composed: no coordinates")
    }

    @Test fun twoEditorsCanShareOneScrollState() = runComposeUiTest {
        val a = EditorView(EditorState.create(lines))
        val b = EditorView(EditorState.create(lines))
        val shared = EditorScrollState()
        setContent {
            CompositionLocalProvider(LocalEditorCursorBlink provides false) {
                Row {
                    Editor(a, Modifier.size(300.dp, 200.dp).testTag("a"), scrollState = shared)
                    Editor(b, Modifier.size(300.dp, 200.dp).testTag("b"), scrollState = shared)
                }
            }
        }
        waitForIdle()
        onNodeWithTag("a").performMouseInput { scroll(5f) }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        assertTrue(shared.y > 0f, "the wheel did not scroll")
        assertEquals(a.scrollState, b.scrollState)
        assertEquals(a.viewport.value.first, b.viewport.value.first, "the two views show different lines")
    }
}
