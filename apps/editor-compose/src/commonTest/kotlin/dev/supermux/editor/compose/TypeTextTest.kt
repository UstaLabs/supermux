package dev.supermux.editor.compose

import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.ChangeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** EditorView.typeText: the one entry point every kind of typed text goes through. */
class TypeTextTest {
    @Test fun typesAtEveryRange() {
        val v = EditorView(EditorState.create("a\nb", EditorSelection.create(listOf(SelectionRange(1), SelectionRange(3)))))
        assertTrue(v.typeText("!"))
        assertEquals("a!\nb!", v.state.doc.toString())
    }

    @Test fun anInputHandlerCanTakeTheTextOver() {
        // A closeBrackets-like plugin: "(" types "()" with the caret between.
        val closeBrackets = inputHandlerFacet.of(InputHandler { t, from, to, text ->
            if (text != "(") return@InputHandler false
            t.dispatch(TransactionSpec(changes = listOf(ChangeSpec(from, to, "()")), selection = EditorSelection.cursor(from + 1), userEvent = "input"))
            true
        })
        val v = EditorView(EditorState.create("x", EditorSelection.cursor(1), closeBrackets))
        v.typeText("(")
        assertEquals("x()", v.state.doc.toString())
        assertEquals(2, v.state.selection.main.head)
        v.typeText("a")
        assertEquals("x(a)", v.state.doc.toString())
        // Through the hidden field too: the field's typing is typeText.
        val w = EditorView(EditorState.create("x", EditorSelection.cursor(1), closeBrackets))
        val sync = FieldSync(w)
        sync.initialField()
        sync.onFieldChange("x(", 2, 2, null)
        assertEquals("x()", w.state.doc.toString())
    }
}
