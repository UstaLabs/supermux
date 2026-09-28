package dev.supermux.editor.compose

import androidx.compose.ui.test.ExperimentalTestApi
import dev.supermux.editor.core.Compartment
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The editor-level facets M4a's plugins set: the viewport as state, the line-number toggle. */
@OptIn(ExperimentalTestApi::class)
class PluginFacetsTest {
    private val text = (0 until 500).joinToString("\n") { "line $it" }

    @Test fun theViewportReachesTheStateOfAPluginThatAsksForIt() {
        editorTest(EditorState.create(text, extensions = EditorViewport.extension)) { f ->
            waitForIdle()
            val shown = EditorViewport.of(f.view.state)!!
            assertTrue(!shown.isEmpty(), "no viewport in the state after the first paint")
            assertEquals(f.view.viewport.value, shown)
            assertEquals(0, shown.first)
            f.controller.scroll.scrollBy(0f, 2000f)
            waitForIdle()
            val scrolled = EditorViewport.of(f.view.state)!!
            assertTrue(scrolled.first > 0, "the viewport in the state did not follow the scroll: $scrolled")
            assertEquals(f.view.viewport.value, scrolled)
        }
        // Without the field, no transaction is made for it.
        editorTest(EditorState.create(text)) { f ->
            var n = 0
            f.view.addListener { n++ }
            f.controller.scroll.scrollBy(0f, 2000f)
            waitForIdle()
            assertEquals(0, n)
        }
    }

    @Test fun theLineNumbersFacetOverridesTheParameterAndReconfigures() {
        val numbers = Compartment("numbers")
        editorTest(EditorState.create(text, extensions = numbers.of(lineNumbersFacet.of(false)))) { f ->
            assertEquals(0f, f.controller.numbersRight, "line numbers shown although the facet says no")
            f.view.dispatch(TransactionSpec(effects = listOf(numbers.reconfigure(lineNumbersFacet.of(true)))))
            waitForIdle()
            assertTrue(f.controller.numbersRight > 0f, "the reconfigure did not bring the line numbers back")
        }
        editorTest(EditorState.create(text, extensions = lineNumbersFacet.of(true)), showLineNumbers = false) { f ->
            assertTrue(f.controller.numbersRight > 0f)
        }
    }
}
