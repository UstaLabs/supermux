package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.Prec
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.keymapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
/** The M3b review's proofs (r1-r5), kept as regression tests. */
class ReviewRegressionTest {
    @Test fun aDocumentSwitchAfterAProgrammaticFocusAsksForNoKeyboard() = runComposeUiTest {
        val a = EditorView(EditorState.create("alpha"))
        val b = EditorView(EditorState.create("beta"))
        var second by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides RecordingKeyboard(), LocalEditorInputOnAnyFocus provides false) {
                Box(Modifier.size(300.dp, 200.dp)) { Editor(if (second) b else a, Modifier.fillMaxSize().testTag(EDITOR_TAG), clipboard = FakeClipboard()) }
            }
        }
        waitForIdle()
        runOnUiThread { a.focus() }
        waitForIdle()
        second = true
        waitForIdle()
        val c = b.surface as EditorController
        assertEquals(false, c.keyboardOnFocus, "a document switch after a programmatic (no-keyboard) focus asks for the soft keyboard")
    }

    @Test fun aDocumentSwitchAfterATouchKeepsTheKeyboardSession() = runComposeUiTest {
        val a = EditorView(EditorState.create("alpha"))
        val b = EditorView(EditorState.create("beta"))
        var second by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides RecordingKeyboard(), LocalEditorInputOnAnyFocus provides false) {
                Box(Modifier.size(300.dp, 200.dp)) { Editor(if (second) b else a, Modifier.fillMaxSize().testTag(EDITOR_TAG), clipboard = FakeClipboard()) }
            }
        }
        waitForIdle()
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(androidx.compose.ui.geometry.Offset(40f, 10f)) }
        waitForIdle()
        assertEquals(true, (a.surface as EditorController).keyboardOnFocus)
        second = true
        waitForIdle()
        assertEquals(true, (b.surface as EditorController).keyboardOnFocus, "a switch after a touch dropped the keyboard session")
        assertEquals(true, b.focused)
    }

    @Test fun endOnAWrappedTokenStaysOnItsRow() = editorTest(EditorState.create("x".repeat(300) + "\nnext"), widthDp = 300, lineWrap = true) { f ->
        val layout = f.geometry.lineLayout(0)
        val rowStart = layout.getLineStart(1)
        runOnUiThread { f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(rowStart + 3))) }
        val heads = ArrayList<Int>()
        val rows = ArrayList<Int>()
        repeat(4) {
            runOnUiThread { DefaultCommands.cursorLineEnd.run(f.view) }
            val h = f.view.state.selection.main.head
            heads += h; rows += layout.getLineForOffset(h)
        }
        assertEquals(1, rows[0], "End put the caret on the NEXT visual row")
        assertEquals(300, heads[1], "End twice did not reach the line's end")
    }

    @Test fun setTextAcrossTheJoinerOfFarApartLinesIsRefused() = editorTest(EditorState.create((0 until 400).joinToString("\n") { "line $it text" })) { f ->
        val at = f.view.state.doc.lineStart(300) + 4
        runOnUiThread { f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(at))) }
        waitForIdle()
        val node = onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).fetchSemanticsNode()
        val exposed = assertNotNull(node.config.getOrNull(SemanticsProperties.EditableText)).text
        val i = exposed.lastIndexOf('\n')
        val action = assertNotNull(node.config.getOrNull(SemanticsActions.SetText)?.action)
        // An AT joins the caret's line to the line shown above it (a Backspace at its start, sent as SetText).
        runOnUiThread { action(androidx.compose.ui.text.AnnotatedString(exposed.substring(0, i) + exposed.substring(i + 1))) }
        waitForIdle()
        // The removed "\n" joins two lines that are not neighbours in the document (the text between
        // them was never exposed): the edit is refused, nothing is deleted.
        assertEquals(400, f.view.state.doc.lineCount, "SetText deleted lines that were never exposed")
    }

    @Test fun aSoftReturnHandledWithoutAnEditLeavesNoPhantomNewline() {
        val swallow = Prec.high(keymapOf(KeyBinding("Enter", Command { true })))
        val view = EditorView(EditorState.create("ab", EditorSelection.cursor(1), swallow))
        val sync = FieldSync(view, 20, 4)
        var field = sync.initialField()
        view.addListener { sync.onStateChange()?.let { field = it } }
        fun type(s: String) {
            val t = field.text.substring(0, field.selStart) + s + field.text.substring(field.selEnd)
            val sel = field.selStart + s.length
            field = FieldText(t, sel, sel)
            sync.onFieldChange(t, sel, sel, null)?.let { field = it }
        }
        type("\n")
        type("x")
        assertEquals("axb", view.state.doc.toString(), "the swallowed Return reached the document on the next keystroke")
    }

    @Test fun setStateDropsTheOldDocumentsPieceWidths() {
        val cjk = "中".repeat(12_000)
        val ascii = "a".repeat(12_000)
        var fresh = 0f
        editorTest(EditorState.create(ascii)) { f -> fresh = f.geometry.rectFor(11_500).left }
        editorTest(EditorState.create(cjk)) { f ->
            // Lay out the first pieces of the CJK line (as painting and scrolling do).
            runOnUiThread { for (o in 100 until 12_000 step 1_000) f.geometry.rectFor(o) }
            runOnUiThread { f.view.setState(EditorState.create(ascii)) }
            waitForIdle()
            val after = f.geometry.rectFor(11_500).left
            assertEquals(fresh, after, 1f, "a replaced document kept the old one's piece widths")
        }
    }
}
