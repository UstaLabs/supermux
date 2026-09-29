package dev.supermux.editor.plugins.lsp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.plugins.autocomplete.Autocomplete
import dev.supermux.editor.plugins.autocomplete.AutocompleteConfig
import dev.supermux.editor.plugins.autocomplete.autocompletion
import dev.supermux.editor.plugins.autocomplete.registerWidgets
import dev.supermux.editor.plugins.lint.Lint
import dev.supermux.editor.plugins.lint.lint
import dev.supermux.editor.plugins.lint.registerWidgets
import dev.supermux.editor.plugins.lsp.fake.FakeLspServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The LSP features in a composed Editor against the fake server: hover, signature help, rename, references. */
@OptIn(ExperimentalTestApi::class)
class LspSurfaceTest {
    private val uri = "file:///work/main.toy"
    private val text = "fun add(a, b) {\n  return a + b\n}\nval total = add(1, 2)\n"

    private class Live(val view: EditorView, val client: LspClient, val server: FakeLspServer)

    private fun ComposeUiTest.editor(): Live {
        var live: Live? = null
        setContent {
            val scope = rememberCoroutineScope()
            val l = remember {
                val server = FakeLspServer(scope)
                val client = LspClient(server.transport, scope, LspClientConfig(parseOnWorker = false))
                val view = EditorView(EditorState.create(text, EditorSelection.cursor(text.length), extensionOf(
                    autocompletion(AutocompleteConfig(interactionDelay = 0)), lint(), client.plugin(uri, "toy"),
                )))
                Live(view, client, server)
            }
            live = l
            val registry = remember { WidgetRegistry().also { Autocomplete.registerWidgets(it); Lint.registerWidgets(it); l.client.registerWidgets(it) } }
            Box(Modifier.size(700.dp, 400.dp)) { Editor(l.view, Modifier.fillMaxSize().testTag("editor"), widgets = registry) }
        }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        return live!!
    }

    private fun ComposeUiTest.field() = onNode(hasSetTextAction() and !SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))

    @Test fun mouseHoverShowsTheServersText() = runComposeUiTest {
        val l = editor()
        assertEquals(LspClientState.READY, l.client.state.value)
        val at = text.indexOf("add(1") + 1
        val rect = l.view.coordsAtPos(at)!!
        onNodeWithTag("editor").performMouseInput { moveTo(Offset(rect.left + 3f, rect.center.y)) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        onNodeWithTag(LspTags.HOVER).fetchSemanticsNode()
    }

    @Test fun typingACallShowsSignatureHelpAboveTheCaret() = runComposeUiTest {
        val l = editor()
        field().requestFocus(); waitForIdle()
        for (c in "add(") { field().performTextInput(c.toString()); waitForIdle() }
        mainClock.advanceTimeBy(400); waitForIdle()
        val sig = onNodeWithTag(LspTags.SIGNATURE).fetchSemanticsNode()
        val caret = l.view.coordsAtPos(l.view.state.selection.main.head)!!
        assertTrue(sig.positionInRoot.y + sig.size.height <= caret.top + 1f, "above the caret's line")
    }

    @Test fun f2RenamesThroughThePrompt() = runComposeUiTest {
        val l = editor()
        field().requestFocus(); waitForIdle()
        runOnIdle { l.view.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.cursor(text.indexOf("total") + 1))) }
        field().performKeyInput { pressKey(Key.F2) }
        mainClock.advanceTimeBy(100); waitForIdle()
        onNodeWithTag(LspTags.RENAME_FIELD).assertIsFocused()
        onNodeWithTag(LspTags.RENAME_FIELD).performTextReplacement("sum")
        onNodeWithTag(LspTags.RENAME_FIELD).performKeyInput { pressKey(Key.Enter) }
        mainClock.advanceTimeBy(500); waitForIdle()
        assertTrue(l.view.state.doc.toString().contains("val sum = add(1, 2)"), l.view.state.doc.toString())
        assertTrue(l.view.focused, "the focus is back in the editor")
        assertEquals(0, onAllNodesWithTag(LspTags.RENAME_FIELD).fetchSemanticsNodes().size)
    }

    @Test fun shiftF12ShowsReferencesAndATapGoesThere() = runComposeUiTest {
        val l = editor()
        field().requestFocus(); waitForIdle()
        runOnIdle { l.view.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.cursor(1 + text.indexOf("add")))) }
        field().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F12) } }
        mainClock.advanceTimeBy(300); waitForIdle()
        onNodeWithTag(LspTags.REFERENCES).fetchSemanticsNode()
        onNodeWithTag(LspTags.reference(1)).performTouchInput { click() }
        waitForIdle()
        assertEquals(text.indexOf("add(1"), l.view.state.selection.main.from)
    }
}
