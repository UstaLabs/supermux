package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.GutterMarker
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.gutterMarkersFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Review fixes: widget slots, their saved state and their recompositions. */
@OptIn(ExperimentalTestApi::class)
class WidgetLifecycleTest {
    private val text = (0 until 300).joinToString("\n") { "line $it" }
    private fun at(line: Int) = EditorState.create(text).doc.lineStart(line)
    private fun block(line: Int, key: WidgetKey, lines: Float = 1f) = Ranged(at(line), at(line), Decoration.BlockWidget(key, estimatedHeightLines = lines) as Decoration)
    private fun inline(pos: Int, key: WidgetKey) = Ranged(pos, pos, Decoration.InlineWidget(key) as Decoration)

    @Test fun aWidgetThatGrowsAndPushesAnotherOutOfRangeDoesNotCrash() {
        var tall by mutableStateOf(false)
        val registry = WidgetRegistry().apply {
            register("grow") { Box(Modifier.fillMaxWidth().height(if (tall) 400.dp else 20.dp)) }
            register("edge") { Box(Modifier.fillMaxWidth().height(20.dp).testTag("edge")) }
        }
        // The edge widget sits near the end of the laid-out range (15 visible lines + overscan).
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(1, WidgetKey("grow", "g")), block(16, WidgetKey("edge", "e"))))
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = registry) { f ->
            onNodeWithTag("edge").assertExists()
            tall = true
            waitForIdle()
            assertTrue(f.controller.frame!!.widgets.none { it.key.id == "e" }, "the edge widget is still placed")
            tall = false
            waitForIdle()
            onNodeWithTag("edge").assertExists()
        }
    }

    @Test fun oneKeyAsABlockAndInlineAtOnce() {
        val key = WidgetKey("pill", "x")
        val registry = WidgetRegistry().apply {
            register("pill") { val s = rememberTextFieldState(); Box(Modifier.width(20.dp).height(10.dp)) { BasicTextField(s, Modifier.size(1.dp)) } }
        }
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(2, key), inline(at(4) + 2, key)))
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = registry) { f ->
            val placed = f.controller.frame!!.widgets.filter { it.key == key }
            assertEquals(setOf(true, false), placed.map { it.inline }.toSet(), "not shown in both roles")
        }
    }

    @Test fun aDraftInOneDocumentNeverShowsInAnother() = runComposeUiTest {
        DrawGuard.strict = true
        val key = WidgetKey("thread", "t1")
        fun doc() = EditorState.create(text, extensions = decorationsFacet.of(dev.supermux.editor.core.RangeSet.of(listOf(block(2, key)))))
        val a = EditorView(doc())
        val b = EditorView(doc())
        var shown by mutableStateOf(a)
        val registry = WidgetRegistry().apply {
            register("thread") { BasicTextField(rememberTextFieldState(), Modifier.fillMaxWidth().height(40.dp).testTag("draft")) }
        }
        setContent {
            CompositionLocalProvider(LocalEditorCursorBlink provides false) {
                Box(Modifier.size(400.dp, 300.dp)) { Editor(shown, Modifier.fillMaxSize(), widgets = registry) }
            }
        }
        waitForIdle()
        onNodeWithTag("draft").performClick()
        onNodeWithTag("draft").performTextInput("for document A")
        waitForIdle()
        shown = b
        waitForIdle()
        val draft = onNodeWithTag("draft").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertEquals("", draft, "document A's draft showed in document B")
        DrawGuard.strict = false
    }

    @Test fun savedWidgetStateStaysBoundedAsWidgetsComeAndGo() {
        val plugin = RangePlugin<Decoration>("w", decorationsFacet)
        val registry = WidgetRegistry().apply {
            register("thread") { BasicTextField(rememberTextFieldState(), Modifier.fillMaxWidth().height(30.dp)) }
        }
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = registry) { f ->
            for (i in 0 until 100) {
                plugin.replace(f.view, listOf(block(3, WidgetKey("thread", "t$i"))))
                waitForIdle()
            }
            assertTrue(f.controller.savedWidgetStates <= 2, "saved states kept for widgets that are gone: ${f.controller.savedWidgetStates}")
        }
    }

    @Test fun scrollingRecomposesNoWidget() {
        var blockComps = 0
        var inlineComps = 0
        val registry = WidgetRegistry().apply {
            register("b") { SideEffect { blockComps++ }; Box(Modifier.fillMaxWidth().height(30.dp)) }
            register("i") { SideEffect { inlineComps++ }; Box(Modifier.width(20.dp).height(10.dp)) }
        }
        val plugin = RangePlugin("w", decorationsFacet, listOf(block(3, WidgetKey("b", "1")), inline(at(5) + 3, WidgetKey("i", "1"))))
        editorTest(EditorState.create(text, extensions = plugin.extension), widgets = registry) { f ->
            f.controller.scroll.scrollBy(0f, 2f)
            waitForIdle()
            val b0 = blockComps
            val i0 = inlineComps
            repeat(10) {
                f.controller.scroll.scrollBy(0f, 1f)
                waitForIdle()
            }
            assertEquals(b0, blockComps, "a scroll recomposed the block widget")
            assertEquals(i0, inlineComps, "a scroll recomposed the inline widget")
        }
    }

    @Test fun anEnterAboveMarkersKeepsTheirNodes() {
        val markers = (10 until 16).map { Ranged(at(it), at(it), GutterMarker("lint", "lint-error", "error $it")) }
        val plugin = RangePlugin("m", gutterMarkersFacet, markers)
        editorTest(EditorState.create(text, extensions = extensionOf(plugin.extension))) { f ->
            val created = f.controller.markerNodesCreated
            f.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "\n"))))
            waitForIdle()
            assertEquals(created, f.controller.markerNodesCreated, "an Enter above recreated the marker nodes below it")
            // Two markers that are the same instance still get two nodes.
            val same = GutterMarker("lint", "lint-warning", "same")
            plugin.replace(f.view, listOf(Ranged(at(3), at(3), same), Ranged(at(5), at(5), same)))
            waitForIdle()
            onNodeWithTag(EDITOR_TAG).assertExists()
        }
    }
}
