package dev.supermux.editor.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.decorationsFacet
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EditorSurfaceTest {
    private val tenK = (0 until 10_000).joinToString("\n") { "line $it: some text to draw" }

    @Test fun onlyTheVisibleLinesAreMeasured() = editorTest(EditorState.create(tenK)) { f ->
        val layouts = f.geometry.layouts
        val visible = (300 / layouts.lineHeightPx).toInt() + 1
        assertTrue(layouts.measureCount in visible..visible + 2 * EditorDefaults.OVERSCAN_LINES + 2, "measured ${layouts.measureCount} lines of 10k")
        assertEquals(0, f.controller.drawnLines.first)
    }

    @Test fun scrollingMovesTheDrawnLines() = editorTest(EditorState.create(tenK)) { f ->
        val lh = f.geometry.layouts.lineHeightPx
        f.controller.scroll.scrollTo(y = 5000 * lh)
        waitForIdle()
        val drawn = f.controller.drawnLines
        assertTrue(5000 in drawn && drawn.first >= 5000 - EditorDefaults.OVERSCAN_LINES, "drew $drawn")
        // The viewport reported to the host is those lines' text.
        val vp = f.view.viewport.value
        assertEquals(f.view.state.doc.lineStart(drawn.first), vp.first)
    }

    @Test fun theViewportIsReportedToTheHost() {
        val seen = ArrayList<IntRange>()
        editorTest(EditorState.create(tenK), onViewport = { seen += it }) { f ->
            waitForIdle()
            assertTrue(seen.isNotEmpty(), "onViewport never ran")
            assertEquals(f.view.viewport.value, seen.last())
            assertEquals(0, seen.last().first)
        }
    }

    @Test fun everyRepaintIsReportedToTheHost() {
        var paints = 0
        editorTest(EditorState.create("abc"), onPaint = { paints++ }) { f ->
            val before = paints
            assertTrue(before > 0, "the first paint was not reported")
            f.view.dispatch(dev.supermux.editor.core.TransactionSpec(changes = listOf(dev.supermux.editor.core.ChangeSpec(3, 3, "d"))))
            waitForIdle()
            assertTrue(paints > before, "the repaint after an edit was not reported")
        }
    }

    @Test fun theCursorIsPaintedInTheCursorColour() = editorTest(EditorState.create("hello world\nsecond", EditorSelection.cursor(6))) { f ->
        f.view.focused = true
        waitForIdle()
        val pixels = onNodeWithTag(EDITOR_TAG).captureToImage().toPixelMap()
        val caret = f.controller.caretRectOnScreen(6)
        val x = ((caret.left + caret.right) / 2).toInt()
        val y = ((caret.top + caret.bottom) / 2).toInt()
        assertClose(f.theme!!.cursor, pixels[x, y], "pixel under the cursor at ($x, $y)")
        // Unfocused: no caret.
        f.view.focused = false
        waitForIdle()
        val after = onNodeWithTag(EDITOR_TAG).captureToImage().toPixelMap()
        assertTrue(!close(f.theme!!.cursor, after[x, y]), "a caret was drawn while unfocused")
    }

    @Test fun marksFromTheDecorationsFacetColourTheirText() {
        val text = "plain KEYWORD plain"
        val marks = decorationsFacet.of(RangeSet.of(listOf(Ranged(6, 13, Decoration.Mark(setOf("tok-keyword"))))))
        editorTest(EditorState.create(text, extensions = marks), theme = { it.copy(fontSizeSp = 40f) }) { f ->
            val theme = f.theme!!
            val keyword = theme.tokens.getValue("tok-keyword").color
            val pixels = onNodeWithTag(EDITOR_TAG).captureToImage().toPixelMap()
            fun count(from: Int, to: Int, c: Color): Int {
                val a = f.controller.caretRectOnScreen(from)
                val b = f.controller.caretRectOnScreen(to)
                var n = 0
                for (x in a.left.toInt() until b.left.toInt()) for (y in a.top.toInt() until a.bottom.toInt()) if (close(c, pixels[x, y])) n++
                return n
            }
            assertTrue(count(6, 13, keyword) > 50, "the marked word is not in the keyword colour")
            assertEquals(0, count(0, 5, keyword), "unmarked text took the keyword colour")
            assertTrue(count(0, 5, theme.foreground) > 20, "unmarked text is not in the foreground colour")
        }
    }

    @Test fun selectionsAndTheCurrentLineArePainted() = editorTest(EditorState.create("aaaa\nbbbb\ncccc", EditorSelection.single(1, 7))) { f ->
        val theme = f.theme!!
        val pixels = onNodeWithTag(EDITOR_TAG).captureToImage().toPixelMap()
        val inSelection = f.controller.caretRectOnScreen(2)
        // A selection's background shows between the glyphs (sample just above the text's baseline area).
        var hits = 0
        for (x in inSelection.left.toInt() - 3..inSelection.left.toInt() + 3) {
            val p = pixels[x, inSelection.top.toInt() + 1]
            if (close(theme.selection.compositeOver(theme.background), p)) hits++
        }
        assertTrue(hits > 0, "no selection colour at the selected text")
    }

    private fun close(a: Color, b: Color) = abs(a.red - b.red) < 0.06f && abs(a.green - b.green) < 0.06f && abs(a.blue - b.blue) < 0.06f
    private fun assertClose(expected: Color, actual: Color, what: String) = assertTrue(close(expected, actual), "$what: expected $expected, got $actual")
}

private fun Color.compositeOver(bg: Color): Color = Color(
    red = red * alpha + bg.red * (1 - alpha),
    green = green * alpha + bg.green * (1 - alpha),
    blue = blue * alpha + bg.blue * (1 - alpha),
)
