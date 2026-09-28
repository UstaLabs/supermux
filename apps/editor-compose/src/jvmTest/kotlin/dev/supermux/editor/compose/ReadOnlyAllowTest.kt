package dev.supermux.editor.compose

import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals

/** [readOnlyAllowFacet]: a read-only view applies only the user events it names (a review surface's revert). */
class ReadOnlyAllowTest {
    @Test fun aReadOnlyViewDropsTypingButAppliesAnAllowedEvent() {
        val view = EditorView(EditorState.create("abc", extensions = readOnlyAllowFacet.of("edit.revert")))
        view.readOnly = true
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "x")), userEvent = "input.type"))
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "y")), userEvent = "paste"))
        assertEquals("abc", view.state.doc.toString())
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 1, "A")), userEvent = "edit.revert"))
        assertEquals("Abc", view.state.doc.toString())
        // Programmatic changes (no userEvent) still apply, as before.
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(3, 3, "!"))))
        assertEquals("Abc!", view.state.doc.toString())
    }
}
