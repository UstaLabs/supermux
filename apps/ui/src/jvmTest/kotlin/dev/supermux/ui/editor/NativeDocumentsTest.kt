package dev.supermux.ui.editor

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.ViewPlugin
import dev.supermux.editor.compose.ViewPluginInstance
import dev.supermux.editor.compose.viewPluginsFacet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.plugins.history.History
import dev.supermux.editor.plugins.view.EditorSettings
import dev.supermux.editor.plugins.view.ViewSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The per-document EditorView store (M5 A2): one view per open [Document], owned next to the
 * [DocumentStore] and outliving any pane; CRLF round trip; dirty against the saved version; reload
 * as ONE `disk` transaction; two panes on one path as two mirrored views.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeDocumentsTest {
    private class Disk(vararg initial: Pair<String, String>) {
        val files = mutableMapOf(*initial)
        val writes = mutableListOf<Pair<String, String>>()
        val read: suspend (String) -> Result<String> = { p -> files[p]?.let { Result.success(it) } ?: Result.failure(IllegalStateException("no $p")) }
        val write: suspend (String, String) -> Boolean = { p, t -> writes += p to t; files[p] = t; true }
    }

    /** Counts its instances' lives, so a test sees when a view's plugins run and stop. */
    private class Lives {
        var started = 0
        var destroyed = 0
        val plugin = ViewPlugin { _ -> started++; object : ViewPluginInstance { override fun destroy() { destroyed++ } } }
    }

    private fun store(disk: Disk, scope: CoroutineScope = TestScope(UnconfinedTestDispatcher()), lives: Lives? = null, settings: EditorSettings = EditorSettings(fontSize = 13f, lineWrap = true)): DocumentStore =
        DocumentStore(disk.read, disk.write, scope).also { s ->
            s.native = NativeEditorEnv(
                scope = scope,
                settings = settings,
                extraExtensions = { lives?.let { viewPluginsFacet.of(it.plugin) } ?: dev.supermux.editor.core.extensionOf() },
            )
        }

    private fun EditorView.type(at: Int, text: String) =
        dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, text)), selection = EditorSelection.cursor(at + text.length), userEvent = "input.type"))

    private fun DocumentStore.view(path: String): NativeDocument = assertNotNull(nativeFor(assertNotNull(get(path))))

    @Test fun a_crlf_file_opens_as_lf_and_saves_back_as_crlf() = runTest {
        val disk = Disk("a.kt" to "one\r\ntwo\r\n")
        val s = store(disk, this)
        s.open("a.kt"); testScheduler.advanceUntilIdle()

        val n = s.view("a.kt")
        assertEquals("one\ntwo\n", n.primary.state.doc.toString())
        assertFalse(s.isDirty("a.kt"))

        n.primary.type(3, "!")
        assertTrue(s.isDirty("a.kt"))
        s.save(s.get("a.kt")!!); testScheduler.advanceUntilIdle()

        assertEquals("one!\r\ntwo\r\n", disk.writes.single().second)
        assertFalse(s.isDirty("a.kt"))
        n.dispose()
    }

    @Test fun the_document_text_is_the_view_and_dirty_compares_with_the_saved_version() {
        val s = store(Disk("a.kt" to "abc"))
        s.open("a.kt")
        val doc = s.get("a.kt")!!
        val n = s.view("a.kt")

        n.primary.type(3, "d")
        assertEquals("abcd", doc.content)          // derived from the view, no String kept per keystroke
        assertTrue(doc.isDirty)

        // Undo back to the saved text: clean again (content, not history, decides).
        History.undo.run(n.primary)
        assertEquals("abc", doc.content)
        assertFalse(doc.isDirty)

        // A same-length edit is dirty too.
        n.primary.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 1, "X")), userEvent = "input.type"))
        assertTrue(doc.isDirty)
        n.dispose()
    }

    @Test fun an_edit_typed_while_a_save_is_in_flight_stays_dirty() = runTest {
        val disk = Disk("a.kt" to "abc")
        val s = store(disk, this)
        s.open("a.kt"); testScheduler.advanceUntilIdle()
        val n = s.view("a.kt")
        n.primary.type(3, "d")

        s.save(s.get("a.kt")!!)          // launched, not yet run
        n.primary.type(4, "e")           // typed before the write lands
        testScheduler.advanceUntilIdle()

        assertEquals("abcd", disk.writes.single().second)
        assertTrue(s.isDirty("a.kt"))    // "abcde" was never saved
        n.dispose()
    }

    @Test fun reload_replaces_the_text_in_one_disk_transaction_and_keeps_the_caret_near_its_line() = runTest {
        val disk = Disk("a.kt" to "line1\nline2\nline3\n")
        val s = store(disk, this)
        s.open("a.kt"); testScheduler.advanceUntilIdle()
        val n = s.view("a.kt")
        n.primary.dispatch(TransactionSpec(selection = EditorSelection.cursor(14)))  // in line3
        val seen = mutableListOf<Transaction>()
        n.primary.addListener { if (it.docChanged) seen += it }
        s.markChanged(listOf("a.kt"))

        disk.files["a.kt"] = "line0\r\nline1\r\nline2\r\nline3\r\n"
        s.reload("a.kt", disk.read)

        assertEquals("line0\nline1\nline2\nline3\n", n.primary.state.doc.toString())
        assertEquals(1, seen.size)
        assertTrue(seen.single().isUserEvent("disk"))
        assertEquals(20, n.primary.state.selection.main.head)                  // still in line3, mapped
        assertFalse(s.isDirty("a.kt"))
        assertFalse(s.isStale("a.kt"))
        // The reload's ending is the one saved from now on.
        n.primary.type(0, "x"); s.save(s.get("a.kt")!!); testScheduler.advanceUntilIdle()
        assertEquals("xline0\r\nline1\r\nline2\r\nline3\r\n", disk.writes.last().second)
        n.dispose()
    }

    @Test fun a_reload_is_never_an_undo_step() = runTest {
        val disk = Disk("a.kt" to "a\n")
        val s = store(disk, this)
        s.open("a.kt"); testScheduler.advanceUntilIdle()
        val n = s.view("a.kt")
        disk.files["a.kt"] = "b\n"
        s.reload("a.kt", disk.read)
        assertEquals(0, History.undoDepth(n.primary.state))
        n.dispose()
    }

    @Test fun the_view_outlives_its_pane_and_is_released_only_when_the_document_closes() {
        val lives = Lives()
        val s = store(Disk("a.kt" to "abc"), lives = lives)
        s.open("a.kt")
        val n = s.view("a.kt")
        n.start()
        assertEquals(1, lives.started)

        // A pane borrows the view and gives it back (a tab switch): the view and its plugins stay.
        val v = n.acquire()
        n.release(v)
        assertSame(n, s.nativeFor(s.get("a.kt")!!))
        assertSame(v, n.acquire())
        assertEquals(0, lives.destroyed)

        s.close("a.kt")
        assertEquals(1, lives.destroyed)
        assertTrue(n.disposed)
    }

    @Test fun two_panes_on_one_path_get_two_views_that_mirror_each_other() {
        val s = store(Disk("a.kt" to "abc"))
        s.open("a.kt")
        val n = s.view("a.kt")
        val first = n.acquire()
        val second = n.acquire()
        assertNotSame(first, second)

        first.type(3, "d")
        assertEquals("abcd", second.state.doc.toString())
        second.type(0, ">")
        assertEquals(">abcd", first.state.doc.toString())
        assertEquals(">abcd", s.get("a.kt")!!.content)

        // Each view undoes only its own edits (the mirrored ones are remote).
        assertEquals(1, History.undoDepth(first.state))
        assertEquals(1, History.undoDepth(second.state))

        // The second pane goes: its mirror stops following.
        n.release(second)
        first.type(0, "#")
        assertEquals(">abcd", second.state.doc.toString())
        n.dispose()
    }

    @Test fun settings_reach_every_open_view() {
        val s = store(Disk("a.kt" to "a", "b.kt" to "b", "c.kt" to "c"))
        s.open("a.kt"); s.open("b.kt")
        val a = s.view("a.kt")
        val b = s.view("b.kt")
        s.applySettings(EditorSettings(fontSize = 17f, lineWrap = false))
        for (n in listOf(a, b)) {
            val current = assertNotNull(ViewSettings.current(n.primary.state))
            assertEquals(17f, current.fontSize)
            assertFalse(current.lineWrap)
        }
        // A view made later starts with them.
        s.open("c.kt")
        val c = s.view("c.kt")
        assertEquals(17f, ViewSettings.current(c.primary.state)?.fontSize)
        a.dispose(); b.dispose(); c.dispose()
    }

    @Test fun without_a_native_env_the_store_keeps_plain_strings() {
        val s = DocumentStore(Disk("a.kt" to "x\r\ny").read, { _, _ -> true }, TestScope(UnconfinedTestDispatcher()))
        s.open("a.kt")
        val doc = s.get("a.kt")!!
        assertEquals("x\ny", doc.content)
        assertEquals(null, s.nativeFor(doc))
        s.update("a.kt", "z")
        assertTrue(s.isDirty("a.kt"))
    }
}
