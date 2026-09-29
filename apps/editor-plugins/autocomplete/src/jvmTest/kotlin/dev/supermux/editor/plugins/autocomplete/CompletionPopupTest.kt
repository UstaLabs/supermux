package dev.supermux.editor.plugins.autocomplete

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The popup in a composed Editor: typing through the REAL hidden field (the soft keyboard's path), keys, taps. */
@OptIn(ExperimentalTestApi::class)
class CompletionPopupTest {
    private val words = (listOf("println", "print", "printf", "private") + (0 until 40).map { "prop$it" })

    private val source = CompletionSource { c ->
        val from = c.wordStart()
        if (from == c.pos && !c.explicit) null
        else CompletionResult(from, words.map { Completion(it, detail = "fun", type = "function", info = if (it == "print") "Prints a line." else null) }, validFor = Regex("\\w*"))
    }

    private fun ComposeUiTest.editor(text: String = "", cursor: Int = 0): EditorView {
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(cursor), autocompletion(AutocompleteConfig(interactionDelay = 0), override = listOf(source))))
        val registry = WidgetRegistry().also { Autocomplete.registerWidgets(it) }
        setContent { Box(Modifier.size(500.dp, 400.dp)) { Editor(view, Modifier.fillMaxSize(), widgets = registry) } }
        waitForIdle()
        return view
    }

    private fun ComposeUiTest.field() = onNode(hasSetTextAction() and !SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
    private fun ComposeUiTest.fieldText() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun ComposeUiTest.typeInField(s: String) {
        for (c in s) { field().performTextInput(c.toString()); waitForIdle() }
        mainClock.advanceTimeBy(300)
        waitForIdle()
    }

    @Test fun typingThroughTheFieldOpensTheListAndATapAcceptsKeepingTheFocus() = runComposeUiTest {
        val view = editor("x = ", 4)
        field().requestFocus(); waitForIdle()
        typeInField("pri")
        onNodeWithTag(CompletionTags.LIST).fetchSemanticsNode()
        assertEquals("print", Autocomplete.state(view.state).selectedOption?.completion?.label)
        onNodeWithTag(CompletionTags.INFO).fetchSemanticsNode() // print's documentation beside the list
        onNodeWithTag(CompletionTags.option(2)).performTouchInput { click() }
        waitForIdle()
        assertEquals("x = println", view.state.doc.toString())
        assertTrue(view.focused, "the tap took the focus from the editor")
        assertEquals(0, onAllNodesWithTag(CompletionTags.LIST).fetchSemanticsNodes().size, "closed after accept")
        assertTrue(fieldText().endsWith("x = println"), "the field was rewritten: '${fieldText()}'")
        // The next keystroke lands after the completion.
        field().performTextInput("(")
        waitForIdle()
        assertEquals("x = println(", view.state.doc.toString())
    }

    @Test fun hardwareKeysNavigateAndEnterAccepts() = runComposeUiTest {
        val view = editor()
        field().requestFocus(); waitForIdle()
        typeInField("pr")
        field().performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        waitForIdle()
        val chosen = Autocomplete.state(view.state).selectedOption!!.completion.label
        field().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(chosen, view.state.doc.toString(), "Enter accepted instead of a newline")
        typeInField(" pr")
        field().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(CompletionTags.LIST).fetchSemanticsNodes().size)
        field().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("$chosen pr\n", view.state.doc.toString(), "closed: Enter is a newline")
    }

    @Test fun theListScrollsAndKeepsTheSelectionInView() = runComposeUiTest {
        val view = editor()
        field().requestFocus(); waitForIdle()
        typeInField("pro")
        repeat(30) { field().performKeyInput { pressKey(Key.DirectionDown) } }
        waitForIdle()
        val sel = Autocomplete.state(view.state).selected
        onNodeWithTag(CompletionTags.option(sel)).fetchSemanticsNode() // composed: scrolled into view
        onNodeWithTag(CompletionTags.LIST).performTouchInput { swipeUp() }
        waitForIdle()
        assertEquals("pro", view.state.doc.toString(), "a drag in the list is the list's")
    }
}
