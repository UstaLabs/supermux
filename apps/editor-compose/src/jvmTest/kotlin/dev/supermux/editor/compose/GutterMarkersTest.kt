package dev.supermux.editor.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.GutterMarker
import dev.supermux.editor.core.Prec
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.gutterMarkersFacet
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GutterMarkersTest {
    private val text = (0 until 40).joinToString("\n") { "line $it" }
    private fun at(state: EditorState, line: Int) = state.doc.lineStart(line)

    private fun lint(vararg lines: Int, state: EditorState = EditorState.create(text)) =
        lines.map { Ranged(at(state, it), at(state, it), GutterMarker("lint", "lint-error", "error on line ${it + 1}")) }

    private fun SurfaceFixture.drawn() = assertNotNull(controller.frame).markers

    @Test fun markersDrawOnTheirLinesInTheirColumns() {
        val lintPlugin = RangePlugin("lint", gutterMarkersFacet, lint(2))
        val diffPlugin = RangePlugin("diff", gutterMarkersFacet, listOf(Ranged(at(EditorState.create(text), 5), at(EditorState.create(text), 5), GutterMarker("diff", "diff-add"))))
        editorTest(EditorState.create(text, extensions = extensionOf(lintPlugin.extension, diffPlugin.extension))) { f ->
            val markers = f.drawn()
            assertEquals(setOf("lint" to 2, "diff" to 5), markers.map { it.column to it.line }.toSet())
            val lintRect = markers.single { it.column == "lint" }.rect
            val numbers = f.controller.textLeft
            assertTrue(lintRect.right <= numbers, "the marker is outside the gutter: $lintRect")
            // Its row: the same y as line 2's text.
            assertEquals(f.controller.caretRectOnScreen(at(f.view.state, 2)).top, lintRect.top, 0.5f)
            val pixels = onNodeWithTag(EDITOR_TAG).captureToImage().toPixelMap()
            val red = f.theme!!.gutterMarkers.getValue("lint-error").color
            assertTrue(close(red, pixels[lintRect.center.x.toInt(), lintRect.center.y.toInt()]), "no lint dot drawn")
            // The same column one line down is empty.
            val below = lintRect.center.y.toInt() + f.controller.layouts.lineHeightPx.toInt()
            assertTrue(!close(red, pixels[lintRect.center.x.toInt(), below]), "a dot on a line without a marker")
            val add = markers.single { it.column == "diff" }.rect
            val green = f.theme!!.gutterMarkers.getValue("diff-add").color
            assertTrue(close(green, pixels[add.center.x.toInt(), add.center.y.toInt()]), "no diff bar drawn")
        }
    }

    @Test fun markersFollowEdits() {
        val lintPlugin = RangePlugin("lint", gutterMarkersFacet, lint(5))
        editorTest(EditorState.create(text, extensions = lintPlugin.extension)) { f ->
            assertEquals(listOf(5), f.drawn().map { it.line })
            f.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "a\nb\nc\n"))))
            waitForIdle()
            assertEquals(listOf(8), f.drawn().map { it.line }, "the marker did not move with its line")
            // Its line deleted: gone.
            val st = f.view.state
            f.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(st.doc.lineStart(7), st.doc.lineStart(9)))))
            waitForIdle()
            assertEquals(emptyList<Any>(), f.drawn().map { it.line })
        }
    }

    @Test fun aClickOnAMarkerReportsItsLineAndMovesNoCaret() {
        val lintPlugin = RangePlugin("lint", gutterMarkersFacet, lint(3, 6))
        val clicks = ArrayList<Triple<String, Int, String?>>()
        editorTest(EditorState.create(text, EditorSelection.cursor(0), extensions = lintPlugin.extension)) { f ->
            f.view.onGutterClick = { column, line, marker -> clicks += Triple(column, line, marker?.kind) }
            val rect = f.drawn().single { it.line == 6 }.rect
            onNodeWithTag(EDITOR_TAG).performMouseInput { click(rect.center) }
            waitForIdle()
            assertEquals(listOf<Triple<String, Int, String?>>(Triple("lint", 6, "lint-error")), clicks)
            assertEquals(EditorSelection.cursor(0), f.view.state.selection, "a gutter click moved the caret")
            // A finger too.
            val three = f.drawn().single { it.line == 3 }.rect
            onNodeWithTag(EDITOR_TAG).performTouchInput { click(three.center) }
            waitForIdle()
            assertEquals(Triple<String, Int, String?>("lint", 3, "lint-error"), clicks.last())
            assertEquals(EditorSelection.cursor(0), f.view.state.selection)
        }
    }

    @Test fun aPluginHearsGutterClicksThroughItsFacet() {
        val lintPlugin = RangePlugin("lint", gutterMarkersFacet, lint(1))
        val heard = ArrayList<Int>()
        val handler = gutterClickFacet.of(GutterClickHandler { _, column, line, _ -> if (column == "lint") { heard += line; true } else false })
        editorTest(EditorState.create(text, extensions = extensionOf(lintPlugin.extension, handler))) { f ->
            var hostHeard = 0
            f.view.onGutterClick = { _, _, _ -> hostHeard++ }
            onNodeWithTag(EDITOR_TAG).performMouseInput { click(f.drawn().single().rect.center) }
            waitForIdle()
            assertEquals(listOf(1), heard)
            assertEquals(0, hostHeard, "a click the plugin took reached the host too")
        }
    }

    @Test fun columnsKeepAStableOrderAndWidth() {
        val lintPlugin = RangePlugin("lint", gutterMarkersFacet)
        val diffPlugin = RangePlugin("diff", gutterMarkersFacet)
        // diff has the higher precedence: its column comes first whichever marker shows up first.
        editorTest(EditorState.create(text, extensions = extensionOf(Prec.high(diffPlugin.extension), lintPlugin.extension))) { f ->
            assertEquals(emptyList<Any>(), f.controller.gutterColumns.map { it.id })
            lintPlugin.replace(f.view, lint(1, state = f.view.state))
            waitForIdle()
            assertEquals(listOf("lint"), f.controller.gutterColumns.map { it.id })
            val st = f.view.state
            diffPlugin.replace(f.view, listOf(Ranged(at(st, 4), at(st, 4), GutterMarker("diff", "diff-remove"))))
            waitForIdle()
            assertEquals(listOf("diff", "lint"), f.controller.gutterColumns.map { it.id })
            val textLeft = f.controller.textLeft
            // Every diff marker gone: the column keeps its place, so the text does not shift.
            diffPlugin.replace(f.view, emptyList())
            waitForIdle()
            assertEquals(listOf("diff", "lint"), f.controller.gutterColumns.map { it.id })
            assertEquals(textLeft, f.controller.textLeft)
            // The theme's width for each.
            val cols = f.controller.gutterColumns
            val density = f.controller.densityValue
            assertEquals(f.theme!!.gutterColumns.getValue("diff").value * density, cols[0].width, 0.5f)
            assertTrue(abs(cols[1].x - (cols[0].x + cols[0].width)) < 0.5f, "columns overlap or leave a hole: $cols")
        }
    }

    @Test fun aMarkerWithATooltipIsExposedToScreenReaders() {
        val lintPlugin = RangePlugin("lint", gutterMarkersFacet, lint(2))
        val clicks = ArrayList<Int>()
        editorTest(EditorState.create(text, extensions = lintPlugin.extension)) { f ->
            f.view.onGutterClick = { _, line, _ -> clicks += line }
            val node = onNodeWithContentDescription("error on line 3", useUnmergedTree = true)
            node.assertExists()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitForIdle()
            assertEquals(listOf(2), clicks)
        }
    }

    private fun close(a: Color, b: Color) = abs(a.red - b.red) < 0.08f && abs(a.green - b.green) < 0.08f && abs(a.blue - b.blue) < 0.08f
}
