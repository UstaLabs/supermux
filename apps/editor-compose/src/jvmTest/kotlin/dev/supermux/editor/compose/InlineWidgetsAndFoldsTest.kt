package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InlineWidgetsAndFoldsTest {
    private val lines = (0 until 30).joinToString("\n") { "line $it" }
    private fun EditorState.lineEnd(l: Int) = doc.lineStart(l + 1) - 1

    private fun inline(at: Int, side: Int, type: String = "pill", id: String = "p") =
        Ranged(at, at, Decoration.InlineWidget(WidgetKey(type, id), side) as Decoration)

    private fun fold(from: Int, to: Int, id: String = "f") = Ranged(from, to, Decoration.Replace(WidgetKey("fold", id), fold = true) as Decoration)

    private fun pills() = WidgetRegistry().apply { register("pill") { Box(Modifier.width(30.dp).height(10.dp).testTag("pill")) } }

    @Test fun anInlineWidgetTakesItsRoomAndTheTextAroundItStillHitTests() {
        val plugin = RangePlugin("i", decorationsFacet, listOf(inline(3, side = 1)))
        editorTest(EditorState.create("abcdef\nsecond", extensions = plugin.extension), widgets = pills()) { f ->
            val cw = f.controller.layouts.charWidthPx
            val w = 30 * f.controller.densityValue
            val c = f.controller
            val at3 = c.caretRectOnScreen(3).left
            assertEquals(c.caretRectOnScreen(2).left + cw, at3, 1f, "the caret at 3 (side > 0) is right after the c")
            assertEquals(at3 + w + cw, c.caretRectOnScreen(4).left, 1.5f, "the d starts after the widget")
            val placed = assertNotNull(c.frame).widgets.single { it.inline }
            assertEquals(at3, placed.rect.left, 1f)
            assertEquals(w, placed.rect.width, 1f)
            assertTrue(onNodeWithTag("pill").fetchSemanticsNode().positionInRoot.x in (at3 - 1)..(at3 + 1), "the composable is not in its place")
            // A click in the e's left half: offset 4.
            val e = c.caretRectOnScreen(4)
            onNodeWithTag(EDITOR_TAG).performMouseInput { click(Offset(e.left + cw * 0.3f, e.center.y)) }
            waitForIdle()
            assertEquals(EditorSelection.cursor(4), f.view.state.selection)
            // Moving across it: one step each side.
            DefaultCommands.cursorLeft.run(f.view)
            assertEquals(3, f.view.state.selection.main.head)
            DefaultCommands.cursorLeft.run(f.view)
            assertEquals(2, f.view.state.selection.main.head)
        }
    }

    @Test fun anInlineWidgetsNegativeSideDrawsItBeforeTheCaret() {
        val plugin = RangePlugin("i", decorationsFacet, listOf(inline(3, side = -1)))
        editorTest(EditorState.create("abcdef", extensions = plugin.extension), widgets = pills()) { f ->
            val c = f.controller
            val w = 30 * c.densityValue
            assertEquals(c.caretRectOnScreen(2).left + c.layouts.charWidthPx + w, c.caretRectOnScreen(3).left, 1.5f, "the caret at 3 is after the widget")
        }
    }

    @Test fun anUnregisteredInlineWidgetIsADrawnChipAndItsClickIsReported() {
        val plugin = RangePlugin("i", decorationsFacet, listOf(inline(3, 1, type = "hint", id = "h1")))
        editorTest(EditorState.create("abcdef", extensions = plugin.extension)) { f ->
            val clicks = ArrayList<Triple<String, Int, Int>>()
            f.view.onWidgetClick = { key, from, to -> clicks += Triple(key.id, from, to) }
            val chip = assertNotNull(f.controller.frame).chips.single()
            assertEquals(f.controller.chipWidth(), chip.rect.width, 1f)
            val sel = f.view.state.selection
            onNodeWithTag(EDITOR_TAG).performMouseInput { click(chip.rect.center) }
            waitForIdle()
            assertEquals(listOf(Triple("h1", 3, 3)), clicks)
            assertEquals(sel, f.view.state.selection, "a chip click moved the caret")
        }
    }

    @Test fun aFoldHidesItsLinesSkipsTheirNumbersAndTheKeysJumpOverIt() {
        val base = EditorState.create(lines)
        val a = base.lineEnd(3)
        val b = base.lineEnd(8)
        val plugin = RangePlugin("fold", decorationsFacet, listOf(fold(a, b)))
        editorTest(EditorState.create(lines, EditorSelection.cursor(base.doc.lineStart(3) + 2), extensions = plugin.extension)) { f ->
            val g = f.geometry
            val c = f.controller
            val lh = g.layouts.lineHeightPx
            for (l in 4..8) assertEquals(0f, g.heights.height(l), "line $l is not hidden")
            assertEquals(c.caretRectOnScreen(base.doc.lineStart(3)).top + lh, c.caretRectOnScreen(base.doc.lineStart(9)).top, 0.5f, "line 10 is not right under line 4")
            assertTrue(g.shownLines(assertNotNull(c.frame).lines).none { it in 4..8 }, "hidden lines drawn")
            assertEquals(assertNotNull(c.frame).lines.count { it !in 4..8 }, c.frame!!.numbers.size, "a number drawn for a hidden line")
            // The chip sits where line 4's text ends.
            val chip = c.frame!!.chips.single()
            assertEquals(c.caretRectOnScreen(a).left, chip.rect.left, 1f)
            // Down from line 4 lands on line 10; up comes back.
            fun line() = f.view.state.doc.lineIndexAt(f.view.state.selection.main.head)
            DefaultCommands.cursorDown.run(f.view)
            assertEquals(9, line())
            DefaultCommands.cursorUp.run(f.view)
            assertEquals(3, line())
            // Right at the fold's start jumps to its end; left comes back.
            f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(a)))
            DefaultCommands.cursorRight.run(f.view)
            assertEquals(b, f.view.state.selection.main.head)
            DefaultCommands.cursorLeft.run(f.view)
            assertEquals(a, f.view.state.selection.main.head)
            // End on the fold's line goes past it.
            f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(base.doc.lineStart(3))))
            DefaultCommands.cursorLineEnd.run(f.view)
            assertEquals(b, f.view.state.selection.main.head)
            // A click on the chip is reported (the fold plugin unfolds), with the hidden range.
            val clicks = ArrayList<Pair<Int, Int>>()
            f.view.onWidgetClick = { _, from, to -> clicks += from to to }
            onNodeWithTag(EDITOR_TAG).performTouchInput { click(chip.rect.center) }
            waitForIdle()
            assertEquals(listOf(a to b), clicks)
            // Unfolding brings the lines back.
            plugin.replace(f.view, emptyList())
            waitForIdle()
            for (l in 4..8) assertTrue(g.heights.height(l) > 0f, "line $l still hidden after unfolding")
            assertEquals(c.caretRectOnScreen(base.doc.lineStart(3)).top + lh, c.caretRectOnScreen(base.doc.lineStart(4)).top, 0.5f)
            assertTrue(c.frame!!.chips.isEmpty())
        }
    }

    @Test fun aFoldSurvivesEditsAboveAndOutsideIt() {
        val base = EditorState.create(lines)
        val plugin = RangePlugin("fold", decorationsFacet, listOf(fold(base.lineEnd(3), base.lineEnd(8))))
        editorTest(EditorState.create(lines, extensions = plugin.extension)) { f ->
            val g = f.geometry
            f.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "new\nlines\n"))))
            waitForIdle()
            for (l in 6..10) assertEquals(0f, g.heights.height(l), "line $l is not hidden after an edit above")
            assertTrue(g.heights.height(5) > 0f && g.heights.height(11) > 0f)
            // Typing right before the fold, on its first line: the text stays visible.
            val at = plugin.value(f.view.state).single().from
            f.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, "XY"))))
            waitForIdle()
            assertEquals(at + 2, plugin.value(f.view.state).single().from)
            for (l in 6..10) assertEquals(0f, g.heights.height(l))
            // An edit below it.
            val below = f.view.state.doc.lineStart(20)
            f.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(below, below, "zz\n"))))
            waitForIdle()
            for (l in 6..10) assertEquals(0f, g.heights.height(l))
        }
    }

    @Test fun aFoldEndingInsideALineJoinsItsTailToTheFirstRow() {
        val base = EditorState.create(lines)
        val a = base.doc.lineStart(2) + 2 // "li|ne 2"
        val b = base.doc.lineStart(4) + 3 // "lin|e 4"
        val plugin = RangePlugin("fold", decorationsFacet, listOf(fold(a, b)))
        editorTest(EditorState.create(lines, extensions = plugin.extension)) { f ->
            val c = f.controller
            val cw = c.layouts.charWidthPx
            assertEquals(0f, f.geometry.heights.height(3))
            assertEquals(0f, f.geometry.heights.height(4))
            val chip = c.frame!!.chips.single()
            // "e 4" follows the chip on line 3's row.
            val tail = c.caretRectOnScreen(b)
            assertEquals(chip.rect.right, tail.left, 1f)
            assertEquals(c.caretRectOnScreen(base.doc.lineStart(2)).top, tail.top, 0.5f)
            // A click on the tail's second character.
            onNodeWithTag(EDITOR_TAG).performMouseInput { click(Offset(tail.left + cw * 1.3f, tail.center.y)) }
            waitForIdle()
            assertEquals(EditorSelection.cursor(b + 1), f.view.state.selection)
        }
    }

    @Test fun backspaceAtAFoldsEndUnfoldsItAndDeletesNothing() {
        val base = EditorState.create(lines)
        val a = base.lineEnd(3)
        val b = base.lineEnd(8)
        val plugin = RangePlugin("fold", decorationsFacet, listOf(fold(a, b)))
        var revealed: Pair<Int, Int>? = null
        val reveal = revealFacet.of(RevealHandler { t, from, to -> revealed = from to to; plugin.replace(t as EditorView, emptyList()); true })
        editorTest(EditorState.create(lines, EditorSelection.cursor(b), extensions = dev.supermux.editor.core.extensionOf(plugin.extension, reveal))) { f ->
            DefaultCommands.deleteBackward.run(f.view)
            waitForIdle()
            assertEquals(base.doc.toString(), f.view.state.doc.toString(), "a Backspace into a fold deleted text")
            assertEquals(a to b, revealed)
            for (l in 4..8) assertTrue(f.geometry.heights.height(l) > 0f, "line $l still folded")
        }
    }

    @Test fun aSelectionAcrossAFoldPaintsOnlyShownRows() {
        val base = EditorState.create(lines)
        val plugin = RangePlugin("fold", decorationsFacet, listOf(fold(base.lineEnd(3), base.lineEnd(8))))
        editorTest(EditorState.create(lines, EditorSelection.single(base.doc.lineStart(2), base.doc.lineStart(10)), extensions = plugin.extension)) { f ->
            val rows = f.controller.frame!!.selections
            // Lines 3, 4 (with its fold) and 10: three rows.
            assertEquals(3, rows.size, "selection rows: $rows")
        }
    }
}
