package dev.supermux.ui.editor

import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.plugins.history.History
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Review I1: a Changes-pane revert of a file open in a tab goes through that tab's document. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangesRevertTest {
    private fun support(store: DocumentStore?, writes: MutableList<Pair<String, String>>) = NativeDiffSupport(
        readFile = { _, _ -> Result.failure(IllegalStateException()) },
        writeFile = { repo, path, text -> writes += repoPath(repo, path) to text; true },
        sideBySide = false,
        postComment = { null },
        onResolve = {},
        documents = store,
    )

    @Test fun a_revert_of_an_open_clean_file_is_one_undo_step_in_its_tab_and_saved_by_the_store() = runTest {
        val disk = mutableMapOf("lib/a.kt" to "one\r\nTWO\r\n")
        val storeWrites = mutableListOf<String>()
        val store = DocumentStore({ p -> Result.success(disk.getValue(p)) }, { p, t -> storeWrites += t; disk[p] = t; true }, backgroundScope)
        store.native = NativeEditorEnv(backgroundScope)
        store.open("lib/a.kt"); testScheduler.runCurrent()
        val native = store.nativeFor(store.get("lib/a.kt")!!)!!
        val paneWrites = mutableListOf<Pair<String, String>>()

        assertTrue(applyRevert(support(store, paneWrites), "lib", "a.kt", "one\ntwo\n", crlf = true))
        assertEquals("one\ntwo\n", native.primary.state.doc.toString())
        assertEquals(1, History.undoDepth(native.primary.state))   // the tab can undo the revert
        assertEquals(listOf("one\r\ntwo\r\n"), storeWrites)         // saved by the store, its endings
        assertTrue(paneWrites.isEmpty())                           // never a second, direct write
        assertFalse(store.isDirty("lib/a.kt"))
        native.dispose()
    }

    @Test fun a_revert_is_refused_while_the_open_document_is_dirty() = runTest {
        val store = DocumentStore({ Result.success("x\n") }, { _, _ -> true }, backgroundScope)
        store.native = NativeEditorEnv(backgroundScope)
        store.open("a.kt"); testScheduler.runCurrent()
        val native = store.nativeFor(store.get("a.kt")!!)!!
        native.primary.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "unsaved ")), userEvent = "input.type"))
        val writes = mutableListOf<Pair<String, String>>()
        assertFalse(applyRevert(support(store, writes), "", "a.kt", "y\n", crlf = false))
        assertEquals("unsaved x\n", native.primary.state.doc.toString())
        assertTrue(writes.isEmpty())
        native.dispose()
    }

    @Test fun a_revert_of_a_file_nobody_has_open_is_written_directly() = runTest {
        val writes = mutableListOf<Pair<String, String>>()
        assertTrue(applyRevert(support(null, writes), "", "b.kt", "a\nb\n", crlf = true))
        assertEquals(listOf("b.kt" to "a\r\nb\r\n"), writes)
    }
}
