package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import dev.supermux.editor.core.EditorState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ZoomTest {
    private val text = (0 until 300).joinToString("\n") { "line $it has some words to wrap around the narrow editor" }
    private val mod = if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft

    private fun androidx.compose.ui.test.ComposeUiTest.chord(key: Key) {
        onNode(hasSetTextAction()).performKeyInput { withKeyDown(mod) { pressKey(key) } }
        waitForIdle()
    }

    @Test fun theKeysStepOneSizeAndStayWithinTheLimits() {
        val reported = ArrayList<Float>()
        editorTest(EditorState.create(text), onFontSize = { reported += it }) { f ->
            onNode(hasSetTextAction()).requestFocus()
            waitForIdle()
            assertEquals(EditorZoom.DEFAULT, f.view.effectiveFontSize)
            chord(Key.Equals)
            assertEquals(14f, f.view.fontSize)
            chord(Key.Minus)
            chord(Key.Minus)
            assertEquals(12f, f.view.fontSize)
            repeat(10) { chord(Key.Minus) }
            assertEquals(EditorZoom.MIN, f.view.fontSize, "below the minimum")
            repeat(30) { chord(Key.Equals) }
            assertEquals(EditorZoom.MAX, f.view.fontSize, "above the maximum")
            chord(Key.Zero)
            assertNull(f.view.fontSize, "Mod 0 goes back to the theme's size")
            assertEquals(EditorZoom.DEFAULT, f.view.effectiveFontSize)
            assertEquals(listOf(14f, 13f, 12f), reported.take(3))
            assertEquals(EditorZoom.DEFAULT, reported.last())
            // The surface draws at the zoomed size: a bigger line and cell.
            val lh = f.controller.layouts.lineHeightPx
            chord(Key.Equals); chord(Key.Equals); chord(Key.Equals)
            assertTrue(f.controller.layouts.lineHeightPx > lh, "the layout did not follow the zoom")
        }
    }

    @Test fun aPinchScalesTheFont() = editorTest(EditorState.create(text)) { f ->
        onNodeWithTag(EDITOR_TAG).performTouchInput {
            pinch(Offset(180f, 150f), Offset(120f, 150f), Offset(220f, 150f), Offset(300f, 150f), durationMillis = 400)
        }
        waitForIdle()
        val size = f.view.effectiveFontSize
        // 40 px apart -> 180 px apart: 4.5x, clamped to the maximum.
        assertEquals(EditorZoom.MAX, size, "spread")
        onNodeWithTag(EDITOR_TAG).performTouchInput {
            pinch(Offset(100f, 150f), Offset(150f, 150f), Offset(300f, 150f), Offset(250f, 150f), durationMillis = 400)
        }
        waitForIdle()
        // 200 -> 100 px: half the size.
        assertTrue(abs(f.view.effectiveFontSize - EditorZoom.MAX / 2) <= 0.5f, "pinched to ${f.view.effectiveFontSize}")
        assertEquals(0f, f.controller.scroll.y, "a pinch scrolled")
    }

    private fun anchorKept(lineWrap: Boolean) = editorTest(EditorState.create(text), lineWrap = lineWrap) { f ->
        val doc = f.view.state.doc
        val line = 120
        f.view.restoreScroll(EditorScrollPosition(doc.lineStart(line)))
        waitForIdle()
        for (size in listOf(18f, 24f, 11f, 15.5f)) {
            f.view.zoomTo(size)
            waitForIdle()
            val lh = f.controller.layouts.lineHeightPx
            val top = f.controller.caretRectOnScreen(doc.lineStart(line)).top
            assertTrue(abs(top) <= lh, "at $size sp (wrap $lineWrap) line $line is at y $top, line height $lh")
        }
    }

    @Test fun theTopLineStaysPutWhileZooming() = anchorKept(lineWrap = false)

    @Test fun theTopLineStaysPutWhileZoomingWrapped() = anchorKept(lineWrap = true)
}
