package dev.supermux.editor.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
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
class BlockWidgetsTest {
    private val text = (0 until 400).joinToString("\n") { "line $it" }
    private fun lineStart(line: Int) = EditorState.create(text).doc.lineStart(line)
    private fun block(line: Int, id: String, above: Boolean = false, type: String = "box", lines: Float = 1f) =
        Ranged(lineStart(line), lineStart(line), Decoration.BlockWidget(WidgetKey(type, id), above = above, estimatedHeightLines = lines) as Decoration)

    private fun boxes(heightDp: Int) = WidgetRegistry().apply {
        register("box") { key -> Box(Modifier.fillMaxWidth().height(heightDp.dp).testTag("w-${key.id}")) }
    }

    private fun SurfaceFixture.placed(id: String) = assertNotNull(controller.frame).widgets.single { it.key.id == id }

    @Test fun aWidgetSitsBelowItsLineAndItsMeasuredHeightMovesTheLinesAfterIt() {
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(3, "a")))
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = boxes(100)) { f ->
            val px = 100 * f.controller.densityValue
            val g = f.geometry
            assertEquals(px, g.heights.blockBelow(3), 0.5f, "the measured height did not reach the height map")
            val w = f.placed("a")
            // Right under line 3's text, across the text area.
            assertEquals(g.lineTop(3) + g.textHeight(3) - f.controller.scroll.y, w.rect.top, 0.5f)
            assertEquals(px, w.rect.height, 0.5f)
            assertEquals(f.controller.gutterWidth, w.rect.left, 0.5f)
            // Line 4 starts below it.
            assertEquals(w.rect.bottom, f.controller.caretRectOnScreen(lineStart(4)).top, 0.5f)
            // The composable is really there.
            val node = onNodeWithTag("w-a").fetchSemanticsNode()
            assertEquals(w.rect.top, node.positionInRoot.y, 1f)
        }
    }

    @Test fun aWidgetAboveItsLinePushesTheLineDown() {
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(2, "a", above = true)))
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = boxes(40)) { f ->
            val g = f.geometry
            val w = f.placed("a")
            assertEquals(g.heights.top(2) - f.controller.scroll.y, w.rect.top, 0.5f)
            assertEquals(w.rect.bottom, f.controller.caretRectOnScreen(lineStart(2)).top, 0.5f)
        }
    }

    @Test fun anUnmeasuredWidgetUsesItsEstimateAndAGapTypeIsExactlyItsLines() {
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(300, "far", lines = 3f), block(5, "gap", type = "gap", lines = 2f)))
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = boxes(100)) { f ->
            val lh = f.geometry.layouts.lineHeightPx
            assertEquals(3 * lh, f.geometry.heights.blockBelow(300), 0.5f, "off screen: the estimate")
            assertEquals(2 * lh, f.geometry.heights.blockBelow(5), 0.01f, "a type nobody registered is empty space of exactly its lines")
        }
    }

    @Test fun scrollingUpPastATallNeverMeasuredWidgetMovesTheTextByExactlyTheScroll() {
        // Tall widgets every 20 lines, estimated at one line each: 300 px real.
        val plugin = RangePlugin("w", decorationsFacet, (1 until 20).map { block(it * 20, "t$it") })
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = boxes(300)) { f ->
            f.controller.scroll.scrollTo(y = 1e9f)
            waitForIdle()
            val d = 13f
            repeat(120) { step ->
                val ref = f.geometry.heights.lineAt(f.controller.scroll.y) + 1
                val before = f.geometry.lineTop(ref) - f.controller.scroll.y
                f.controller.scroll.scrollBy(0f, -d)
                waitForIdle()
                val after = f.geometry.lineTop(ref) - f.controller.scroll.y
                assertEquals(before + d, after, 0.5f, "step $step: line $ref jumped by ${after - before - d} px")
            }
            // Down again, past widgets measured on the way up.
            repeat(60) { step ->
                val ref = f.geometry.heights.lineAt(f.controller.scroll.y) + 1
                val before = f.geometry.lineTop(ref) - f.controller.scroll.y
                f.controller.scroll.scrollBy(0f, d)
                waitForIdle()
                val after = f.geometry.lineTop(ref) - f.controller.scroll.y
                assertEquals(before - d, after, 0.5f, "down step $step: line $ref jumped")
            }
        }
    }

    @Test fun aTextFieldInAWidgetTakesTypingAndKeysWhileTheEditorDoesNot() {
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(2, "t1", type = "thread")))
        val registry = WidgetRegistry().apply {
            register("thread") {
                val draft = rememberTextFieldState()
                BasicTextField(draft, Modifier.fillMaxWidth().height(60.dp).testTag("draft"))
            }
        }
        editorTest(EditorState.create(text, EditorSelection.cursor(lineStart(2) + 6), extensions = plugin.extension), widgets = registry) { f ->
            val doc = f.view.state.doc.toString()
            // The caret is at the end of line 2, right above the widget: the pointer shield (64 dp
            // around the caret) overlaps the widget's top, and must not take the widget's click.
            val w = f.placed("t1").rect
            onNodeWithTag(EDITOR_TAG).performMouseInput { click(Offset(f.controller.caretRectOnScreen(lineStart(2) + 6).left, w.top + 4f)) }
            waitForIdle()
            assertTrue(!f.view.focused, "the editor kept the focus")
            onNodeWithTag("draft").performTextInput("hello")
            waitForIdle()
            onNodeWithTag("draft").performKeyInput { pressKey(Key.Backspace); pressKey(Key.DirectionLeft); pressKey(Key.Enter) }
            waitForIdle()
            assertEquals(doc, f.view.state.doc.toString(), "the editor took the widget's typing or keys")
            assertEquals(EditorSelection.cursor(lineStart(2) + 6), f.view.state.selection, "the editor's caret moved")
            val draft = onNodeWithTag("draft").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
            assertEquals("hel\nl", draft)
            // Back to the editor: a click on its text.
            onNodeWithTag(EDITOR_TAG).performMouseInput { click(f.controller.caretRectOnScreen(lineStart(0) + 2).center) }
            waitForIdle()
            assertTrue(f.view.focused, "a click on the text did not take the focus back")
            onNode(hasEditorField() and !androidx.compose.ui.test.hasTestTag("draft")).performTextInput("X")
            waitForIdle()
            assertEquals("liXne 0", f.view.state.doc.line(1).text)
        }
    }

    @Test fun aWidgetTapIsTheWidgetsNotTheEditors() {
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(1, "btn")))
        var clicks = 0
        val registry = WidgetRegistry().apply {
            register("box") { Box(Modifier.fillMaxWidth().height(50.dp).testTag("btn").clickable { clicks++ }) }
        }
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = registry) { f ->
            val sel = f.view.state.selection
            onNodeWithTag("btn").performTouchInput { click() }
            waitForIdle()
            assertEquals(1, clicks)
            assertEquals(sel, f.view.state.selection, "a tap on the widget moved the caret")
            assertEquals(0, f.keyboard.shows.get(), "a tap on the widget raised the editor's keyboard")
        }
    }

    @Test fun focusMovesBackFromAWidgetThroughItsScope() {
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(1, "t")))
        val registry = WidgetRegistry().apply {
            register("box") {
                val draft = rememberTextFieldState()
                BasicTextField(draft, Modifier.fillMaxWidth().height(40.dp).testTag("draft").onPreviewKeyEvent { e ->
                    if (e.key == Key.Escape) { focusEditor(); true } else false
                })
            }
        }
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = registry) { f ->
            onNodeWithTag("draft").performClick()
            waitForIdle()
            assertTrue(!f.view.focused)
            onNodeWithTag("draft").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertTrue(f.view.focused, "focusEditor() did not give the focus back")
        }
    }

    @Test fun aWidgetKeepsItsStateAcrossAScrollOutAndBack() {
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(2, "counter")) + (1..30).map { block(40 + it * 8, "other$it") })
        var disposed = 0
        val registry = WidgetRegistry().apply {
            register("box") { key ->
                if (key.id == "counter") {
                    var n by remember { mutableIntStateOf(0) }
                    val draft = rememberTextFieldState()
                    DisposableEffect(Unit) { onDispose { disposed++ } }
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("counter").clickable { n++ }) { BasicText("n=$n draft=${draft.text}") }
                } else {
                    Box(Modifier.fillMaxWidth().height(30.dp))
                }
            }
        }
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = registry) { f ->
            repeat(3) { onNodeWithTag("counter").performClick(); waitForIdle() }
            onNodeWithTag("counter", useUnmergedTree = true).assertExists()
            // Out of view (a screen down) and back: still composed, the same state.
            f.controller.scroll.scrollTo(y = 20 * f.geometry.layouts.lineHeightPx)
            waitForIdle()
            assertTrue(f.controller.frame!!.widgets.none { it.key.id == "counter" }, "still placed off screen")
            f.controller.scroll.scrollTo(y = 0f)
            waitForIdle()
            assertEquals(0, disposed, "disposed within the retained cache")
            assertTrue(textOf("counter").startsWith("n=3"), "lost its state: ${textOf("counter")}")
            // Far away, past many other widgets: disposed, and saveable state comes back.
            f.controller.scroll.scrollTo(y = 1e9f)
            waitForIdle()
            assertEquals(1, disposed, "a widget far out of view is still composed")
            f.controller.scroll.scrollTo(y = 0f)
            waitForIdle()
            assertEquals(1, disposed)
            onNodeWithTag("counter", useUnmergedTree = true).assertExists()
        }
    }

    @Test fun aDraftTypedInAWidgetSurvivesItsDisposal() {
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(2, "thread")) + (1..30).map { block(40 + it * 8, "other$it") })
        val registry = WidgetRegistry().apply {
            register("box") { key ->
                if (key.id == "thread") BasicTextField(rememberTextFieldState(), Modifier.fillMaxWidth().height(40.dp).testTag("draft"))
                else Box(Modifier.fillMaxWidth().height(30.dp))
            }
        }
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = registry) { f ->
            onNodeWithTag("draft").performClick()
            onNodeWithTag("draft").performTextInput("half a comment")
            waitForIdle()
            f.controller.scroll.scrollTo(y = 1e9f)
            waitForIdle()
            f.controller.scroll.scrollTo(y = 0f)
            waitForIdle()
            val draft = onNodeWithTag("draft").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
            assertEquals("half a comment", draft)
        }
    }

    @Test fun upAndDownStepOverAWidget() {
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(3, "below"), block(6, "above", above = true)))
        editorTest(EditorState.create(text, EditorSelection.cursor(lineStart(3) + 2), extensions = plugin.extension), widgets = boxes(80)) { f ->
            fun line() = f.view.state.doc.lineIndexAt(f.view.state.selection.main.head)
            DefaultCommands.cursorDown.run(f.view)
            waitForIdle()
            assertEquals(4, line(), "Down from the line above a widget")
            DefaultCommands.cursorUp.run(f.view)
            waitForIdle()
            assertEquals(3, line(), "Up from the line below a widget")
            f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(lineStart(6) + 2)))
            DefaultCommands.cursorUp.run(f.view)
            waitForIdle()
            assertEquals(5, line(), "Up over a widget above the line")
            DefaultCommands.cursorDown.run(f.view)
            waitForIdle()
            assertEquals(6, line(), "Down over a widget above the line")
            assertEquals(lineStart(6) + 2, f.view.state.selection.main.head, "the goal column was lost")
        }
    }

    @Test fun widgetsFollowEditsAboveThem() {
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(10, "a")))
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = boxes(60)) { f ->
            val px = 60 * f.controller.densityValue
            f.view.dispatch(TransactionSpec(changes = listOf(dev.supermux.editor.core.ChangeSpec(0, 0, "x\ny\n"))))
            waitForIdle()
            assertEquals(0f, f.geometry.heights.blockBelow(10), "the old line kept the block")
            assertEquals(px, f.geometry.heights.blockBelow(12), 0.5f, "the block did not follow its line")
            // Typing on the widget's own line keeps its block.
            val at = f.view.state.doc.lineStart(12) + 3
            f.view.dispatch(TransactionSpec(changes = listOf(dev.supermux.editor.core.ChangeSpec(at, at, "zz"))))
            waitForIdle()
            assertEquals(px, f.geometry.heights.blockBelow(12), 0.5f)
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.textOf(tag: String): String =
        onNodeWithTag(tag, useUnmergedTree = true).onChildren().fetchSemanticsNodes().firstNotNullOfOrNull { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { t: androidx.compose.ui.text.AnnotatedString -> t.text }
        } ?: ""
}
