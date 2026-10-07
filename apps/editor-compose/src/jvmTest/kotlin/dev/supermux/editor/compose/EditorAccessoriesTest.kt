package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommandAvailability
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.NamedCommand
import dev.supermux.editor.core.commandAvailabilityFacet
import dev.supermux.editor.core.commandsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The mobile accessory bar: its buttons run editor commands and never take the focus. */
@OptIn(ExperimentalTestApi::class)
class EditorAccessoriesTest {
    private val text = "fun f() {\n    x\n}\nline four\n"

    private class Runs { val log = ArrayList<String>(); var canUndo = false }

    /** An editor with fake "history" and "search" named commands, and the bar under it. */
    private fun ComposeUiTest.show(runs: Runs, visibility: AccessoryVisibility = AccessoryVisibility.ALWAYS, extra: dev.supermux.editor.core.Extension = extensionOf()): Pair<EditorView, RecordingKeyboard> {
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(0), extensionOf(
            commandsFacet.of(listOf(
                NamedCommand("history.undo", "Undo", Command { runs.log += "undo"; true }),
                NamedCommand("history.redo", "Redo", Command { runs.log += "redo"; true }),
                NamedCommand("search.open", "Find", Command { runs.log += "find"; true }),
            )),
            commandAvailabilityFacet.of(CommandAvailability { _, id -> when (id) { "history.undo" -> runs.canUndo; "history.redo" -> false; else -> null } }),
            extra,
        )))
        val keyboard = RecordingKeyboard()
        setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard, LocalEditorCursorBlink provides false) {
                Column(Modifier.width(700.dp)) {
                    Editor(view, Modifier.fillMaxWidth().height(300.dp))
                    EditorAccessories(view, visibility = visibility)
                }
            }
        }
        waitForIdle()
        onNode(hasEditorField()).requestFocus()
        waitForIdle()
        return view to keyboard
    }

    private val EditorView.head get() = state.selection.main.head

    @Test fun theArrowsAndTabRunTheEditorsCommandsAndTheFieldKeepsTheFocus() = runComposeUiTest {
        val (v, _) = show(Runs())
        assertTrue(v.focused)
        onNodeWithTag(AccessoryTags.RIGHT).performClick(); waitForIdle()
        onNodeWithTag(AccessoryTags.RIGHT).performClick(); waitForIdle()
        assertEquals(2, v.head)
        onNodeWithTag(AccessoryTags.DOWN).performClick(); waitForIdle()
        assertEquals(1, v.state.doc.lineIndexAt(v.head))
        onNodeWithTag(AccessoryTags.LEFT).performClick(); waitForIdle()
        onNodeWithTag(AccessoryTags.UP).performClick(); waitForIdle()
        assertEquals(0, v.state.doc.lineIndexAt(v.head))
        // Tab inserts up to the next indent stop at the caret (column 1 -> 4); Shift-Tab takes a unit off the line.
        onNodeWithTag(AccessoryTags.TAB).performClick(); waitForIdle()
        assertEquals("f   un f() {", v.state.doc.line(1).text)
        v.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.cursor(v.state.doc.lineStart(1) + 5)))
        onNodeWithTag(AccessoryTags.SHIFT_TAB).performClick(); waitForIdle()
        assertEquals("x", v.state.doc.line(2).text)
        assertTrue(v.focused, "the editor kept the focus through every button")
        onNode(hasEditorField()).assertIsFocused()
    }

    @Test fun aHeldArrowRepeats() = runComposeUiTest {
        val (v, _) = show(Runs())
        mainClock.autoAdvance = false
        onNodeWithTag(AccessoryTags.RIGHT).performTouchInput { down(center) }
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag(AccessoryTags.RIGHT).performTouchInput { up() }
        mainClock.autoAdvance = true
        waitForIdle()
        assertTrue(v.head >= 8, "held for a second: moved ${v.head}")
        assertTrue(v.focused)
    }

    @Test fun undoIsDisabledUntilThereIsSomethingToUndoAndFindRunsTheSearchCommand() = runComposeUiTest {
        val runs = Runs()
        val (v, _) = show(runs)
        onNodeWithTag(AccessoryTags.UNDO).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        onNodeWithTag(AccessoryTags.UNDO).performClick(); waitForIdle()
        assertEquals(emptyList(), runs.log, "disabled: nothing ran")
        runs.canUndo = true
        v.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.cursor(1))) // any transaction re-reads it
        waitForIdle()
        onNodeWithTag(AccessoryTags.UNDO).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
        onNodeWithTag(AccessoryTags.UNDO).performClick(); waitForIdle()
        onNodeWithTag(AccessoryTags.REDO).performClick(); waitForIdle()
        onNodeWithTag(AccessoryTags.FIND).performClick(); waitForIdle()
        assertEquals(listOf("undo", "find"), runs.log, "redo can't run; undo and find ran")
        assertTrue(v.focused)
    }

    @Test fun aKeysBindingWinsOverTheDefault() = runComposeUiTest {
        // A plugin that binds Tab (a snippet's next field, a completion's accept): the bar runs it.
        val runs = Runs()
        val (v, _) = show(runs, extra = keymapOf(KeyBinding("Tab", Command { runs.log += "plugin tab"; true })))
        onNodeWithTag(AccessoryTags.TAB).performClick(); waitForIdle()
        assertEquals(listOf("plugin tab"), runs.log)
        assertEquals(text, v.state.doc.toString())
    }

    @Test fun hideKeyboardHidesItAndKeepsTheFocus() = runComposeUiTest {
        val (v, kb) = show(Runs())
        onNodeWithTag(AccessoryTags.HIDE_KEYBOARD).performClick(); waitForIdle()
        assertEquals(1, kb.hides.get())
        assertTrue(v.focused)
    }

    @Test fun automaticallyItShowsOnlyWithASoftKeyboardUp() = runComposeUiTest {
        // The desktop has no soft keyboard (no IME inset): focused or not, no bar.
        val (v, _) = show(Runs(), visibility = AccessoryVisibility.AUTO)
        assertTrue(v.focused)
        onNodeWithTag(AccessoryTags.BAR).assertDoesNotExist()
        onNodeWithTag(AccessoryTags.RIGHT).assertDoesNotExist()
    }

    @Test fun aSwipeThatStartsOnAKeyRunsNothing() = runComposeUiTest {
        val runs = Runs().also { it.canUndo = true }
        val (v, _) = show(runs)
        v.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.cursor(1)))
        waitForIdle()
        onNodeWithTag(AccessoryTags.UNDO).performTouchInput { swipe(start = center, end = androidx.compose.ui.geometry.Offset(center.x - 200f, center.y)) }
        waitForIdle()
        assertEquals(emptyList(), runs.log, "a swipe over Undo does not undo")
        val head = v.head
        onNodeWithTag(AccessoryTags.RIGHT).performTouchInput { swipe(start = center, end = androidx.compose.ui.geometry.Offset(center.x - 200f, center.y)) }
        waitForIdle()
        assertEquals(head, v.head, "a swipe over an arrow does not move the caret")
        // A plain tap still runs: on the finger's lift.
        onNodeWithTag(AccessoryTags.UNDO).performTouchInput { down(center) }
        waitForIdle()
        assertEquals(emptyList(), runs.log, "nothing on touch-down")
        onNodeWithTag(AccessoryTags.UNDO).performTouchInput { up() }
        waitForIdle()
        assertEquals(listOf("undo"), runs.log)
    }
}
