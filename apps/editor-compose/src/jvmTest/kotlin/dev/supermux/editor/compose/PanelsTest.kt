package dev.supermux.editor.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.Compartment
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Panel
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.panelsFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PanelsTest {
    private val text = (0 until 100).joinToString("\n") { "line $it" }

    private fun registry() = WidgetRegistry().apply {
        register("panel:search") {
            BasicTextField(rememberTextFieldState(), Modifier.fillMaxWidth().height(40.dp).testTag("search"))
        }
        register("panel:status") {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(24.dp).testTag("status"))
        }
    }

    @Test fun panelsTakeTheirHeightOutOfTheViewportTopAndBottom() {
        val panels = extensionOf(panelsFacet.of(Panel("search", top = true)), panelsFacet.of(Panel("status", top = false)))
        editorTest(EditorState.create(text, extensions = panels), heightDp = 300, widgets = registry()) { f ->
            val d = f.controller.densityValue
            assertEquals((300 - 40 - 24) * d, f.controller.viewportSize.height, 1f, "the viewport did not shrink by the panels")
            assertEquals(0f, onNodeWithTag("search").fetchSemanticsNode().positionInRoot.y, 1f, "the top panel is not at the top")
            assertEquals((300 - 24) * d, onNodeWithTag("status").fetchSemanticsNode().positionInRoot.y, 1f, "the bottom panel is not at the bottom")
            // The text starts under the top panel.
            val editorTop = onNodeWithTag(EDITOR_TAG).fetchSemanticsNode().positionInRoot.y
            val surface = f.controller.coordinates!!
            assertEquals(editorTop + 40 * d, surface.localToRoot(androidx.compose.ui.geometry.Offset.Zero).y, 1f)
        }
    }

    @Test fun aPanelTakesTypingAndEscapeGivesTheFocusBack() {
        editorTest(EditorState.create(text, extensions = panelsFacet.of(Panel("search", top = true))), widgets = registry()) { f ->
            val doc = f.view.state.doc.toString()
            onNodeWithTag("search").performClick()
            waitForIdle()
            assertTrue(!f.view.focused)
            onNodeWithTag("search").performTextInput("needle")
            onNodeWithTag("search").performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.Backspace) }
            waitForIdle()
            assertEquals(doc, f.view.state.doc.toString(), "the editor took the panel's input")
            assertEquals("neede", onNodeWithTag("search").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            onNodeWithTag("search").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertTrue(f.view.focused, "Escape did not give the focus back to the editor")
        }
    }

    @Test fun aPanelsContentSeesEscapeFirst() {
        var escapes = 0
        val chords = ArrayList<dev.supermux.editor.core.KeyChord>()
        val reg = WidgetRegistry().apply {
            register("panel:own") {
                BasicTextField(
                    rememberTextFieldState(),
                    Modifier.fillMaxWidth().height(40.dp).testTag("own").onPreviewKeyEvent { e ->
                        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) keyChordOf(e)?.let { chords += it }
                        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == Key.Escape) { escapes++; true } else false
                    },
                )
            }
        }
        editorTest(EditorState.create(text, extensions = panelsFacet.of(Panel("own", top = true))), widgets = reg) { f ->
            onNodeWithTag("own").performClick()
            waitForIdle()
            onNodeWithTag("own").performKeyInput { withKeyDown(Key.ShiftLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.G) } } }
            onNodeWithTag("own").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertEquals(1, escapes, "the panel's content did not see its Escape")
            // keyChordOf names what the content saw, the way editor-core's key bindings do.
            assertTrue(dev.supermux.editor.core.KeyChord("g", shift = true, meta = true) in chords, chords.toString())
            assertEquals(dev.supermux.editor.core.KeyChord("Escape"), chords.last())
            assertTrue(!f.view.focused, "the surface took an Escape the panel's content handled")
        }
    }

    @Test fun aPanelComingAndGoingKeepsTheEditor() {
        val slot = Compartment("panels")
        editorTest(EditorState.create(text, extensions = slot.of(extensionOf())), heightDp = 300, widgets = registry()) { f ->
            val d = f.controller.densityValue
            val controller = f.controller
            f.view.focus()
            waitForIdle()
            assertTrue(f.view.focused)
            f.view.dispatch(TransactionSpec(effects = listOf(slot.reconfigure(panelsFacet.of(Panel("search", top = true))))))
            waitForIdle()
            assertEquals((300 - 40) * d, f.controller.viewportSize.height, 1f)
            assertTrue(controller === f.controller, "the surface was rebuilt")
            f.view.dispatch(TransactionSpec(effects = listOf(slot.reconfigure(extensionOf()))))
            waitForIdle()
            assertEquals(300 * d, f.controller.viewportSize.height, 1f)
            assertTrue(f.view.focused, "the editor lost its focus to a panel coming and going")
        }
    }
}
