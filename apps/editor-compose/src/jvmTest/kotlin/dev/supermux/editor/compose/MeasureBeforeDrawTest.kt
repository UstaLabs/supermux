package dev.supermux.editor.compose

import androidx.compose.ui.test.ExperimentalTestApi
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M3c Task 1: measuring, anchoring and every scroll write happen in the layout pass; the draw pass
 * only reads what the layout decided ([DrawGuard] fails any test that measures or scrolls in draw).
 */
@OptIn(ExperimentalTestApi::class)
class MeasureBeforeDrawTest {
    private val wrapped = (0 until 600).joinToString("\n") { if (it % 3 == 0) "long line $it " + "word ".repeat(60) else "line $it" }

    @Test fun theGuardFailsAScrollWriteInsideADraw() {
        val scroll = EditorScrollState()
        DrawGuard.strict = true
        try {
            assertFailsWith<IllegalStateException> { DrawGuard.drawing { scroll.scrollTo(y = 10f) } }
        } finally {
            DrawGuard.strict = false
        }
    }

    @Test fun scrollingUpThroughUnmeasuredWrappedLinesNeverWritesTheScrollInDraw() =
        editorTest(EditorState.create(wrapped), lineWrap = true) { f ->
            // The fixture runs with DrawGuard.strict: a measure or scroll write in draw throws.
            f.controller.scroll.scrollTo(y = 1e9f)
            waitForIdle()
            repeat(40) {
                f.controller.scroll.scrollBy(0f, -11f)
                waitForIdle()
            }
            assertTrue(f.controller.scroll.y > 0f)
        }

    @Test fun anAnchorCorrectionIsPaintedInTheSameFrameAsTheScroll() {
        var paints = 0
        var painted = Float.NaN
        var ref = 0
        var fixture: SurfaceFixture? = null
        editorTest(EditorState.create(wrapped), lineWrap = true, onPaint = {
            val f = fixture
            if (f != null) { paints++; painted = f.geometry.lineTop(ref) - f.controller.frameScrollY }
        }) { f ->
            f.controller.scroll.scrollTo(y = 1e9f)
            waitForIdle()
            fixture = f
            val d = 7f
            repeat(30) { step ->
                ref = f.geometry.heights.lineAt(f.controller.scroll.y) + 1
                val before = f.geometry.lineTop(ref) - f.controller.scroll.y
                paints = 0
                val passes = f.controller.layoutPasses
                f.controller.scroll.scrollBy(0f, -d)
                waitForIdle()
                assertEquals(1, paints, "step $step: ${paints} paints for one scroll (an anchor correction must not take a frame of its own)")
                assertEquals(1, f.controller.layoutPasses - passes, "step $step: layout passes for one scroll")
                assertEquals(before + d, painted, 0.5f, "step $step: the painted frame did not show the anchored position")
            }
        }
    }

    @Test fun anIdleEditorDoesNotKeepLayingOut() = editorTest(EditorState.create(wrapped), lineWrap = true) { f ->
        f.controller.scroll.scrollTo(y = 1e9f)
        waitForIdle()
        f.controller.scroll.scrollBy(0f, -300f)
        waitForIdle()
        val passes = f.controller.layoutPasses
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals(passes, f.controller.layoutPasses, "the surface laid out again with nothing changed")
    }

    @Test fun anEditIsLaidOutAndPaintedOnce() {
        var paints = 0
        editorTest(EditorState.create("abc\ndef"), onPaint = { paints++ }) { f ->
            val passes = f.controller.layoutPasses
            paints = 0
            f.view.dispatch(TransactionSpec(changes = listOf(dev.supermux.editor.core.ChangeSpec(3, 3, "x")), selection = EditorSelection.cursor(4)))
            waitForIdle()
            assertEquals(1, f.controller.layoutPasses - passes)
            assertEquals(1, paints)
        }
    }
}
