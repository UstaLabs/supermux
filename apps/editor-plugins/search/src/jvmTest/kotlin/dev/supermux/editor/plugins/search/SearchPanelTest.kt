package dev.supermux.editor.plugins.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.plugins.history.History
import dev.supermux.editor.plugins.history.history
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The search panel in a composed Editor: focus, Escape, typing with its debounce, the buttons. */
@OptIn(ExperimentalTestApi::class)
class SearchPanelTest {
    private val text = "val foo = 1\nfoo(foo)\n\nfood = foo + bar\n"
    private val mod = if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft

    private fun ComposeUiTest.editor(cursor: Int = 0): EditorView {
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(cursor), extensionOf(search(), history())))
        val registry = WidgetRegistry().also { Search.registerWidgets(it) }
        setContent { Box(Modifier.size(700.dp, 400.dp)) { Editor(view, Modifier.fillMaxSize(), widgets = registry) } }
        waitForIdle()
        return view
    }

    private fun ComposeUiTest.editorField() = onNode(hasSetTextAction() and !SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))

    private fun ComposeUiTest.openWithModF() {
        editorField().requestFocus()
        waitForIdle()
        editorField().performKeyInput { withKeyDown(mod) { pressKey(Key.F) } }
        waitForIdle()
    }

    private fun ComposeUiTest.fieldText(tag: String) =
        onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private val EditorView.ranges get() = state.selection.ranges.map { it.from to it.to }

    @Test fun modFOpensThePanelItsFieldHasTheFocusAndTheWord() = runComposeUiTest {
        val view = editor(cursor = 5)
        openWithModF()
        assertTrue(Search.isOpen(view.state))
        onNodeWithTag(SearchPanelTags.FIND).assertIsFocused()
        assertEquals("foo", fieldText(SearchPanelTags.FIND))
        assertFalse(view.focused)
    }

    @Test fun typingSearchesAfterTheDebounceAndSelectsTheFirstMatch() = runComposeUiTest {
        val view = editor(cursor = text.indexOf("\n\n") + 1) // an empty line: nothing to fill in
        openWithModF()
        assertEquals("", fieldText(SearchPanelTags.FIND))
        mainClock.autoAdvance = false
        onNodeWithTag(SearchPanelTags.FIND).performTextInput("foo")
        mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS / 2)
        assertEquals("", Search.query(view.state).search, "searched before the debounce")
        mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS * 2)
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals("foo", Search.query(view.state).search)
        val at = text.indexOf("food")
        assertEquals(listOf(at to at + 3), view.ranges, "the first match from the cursor is selected")
        waitUntil(timeoutMillis = 2000) { onNodeWithTag(SearchPanelTags.COUNT).fetchSemanticsNode().config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.firstOrNull() == "4 of 5" }
        onNodeWithTag(SearchPanelTags.FIND).assertIsFocused()
    }

    @Test fun enterMovesShiftEnterMovesBackEscapeCloses() = runComposeUiTest {
        val view = editor(cursor = 5)
        openWithModF()
        val find = onNodeWithTag(SearchPanelTags.FIND)
        find.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf(12 to 15), view.ranges)
        find.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf(16 to 19), view.ranges)
        find.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        waitForIdle()
        assertEquals(listOf(12 to 15), view.ranges)
        find.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(Search.isOpen(view.state))
        assertTrue(view.focused, "Escape gave the focus back to the editor")
    }

    @Test fun theButtonsRunTheCommandsAndKeepTheFieldFocused() = runComposeUiTest {
        val view = editor(cursor = 5)
        openWithModF()
        onNodeWithContentDescription("Next match").performClick()
        waitForIdle()
        assertEquals(listOf(12 to 15), view.ranges)
        onNodeWithTag(SearchPanelTags.FIND).assertIsFocused()
        onNodeWithContentDescription("Previous match").performClick()
        waitForIdle()
        assertEquals(listOf(4 to 7), view.ranges)
        // The toggles show and set the query's flags.
        val word = onNodeWithContentDescription("Whole word")
        assertEquals(ToggleableState.Off, word.fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        word.performClick()
        waitForIdle()
        assertTrue(Search.query(view.state).wholeWord)
        assertEquals(ToggleableState.On, word.fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        onNodeWithContentDescription("Close").performClick()
        waitForIdle()
        assertFalse(Search.isOpen(view.state))
        assertTrue(view.focused)
    }

    @Test fun replaceAllFromThePanelIsOneUndoStep() = runComposeUiTest {
        val view = editor(cursor = 5)
        openWithModF()
        onNodeWithContentDescription("Show replace").performClick()
        waitForIdle()
        onNodeWithTag(SearchPanelTags.REPLACE).performTextInput("baz")
        // Straight away: the button uses what the field holds, not the debounced query.
        onNodeWithContentDescription("Replace all").performClick()
        waitForIdle()
        assertEquals(text.replace("foo", "baz"), view.state.doc.toString())
        assertEquals(1, History.undoDepth(view.state))
        History.undo.run(view)
        assertEquals(text, view.state.doc.toString())
    }

    @Test fun enterInTheReplaceFieldReplacesOneByOne() = runComposeUiTest {
        val view = editor(cursor = 5)
        openWithModF()
        onNodeWithContentDescription("Show replace").performClick()
        waitForIdle()
        val r = onNodeWithTag(SearchPanelTags.REPLACE)
        r.performTextInput("X")
        r.performKeyInput { pressKey(Key.Enter) } // a cursor, not a match: the first Enter selects the next one
        waitForIdle()
        assertEquals(text, view.state.doc.toString())
        assertEquals(listOf(12 to 15), view.ranges)
        r.performKeyInput { pressKey(Key.Enter) } // now it is the match: replaced, the next one selected
        waitForIdle()
        assertEquals("val foo = 1\nX(foo)\n\nfood = foo + bar\n", view.state.doc.toString())
        assertEquals(listOf(14 to 17), view.ranges, "the next match is selected")
    }

    @Test fun selectAllMatchesGivesTheEditorACursorPerMatch() = runComposeUiTest {
        val view = editor(cursor = 5)
        openWithModF()
        onNodeWithTag(SearchPanelTags.FIND).performKeyInput {
            if (isApplePlatform) withKeyDown(Key.MetaLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
            else withKeyDown(Key.AltLeft) { pressKey(Key.Enter) }
        }
        waitForIdle()
        assertEquals(5, view.state.selection.ranges.size)
        assertTrue(view.focused, "the editor has the focus, to type at every match")
        editorField().performTextInput("Z")
        waitForIdle()
        assertEquals("val Z = 1\nZ(Z)\n\nZd = Z + bar\n", view.state.doc.toString())
    }
}
