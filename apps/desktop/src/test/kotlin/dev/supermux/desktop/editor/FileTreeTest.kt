package dev.supermux.desktop.editor

import dev.supermux.net.FsEntry
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
