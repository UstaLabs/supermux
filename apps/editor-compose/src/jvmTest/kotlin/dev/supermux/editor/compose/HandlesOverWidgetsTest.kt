package dev.supermux.editor.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The caret's handle hangs below the caret's row: over a widget right below that line it must still be drawn and grabbed. */
@OptIn(ExperimentalTestApi::class)
class HandlesOverWidgetsTest {
    private val text = (0 until 40).joinToString("\n") { "line $it has some text" }

    @Test fun aHandleOverAWidgetIsDrawnAboveItAndDragsTheCaret() {
        val st = EditorState.create(text)
        val under = st.doc.lineStart(3)
        var widgetClicks = 0
        val registry = WidgetRegistry().apply {
            register("box") { Box(Modifier.fillMaxWidth().height(120.dp).background(Color(0xFFFF00FF)).clickable { widgetClicks++ }) }
        }
        val plugin = RangePlugin("w", decorationsFacet, listOf(Ranged(under, under, Decoration.BlockWidget(WidgetKey("box", "b"), above = true) as Decoration)))
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = registry) { f ->
            val caretAt = st.doc.lineStart(2) + 6
            onNodeWithTag(EDITOR_TAG).performTouchInput { click(f.controller.caretRectOnScreen(caretAt).center) }
            waitForIdle()
            assertEquals(TouchHandles.CURSOR, f.controller.handles)
            val spot = f.controller.handleSpots().single()
            val widget = f.controller.frame!!.widgets.single().rect
            assertTrue(widget.contains(spot.body), "the test's handle is not over the widget: $spot vs $widget")
            // Drawn above the widget.
            val px = onNodeWithTag(EDITOR_TAG).captureToImage().toPixelMap()[spot.body.x.toInt(), spot.body.y.toInt()]
            val handle = f.theme!!.selectionHandle
            assertTrue(abs(px.red - handle.red) < 0.1f && abs(px.green - handle.green) < 0.1f && abs(px.blue - handle.blue) < 0.1f, "the handle is under the widget: $px")
            // Grabbed: dragging it moves the caret to line 0, and the widget saw no click.
            val lh = f.controller.layouts.lineHeightPx
            onNodeWithTag(EDITOR_TAG).performTouchInput {
                down(spot.body)
                moveTo(spot.body + Offset(0f, -lh * 2))
                moveTo(spot.body + Offset(0f, -lh * 2 - 1f))
                up()
            }
            waitForIdle()
            assertEquals(0, f.view.state.doc.lineIndexAt(f.view.state.selection.main.head), "the handle did not move the caret")
            assertEquals(0, widgetClicks, "the widget took the handle's press")
        }
    }
}
