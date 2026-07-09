package dev.supermux.desktop.editor

import dev.supermux.net.FsEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic port of Android's FileTree.kt sort/path-join helpers (M3 plan Task 3: "tree-node
 * sorting (dirs-first) tests"). Neither the Swift `TreeNode` type nor its test suite has an
 * equivalent helper, so this coverage is authored directly against Android's
 * `sortedForTree`/`childPath` semantics (apps/android/.../editor/FileTree.kt:42-49).
 */
class FileTreeTest {

    private fun entry(name: String, type: String, ignored: Boolean = false) =
        FsEntry(name = name, type = type, ignored = ignored)

    @Test fun sorted_for_tree_puts_directories_before_files() {
        val entries = listOf(
            entry("b.txt", "file"),
            entry("adir", "dir"),
            entry("a.txt", "file"),
        )
        assertEquals(listOf("adir", "a.txt", "b.txt"), entries.sortedForTree().map { it.name })
    }

    @Test fun sorted_for_tree_sorts_files_case_insensitively_within_the_file_group() {
        val entries = listOf(entry("Banana", "file"), entry("apple", "file"), entry("Cherry", "file"))
        assertEquals(listOf("apple", "Banana", "Cherry"), entries.sortedForTree().map { it.name })
    }

    @Test fun sorted_for_tree_sorts_directories_case_insensitively_within_the_dir_group() {
        val entries = listOf(entry("Zeta", "dir"), entry("alpha", "dir"), entry("beta.txt", "file"))
        assertEquals(listOf("alpha", "Zeta", "beta.txt"), entries.sortedForTree().map { it.name })
    }

    @Test fun sorted_for_tree_keeps_relative_order_for_case_insensitive_ties() {
        // sortedWith is a stable sort, so two entries whose lowercased names are equal keep their
        // original relative order.
        val entries = listOf(entry("dup", "dir"), entry("Dup", "dir"))
        assertEquals(listOf("dup", "Dup"), entries.sortedForTree().map { it.name })
    }

    @Test fun sorted_for_tree_on_an_empty_list_is_empty() {
        assertEquals(emptyList(), emptyList<FsEntry>().sortedForTree())
    }

    @Test fun child_path_joins_directly_under_the_tree_root_without_a_dot_slash_prefix() {
        assertEquals("src", childPath(".", "src"))
    }

    @Test fun child_path_joins_nested_dirs_with_a_slash() {
        assertEquals("src/main", childPath("src", "main"))
    }

    // ── loadAndExpand: the pure state part of toggleDir's failure handling (M3-T4 obligation 4) ────

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun editor(scope: kotlinx.coroutines.CoroutineScope) =
        EditorState(fsRead = { Result.success("") }, fsWrite = { _, _ -> true }, scope = scope)

    private fun dirNode(path: String) =
        TreeNode(entry = entry(path.substringAfterLast('/'), "dir"), path = path, children = mutableListOf())

    @Test fun load_and_expand_marks_expanded_and_clears_error_on_success() = runTest {
        val e = editor(this)
        val node = dirNode("src")
        e.treeLoadError = mapOf("src" to "stale error") // a prior error that success must clear

        loadAndExpand(e, node) { listOf(TreeNode(entry("a.kt", "file"), "src/a.kt")) }

        assertTrue("src" in e.expandedPaths)
        assertTrue(node.loaded)
        assertEquals(1, node.children!!.size)
        assertFalse("src" in e.treeLoadError)
        assertFalse("src" in e.treeLoadingPaths)
    }

    @Test fun load_and_expand_does_not_expand_and_records_error_on_failure() = runTest {
        val e = editor(this)
        val node = dirNode("src")

        loadAndExpand(e, node) { throw RuntimeException("permission denied") }

        assertFalse("src" in e.expandedPaths) // NOT expanded on failure
        assertFalse(node.loaded)
        assertEquals("permission denied", e.treeLoadError["src"])
        assertFalse("src" in e.treeLoadingPaths) // always cleared in finally
    }

    // In-flight guard: a second tap while a slow listing is loading must not launch a duplicate
    // fsList (which would double the child rows on the shared node.children list).
    @Test fun load_and_expand_is_a_no_op_when_the_dir_is_already_loading() = runTest {
        val e = editor(this)
        val node = dirNode("src")
        e.treeLoadingPaths = setOf("src") // a listing is already in flight for this path

        var listed = false
        loadAndExpand(e, node) { listed = true; emptyList() }

        assertFalse(listed) // guarded — no second listing was issued
        assertFalse("src" in e.expandedPaths) // and it did not expand off the in-flight load
    }
}
