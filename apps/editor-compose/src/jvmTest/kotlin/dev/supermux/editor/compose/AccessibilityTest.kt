package dev.supermux.editor.compose

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AccessibilityTest {
    private val text = (0 until 400).joinToString("\n") { "line $it text" }

    private fun androidx.compose.ui.test.ComposeUiTest.editor(): SemanticsNode =
        onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and !hasSetTextAction()).fetchSemanticsNode()
    private fun SemanticsNode.exposed(): String = assertNotNull(config.getOrNull(SemanticsProperties.EditableText)).text

    @Test fun theEditorIsOneEditableNodeWithTheVisibleLinesOnly() = editorTest(EditorState.create(text)) { f ->
        val node = editor()
        val exposed = node.exposed()
        val drawn = f.controller.geometry.visibleLines(0f, f.controller.viewportSize.height, 0)
        assertEquals((drawn.first..drawn.last).joinToString("\n") { "line $it text" }, exposed)
        assertTrue(exposed.length < text.length / 10, "the whole document was exposed")
        assertEquals(listOf(EditorSemantics.LABEL), node.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(TextRange(0), node.config.getOrNull(SemanticsProperties.TextSelectionRange))
        assertTrue(node.config.getOrNull(SemanticsProperties.IsEditable) == true)
        // The gutter is drawn, never read: no line number is in the text on its own.
        assertFalse(exposed.lines().any { it.trim().toIntOrNull() != null }, "line numbers exposed")
    }

    @Test fun theHiddenFieldIsNotInTheTreeAtAll() = editorTest(EditorState.create(text), exposeField = false) { _ ->
        // iOS and the web ignore hideFromAccessibility: the field's semantics are cleared instead.
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty(), "the IME field is in the semantics tree")
        val editable = onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).fetchSemanticsNodes()
        assertEquals(1, editable.size, "exactly one editable text element")
    }

    @Test fun theTextNodeIsNotTheScrollNode() = editorTest(EditorState.create(text)) { _ ->
        // macOS maps a node with scroll semantics to an AXScrollArea and drops its text.
        val node = editor()
        assertFalse(node.config.contains(SemanticsActions.ScrollBy), "the text node is the scroll node")
        assertTrue(node.parent?.config?.contains(SemanticsActions.ScrollBy) == true, "the scroll node is not its parent")
    }

    @Test fun theSelectionAndACaretMoveAreExposed() = editorTest(EditorState.create(text, EditorSelection.single(2, 6))) { f ->
        assertEquals(TextRange(2, 6), editor().config.getOrNull(SemanticsProperties.TextSelectionRange))
        DefaultCommands.cursorDown.run(f.view)
        waitForIdle()
        val head = f.view.state.selection.main.head
        assertEquals(f.view.state.doc.lineIndexAt(head), 1)
        assertEquals(TextRange(head), editor().config.getOrNull(SemanticsProperties.TextSelectionRange))
        // The move to another line is said once.
        assertEquals("line 2: line 1 text", f.controller.announcer.text)
        // Typing on that line does not repeat it.
        f.view.typeText("x")
        waitForIdle()
        assertEquals("line 2: line 1 text", f.controller.announcer.text)
    }

    @Test fun aCaretFarAwayBringsItsLineAlong() = editorTest(EditorState.create(text)) { f ->
        val at = f.view.state.doc.lineStart(300) + 4
        f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(at)))
        waitForIdle()
        // No scrollIntoView: the top is still on screen, and line 300 is exposed after it.
        val exposed = editor().exposed()
        assertTrue(exposed.endsWith("\nline 300 text"), "exposed: …${exposed.takeLast(40)}")
        val sel = assertNotNull(editor().config.getOrNull(SemanticsProperties.TextSelectionRange))
        assertEquals(exposed.length - "line 300 text".length + 4, sel.start)
    }

    @Test fun scrollingExposesTheNewLines() = editorTest(EditorState.create(text)) { f ->
        f.controller.scroll.scrollBy(0f, f.controller.layouts.lineHeightPx * 100)
        waitForIdle()
        // The caret stays on line 0, so its line comes first, then the visible ones.
        val exposed = editor().exposed()
        assertTrue(exposed.startsWith("line 0 text\nline 100 text\n"), "after scrolling: ${exposed.take(40)}")
        assertFalse(exposed.contains("line 50 text"))
    }

    @Test fun theSetSelectionActionMovesTheDocumentSelection() = editorTest(EditorState.create(text)) { f ->
        val action = assertNotNull(editor().config.getOrNull(SemanticsActions.SetSelection)?.action)
        runOnUiThread { action(8, 11, false) }
        waitForIdle()
        assertEquals(EditorSelection.single(8, 11), f.view.state.selection)
        val type = assertNotNull(editor().config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action)
        runOnUiThread { type(androidx.compose.ui.text.AnnotatedString("Z")) }
        waitForIdle()
        assertTrue(f.view.state.doc.toString().startsWith("line 0 tZ\n"), f.view.state.doc.toString().take(12))
    }

    @Test fun theLabelIsTheContentDescriptionAndReadOnlyIsNotEditable() = editorTest(EditorState.create(text), readOnly = true, exposeField = false) { _ ->
        val node = editor()
        assertFalse(node.config.getOrNull(SemanticsProperties.IsEditable) == true)
        assertTrue(node.config.getOrNull(SemanticsActions.InsertTextAtCursor) == null, "typing offered while read-only")
        assertNotNull(node.config.getOrNull(SemanticsActions.CopyText))
    }
}
