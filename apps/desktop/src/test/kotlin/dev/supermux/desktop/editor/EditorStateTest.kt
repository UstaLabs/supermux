package dev.supermux.desktop.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FIRST Kotlin tests of the editor state model (M3 plan Task 3). Ports the semantics of
 * apps/iosApp/SupermuxTests/EditorStateTests.swift, backed by
 * apps/android/src/main/kotlin/dev/supermux/android/editor/EditorState.kt as the IMPLEMENTATION
 * reference — where Android and the Swift suite disagree in shape (see per-test notes below), these
 * tests assert Android's behavior; the disagreement is called out in the M3 Task 3 report rather
 * than silently resolved.
 *
 * `TestScope(UnconfinedTestDispatcher())` runs a `scope.launch { }` with no genuine suspension point
 * eagerly to completion before the launch call returns — the same "synchronous after await" shape
 * the Swift `async` tests rely on — so most tests below read top-to-bottom like the Swift originals
 * with no explicit scheduler driving. The two tests that need a REAL suspension (the save-guard and
 * the openFileAtLine poll) gate their fsRead/fsWrite with a [CompletableDeferred] and drive virtual
 * time explicitly via `runTest`/`advanceUntilIdle`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorStateTest {

    private fun state(
        readSucceeds: Boolean = true,
        writeSucceeds: Boolean = true,
        scope: CoroutineScope = TestScope(UnconfinedTestDispatcher()),
        onWrite: ((String, String) -> Unit)? = null,
        fsRead: suspend (String) -> Result<String> = { path ->
            if (readSucceeds) Result.success("body:$path")
            else Result.failure(RuntimeException("binary"))
        },
    ) = EditorState(
        fsRead = fsRead,
        fsWrite = { path, content ->
            onWrite?.invoke(path, content)
            writeSucceeds
        },
        scope = scope,
    )

    // ── open / activate (parity: testOpenFileAppendsAndActivates) ──────────────────────────────

    @Test fun open_file_appends_and_activates() {
        val s = state()
        s.openFile("a.kt")

        assertEquals(1, s.tabs.size)
        assertEquals("a.kt", s.tabs.first().path)
        assertEquals("body:a.kt", s.tabs.first().content)
        assertEquals("a.kt", s.activeTabPath)
        assertFalse(s.isDirty("a.kt"))
        assertNull(s.loadingPath)
        assertNull(s.loadError)
    }

    // parity: testOpeningExistingFileJustActivatesNoDuplicate
    @Test fun reopening_existing_file_just_activates_no_duplicate() {
        val s = state()
        s.openFile("a.kt")
        s.openFile("b.kt")
        s.openFile("a.kt")

        assertEquals(2, s.tabs.size)
        assertEquals("a.kt", s.activeTabPath)
    }

    // parity: testTwelveDistinctFilesKeepAllTwelveNoCap
    @Test fun twelve_distinct_files_kept_no_cap() {
        val s = state()
        for (i in 0 until 12) s.openFile("file$i.txt")

        assertEquals(12, s.tabs.size)
        assertEquals("file0.txt", s.tabs.first().path)
        assertEquals("file11.txt", s.tabs.last().path)
        assertEquals("file11.txt", s.activeTabPath)
    }

    // parity: testOpenFileFailureSetsLoadError
    @Test fun open_file_failure_sets_load_error() {
        val s = state(readSucceeds = false)
        s.openFile("x.bin")

        assertTrue(s.tabs.isEmpty())
        assertNull(s.activeTabPath)
        assertNotNull(s.loadError)
        assertNull(s.loadingPath)
    }

    // ── close (parity: testCloseTabRemovesAndSelectsNeighbor / testClosingInactiveTabKeepsActive) ─

    @Test fun close_tab_removes_and_selects_neighbor() {
        val s = state()
        s.openFile("a.txt")
        s.openFile("b.txt")
        s.openFile("c.txt")

        // Close the active (last) tab -> neighbor coerced to the new last.
        s.closeTab("c.txt")
        assertEquals(listOf("a.txt", "b.txt"), s.tabs.map { it.path })
        assertEquals("b.txt", s.activeTabPath)

        // Close a leading tab while it is active -> same-index neighbor.
        s.activeTabPath = "a.txt"
        s.closeTab("a.txt")
        assertEquals(listOf("b.txt"), s.tabs.map { it.path })
        assertEquals("b.txt", s.activeTabPath)

        // Closing the last remaining tab clears the active selection.
        s.closeTab("b.txt")
        assertTrue(s.tabs.isEmpty())
        assertNull(s.activeTabPath)
    }

    @Test fun closing_inactive_tab_keeps_active() {
        val s = state()
        s.openFile("a.txt")
        s.openFile("b.txt")
        s.activeTabPath = "b.txt"

        s.closeTab("a.txt")
        assertEquals(listOf("b.txt"), s.tabs.map { it.path })
        assertEquals("b.txt", s.activeTabPath)
    }

    @Test fun close_tab_on_an_unknown_path_is_a_no_op() {
        val s = state()
        s.openFile("a.txt")
        s.closeTab("never-opened.txt")
        assertEquals(listOf("a.txt"), s.tabs.map { it.path })
        assertEquals("a.txt", s.activeTabPath)
    }

    @Test fun select_tab_switches_active_and_clears_load_error() {
        val s = state(readSucceeds = false)
        s.openFile("bad.bin")
        assertNotNull(s.loadError)

        s.selectTab("bad.bin")
        assertNull(s.loadError)
    }

    // ── dirty / save — ADDED coverage (isDirty content-vs-savedContent + markChanged/isStale/
    //    reload semantics); the Swift file exercises isDirty via a computed `Tab.isDirty` property,
    //    Android exposes `isDirty(path)` instead — same semantics, different call shape. ──────────

    @Test fun update_content_flips_is_dirty() {
        val s = state()
        s.openFile("a.txt")
        assertFalse(s.isDirty("a.txt"))

        s.updateContent("a.txt", "changed")
        assertEquals("changed", s.activeTab?.content)
        assertTrue(s.isDirty("a.txt"))
    }

    @Test fun is_dirty_is_false_for_a_path_with_no_open_tab() {
        val s = state()
        assertFalse(s.isDirty("never-opened.txt"))
    }

    // parity: testSaveActiveClearsDirtyWhenWriteSucceeds
    @Test fun save_active_clears_dirty_when_write_succeeds() {
        var written: Pair<String, String>? = null
        val s = state(writeSucceeds = true, onWrite = { p, c -> written = p to c })
        s.openFile("a.txt")
        s.updateContent("a.txt", "edited")
        assertTrue(s.isDirty("a.txt"))

        s.saveActive()

        assertFalse(s.isDirty("a.txt"))
        assertEquals("edited", s.activeTab?.savedContent)
        assertEquals("edited", s.activeTab?.content)
        assertFalse(s.saving)
        assertEquals("a.txt" to "edited", written)
    }

    // parity: testSaveActiveKeepsDirtyWhenWriteFails
    @Test fun save_active_keeps_dirty_when_write_fails() {
        val s = state(writeSucceeds = false)
        s.openFile("a.txt")
        s.updateContent("a.txt", "edited")

        s.saveActive()

        assertTrue(s.isDirty("a.txt"))
        assertEquals("body:a.txt", s.activeTab?.savedContent)
        assertFalse(s.saving)
    }

    @Test fun save_active_is_a_no_op_when_there_is_no_active_tab() {
        val s = state()
        s.saveActive() // no active tab at all -> must not throw
        assertFalse(s.saving)
    }

    // ADDED — Android's `if (saving) return` re-entrancy guard has no Swift-side test.
    @Test fun save_active_is_a_no_op_while_already_saving() {
        var writeCalls = 0
        val gate = CompletableDeferred<Unit>()
        val s = EditorState(
            fsRead = { path -> Result.success("body:$path") },
            fsWrite = { _, _ -> writeCalls++; gate.await(); true },
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        s.openFile("a.txt")

        s.saveActive() // starts; suspends on the gate mid-write
        assertTrue(s.saving)

        s.saveActive() // re-entrant call while saving == true -> must not start a second write
        assertEquals(1, writeCalls)

        gate.complete(Unit)
        assertFalse(s.saving)
    }

    // ── markChanged / isStale / reload — ADDED coverage; the Swift suite's testReloadRefreshes-
    //    ContentAndClearsDirty covers the read-refresh only, not the stale-flag lifecycle around it.

    @Test fun mark_changed_flags_paths_as_stale_and_normalizes_a_leading_slash() {
        val s = state()
        assertFalse(s.isStale("src/a.kt"))

        s.markChanged(listOf("src/a.kt", "/src/b.kt"))

        assertTrue(s.isStale("src/a.kt"))
        assertTrue(s.isStale("src/b.kt"))
        assertTrue(s.isStale("/src/b.kt")) // isStale normalizes its own query too
    }

    @Test fun reload_refreshes_content_clears_dirty_and_stale_flag() = runTest {
        val s = state()
        s.openFile("a.txt")
        s.updateContent("a.txt", "local edit")
        s.markChanged(listOf("a.txt"))
        assertTrue(s.isDirty("a.txt"))
        assertTrue(s.isStale("a.txt"))

        s.reload("a.txt") { path -> Result.success("body:$path") }

        assertEquals("body:a.txt", s.activeTab?.content)
        assertEquals("body:a.txt", s.activeTab?.savedContent)
        assertFalse(s.isDirty("a.txt"))
        assertFalse(s.isStale("a.txt"))
        assertNull(s.loadingPath)
    }

    @Test fun reload_is_a_no_op_for_a_path_with_no_open_tab() = runTest {
        val s = state()
        s.reload("never-opened.txt") { path -> Result.success("body:$path") }
        assertTrue(s.tabs.isEmpty())
        assertNull(s.loadingPath)
    }

    @Test fun reload_failure_sets_load_error_and_leaves_the_stale_flag_untouched() = runTest {
        val s = state()
        s.openFile("a.txt")
        s.markChanged(listOf("a.txt"))

        s.reload("a.txt") { Result.failure(RuntimeException("disk gone")) }

        assertNotNull(s.loadError)
        assertTrue(s.isStale("a.txt")) // Android's failure path never clears changedPaths
        assertNull(s.loadingPath)
    }

    // ── captureActiveScroll — ADDED. Android acts on the ACTIVE tab implicitly; the Swift port
    //    instead exposes a per-path `setScroll(path, top)`. Android is the impl reference here (see
    //    the M3 Task 3 report for the flagged parity conflict). ─────────────────────────────────

    @Test fun capture_active_scroll_stores_on_the_active_tab_only() {
        val s = state()
        s.openFile("a.txt")
        s.openFile("b.txt") // b.txt is now active

        s.captureActiveScroll(99)

        assertEquals(99, s.tabs.find { it.path == "b.txt" }?.scrollTop)
        assertEquals(0, s.tabs.find { it.path == "a.txt" }?.scrollTop)
    }

    // ── openFileAtLine — ADDED (no Swift-side test of the pending-reveal poll). ─────────────────

    @Test fun open_file_at_line_sets_reveal_immediately_when_the_tab_is_already_open() {
        val s = state()
        s.openFile("a.kt")

        s.openFileAtLine("a.kt", 42, null)

        assertEquals(42 to null, s.tabs.first().revealLine)
    }

    @Test fun open_file_at_line_with_a_null_line_just_opens_without_a_reveal() {
        val s = state()
        s.openFileAtLine("a.kt", null, null)

        assertEquals("a.kt", s.activeTabPath)
        assertNull(s.tabs.first().revealLine)
    }

    @Test fun open_file_at_line_polls_until_the_tab_arrives_then_sets_reveal() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = EditorState(
            fsRead = { path -> gate.await(); Result.success("body:$path") },
            fsWrite = { _, _ -> true },
            scope = this,
        )

        s.openFileAtLine("a.kt", 10, 12)
        // fsRead hasn't resolved yet -> openFile added no tab, so this took the polling branch
        // rather than setting revealLine synchronously.
        assertTrue(s.tabs.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()

        val tab = s.tabs.find { it.path == "a.kt" }
        assertNotNull(tab)
        assertEquals(10 to 12, tab.revealLine)
    }

    // ── tree / search UI state defaults — ADDED (search-query state + tree state, per plan Task 3;
    //    sortedForTree ordering itself is covered separately in FileTreeTest.kt). ─────────────────

    @Test fun tree_and_search_ui_state_defaults() {
        val s = state()
        assertTrue(s.treeRoot.isEmpty())
        assertFalse(s.treeRootLoaded)
        assertTrue(s.expandedPaths.isEmpty())
        assertTrue(s.treeLoadingPaths.isEmpty())
        assertNull(s.treeVisible)
        assertTrue(s.changedPaths.isEmpty())
        assertEquals("", s.searchQuery)
    }

    @Test fun search_query_is_plain_mutable_state() {
        val s = state()
        s.searchQuery = "foo"
        assertEquals("foo", s.searchQuery)
    }
}
