package dev.supermux.editor.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.Tooltip
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.tooltipsFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tooltip layer: placement against the caret, flipping, strict tooltips, hover with its delay. */
@OptIn(ExperimentalTestApi::class)
class TooltipLayerTest {
    private val text = (0 until 60).joinToString("\n") { "line $it alpha beta" }

    /** A plugin showing [Tooltip]s from its own field. */
    private val show = StateEffectType<Tooltip?>("test.tooltip")
    private val field = StateField<Tooltip?>("test.tooltip", { null }, { v, tr ->
        var t = v?.let { it.copy(pos = tr.changes.mapPos(it.pos, 1)) }
        for (e in tr.effects) {
            e.valueIf(show)?.let { t = it }
            if (e.isOf(show) && e.value == null) t = null
            e.valueIf(Tooltip.dismissed)?.let { k -> if (t?.key == k) t = null }
        }
        t
    })
    private val plugin = extensionOf(field, tooltipsFacet.compute(FacetDep.field(field)) { it.field(field) })

    private fun registry() = WidgetRegistry().apply {
        register("tooltip:test") { Box(Modifier.size(120.dp, 40.dp).background(Color.Red).testTag("tip")) }
        register("tooltip:hover") { k -> Box(Modifier.size(80.dp, 30.dp).testTag("hover:${k.id}")) }
    }

    private val key = WidgetKey("tooltip:test", "t")

    private fun ComposeUiTest.tipRect(): androidx.compose.ui.geometry.Rect {
        val n = onNodeWithTag("tip").fetchSemanticsNode()
        return androidx.compose.ui.geometry.Rect(n.positionInRoot, androidx.compose.ui.geometry.Size(n.size.width.toFloat(), n.size.height.toFloat()))
    }

    private fun lineStart(n: Int) = text.split('\n').take(n).sumOf { it.length + 1 }

    @Test fun belowTheCaretsLineLeftAligned() {
        editorTest(EditorState.create(text, EditorSelection.cursor(lineStart(3) + 5), plugin), widgets = registry()) { f ->
            f.view.dispatch(TransactionSpec(effects = listOf(show.of(Tooltip(lineStart(3) + 5, key)))))
            waitForIdle()
            val caret = f.controller.caretRectOnScreen(lineStart(3) + 5)
            val r = tipRect()
            assertTrue(r.top >= caret.bottom, "below the line: $r vs $caret")
            assertTrue(r.top - caret.bottom < 8 * f.controller.densityValue)
            assertEquals(caret.left, r.left, 1f)
        }
    }

    @Test fun flipsAboveAtTheEditorsBottomAndBelowAtItsTop() {
        editorTest(EditorState.create(text, EditorSelection.cursor(0), plugin), heightDp = 300, widgets = registry()) { f ->
            val lastVisible = f.controller.drawnLines.last - EditorDefaults.OVERSCAN_LINES
            val pos = lineStart(lastVisible) + 2
            f.view.dispatch(TransactionSpec(effects = listOf(show.of(Tooltip(pos, key)))))
            waitForIdle()
            val caret = f.controller.caretRectOnScreen(pos)
            assertTrue(tipRect().bottom <= caret.top + 0.5f, "flipped above: ${tipRect()} vs $caret")
            // Asked above on the first line: no room, so below.
            f.view.dispatch(TransactionSpec(effects = listOf(show.of(Tooltip(2, key, above = true)))))
            waitForIdle()
            assertTrue(tipRect().top >= f.controller.caretRectOnScreen(2).bottom, "flipped below")
        }
    }

    @Test fun clampedToTheEditorsRightEdge() {
        val long = "x".repeat(40) + "\n" + text
        editorTest(EditorState.create(long, EditorSelection.cursor(0), plugin), widthDp = 300, widgets = registry()) { f ->
            val w = f.controller.viewportSize.width
            val pos = (0..40).last { f.controller.caretRectOnScreen(it).left < w - 20 * f.controller.densityValue }
            f.view.dispatch(TransactionSpec(effects = listOf(show.of(Tooltip(pos, key)))))
            waitForIdle()
            assertEquals(w, tipRect().right, 1f, "clamped to the right edge: ${tipRect()}")
        }
    }

    @Test fun aStrictTooltipHidesWhenItsPositionScrollsOutANonStrictOneStays() {
        editorTest(EditorState.create(text, EditorSelection.cursor(lineStart(2)), plugin), widgets = registry()) { f ->
            f.view.dispatch(TransactionSpec(effects = listOf(show.of(Tooltip(lineStart(2), key)))))
            waitForIdle()
            onNodeWithTag("tip").fetchSemanticsNode()
            runOnIdle { f.controller.scroll.scrollTo(0f, 40 * f.controller.layouts.lineHeightPx) }
            waitForIdle()
            assertEquals(0, onAllNodesWithTag("tip").fetchSemanticsNodes().size, "strict: hidden out of view")
            f.view.dispatch(TransactionSpec(effects = listOf(show.of(Tooltip(lineStart(2), key, strict = false)))))
            waitForIdle()
            assertTrue(tipRect().top >= 0f, "kept at the editor's top edge")
        }
    }

    @Test fun aPressOnATooltipIsTheTooltipsNotTheText() {
        editorTest(EditorState.create(text, EditorSelection.cursor(lineStart(3)), plugin), widgets = registry()) { f ->
            f.view.dispatch(TransactionSpec(effects = listOf(show.of(Tooltip(lineStart(3), key)))))
            waitForIdle()
            val r = tipRect()
            val before = f.view.state.selection
            onNodeWithTag(EDITOR_TAG).performMouseInput { click(r.center) }
            waitForIdle()
            assertEquals(before, f.view.state.selection, "the click went through to the text")
        }
    }

    @Test fun anEditMovesItWithItsText() {
        editorTest(EditorState.create(text, EditorSelection.cursor(lineStart(3)), plugin), widgets = registry()) { f ->
            f.view.dispatch(TransactionSpec(effects = listOf(show.of(Tooltip(lineStart(3) + 5, key)))))
            waitForIdle()
            val y0 = tipRect().top
            f.view.dispatch(TransactionSpec(changes = listOf(dev.supermux.editor.core.ChangeSpec(0, 0, "new line\n"))))
            waitForIdle()
            assertEquals(y0 + f.controller.layouts.lineHeightPx, tipRect().top, 1f, "moved a line down with its text")
        }
    }

    // ---------------------------------------------------------------------- hover --

    private var queries = 0
    private val hoverExt = hoverTooltip("t", hoverTime = 300) { st, pos, _ ->
        queries++
        val line = st.doc.lineAt(pos)
        val text = line.text
        var a = pos - line.from; var b = a
        while (a > 0 && text[a - 1].isLetterOrDigit()) a--
        while (b < text.length && text[b].isLetterOrDigit()) b++
        if (a == b) null else HoverResult(line.from + a, line.from + b, WidgetKey("tooltip:hover", text.substring(a, b)))
    }

    private fun ComposeUiTest.hoverShown(): List<String> =
        onAllNodesWithTag("hover:beta").fetchSemanticsNodes().map { "beta" } + onAllNodesWithTag("hover:alpha").fetchSemanticsNodes().map { "alpha" }

    @Test fun hoverShowsAfterTheDelayAndHidesOnLeaveEditEscapeAndScroll() {
        editorTest(EditorState.create(text, EditorSelection.cursor(0), hoverExt), widgets = registry()) { f ->
            val betaAt = lineStart(2) + "line 2 alpha b".length
            val p = f.controller.caretRectOnScreen(betaAt).center + Offset(f.controller.layouts.charWidthPx / 2, 0f)
            mainClock.autoAdvance = false
            onNodeWithTag(EDITOR_TAG).performMouseInput { moveTo(p) }
            mainClock.advanceTimeBy(200)
            assertTrue(hoverShown().isEmpty(), "shown before the hover time")
            mainClock.advanceTimeBy(200)
            mainClock.autoAdvance = true
            waitForIdle()
            assertEquals(listOf("beta"), hoverShown())
            assertNotNull(Hover.shown(f.view.state, "t"))
            // A move within the word keeps it; no new query.
            val q = queries
            onNodeWithTag(EDITOR_TAG).performMouseInput { moveTo(p + Offset(f.controller.layouts.charWidthPx, 0f)) }
            waitForIdle()
            assertEquals(q, queries)
            assertEquals(listOf("beta"), hoverShown())
            // Escape closes it.
            onNode(hasEditorField()).requestFocus()
            onNode(hasEditorField()).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertNull(Hover.shown(f.view.state, "t"), "Escape")
            // Back, then an edit closes it.
            onNodeWithTag(EDITOR_TAG).performMouseInput { moveTo(p + Offset(0f, f.controller.layouts.lineHeightPx)) } // alpha? another line's word
            onNodeWithTag(EDITOR_TAG).performMouseInput { moveTo(p) }
            mainClock.advanceTimeBy(600)
            waitForIdle()
            assertNotNull(Hover.shown(f.view.state, "t"))
            f.view.dispatch(TransactionSpec(changes = listOf(dev.supermux.editor.core.ChangeSpec(0, 0, "x")), userEvent = "input"))
            waitForIdle()
            assertNull(Hover.shown(f.view.state, "t"), "an edit")
            // Shown again, then a scroll dismisses it.
            onNodeWithTag(EDITOR_TAG).performMouseInput { moveTo(p + Offset(0f, 2 * f.controller.layouts.lineHeightPx)) }
            onNodeWithTag(EDITOR_TAG).performMouseInput { moveTo(p) }
            mainClock.advanceTimeBy(600)
            waitForIdle()
            assertNotNull(Hover.shown(f.view.state, "t"))
            runOnIdle { f.controller.scroll.scrollTo(0f, f.controller.layouts.lineHeightPx) }
            waitForIdle()
            assertNull(Hover.shown(f.view.state, "t"), "a scroll")
            // Leaving the word (off any text) closes it after a short grace.
        }
    }

    @Test fun leavingTheWordClosesTheHoverAndShowHoverAsksAtTheCaret() {
        editorTest(EditorState.create(text, EditorSelection.cursor(lineStart(1) + 8), hoverExt), widgets = registry()) { f ->
            val alphaAt = lineStart(1) + 8
            val p = f.controller.caretRectOnScreen(alphaAt).center + Offset(f.controller.layouts.charWidthPx / 2, 0f)
            onNodeWithTag(EDITOR_TAG).performMouseInput { moveTo(p) }
            mainClock.advanceTimeBy(400)
            waitForIdle()
            assertEquals(listOf("alpha"), hoverShown())
            // Far right of the line: no text there.
            onNodeWithTag(EDITOR_TAG).performMouseInput { moveTo(Offset(f.controller.viewportSize.width - 4f, p.y)) }
            mainClock.advanceTimeBy(400)
            waitForIdle()
            assertTrue(hoverShown().isEmpty(), "left the word")
            // Touch: the explicit command, no delay, at the main cursor.
            runOnIdle { Hover.showHover.run(f.view) }
            waitForIdle()
            assertEquals(listOf("alpha"), hoverShown())
        }
    }
}
