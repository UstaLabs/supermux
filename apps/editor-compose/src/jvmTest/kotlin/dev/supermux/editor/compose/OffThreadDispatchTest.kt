package dev.supermux.editor.compose

import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/** EditorView.dispatch off the UI thread is detected (a lost write otherwise leaves no trace). */
class OffThreadDispatchTest {
    @Test fun aDispatchFromAnotherThreadIsCounted() {
        val view = EditorView(EditorState.create("abc"))
        val before = EditorDiagnostics.offThreadDispatches
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "x"))))
        assertEquals(before, EditorDiagnostics.offThreadDispatches, "the view's own thread is fine")
        thread { view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "y")))) }.join()
        assertEquals(before + 1, EditorDiagnostics.offThreadDispatches, "a worker thread's dispatch is detected")
    }
}
