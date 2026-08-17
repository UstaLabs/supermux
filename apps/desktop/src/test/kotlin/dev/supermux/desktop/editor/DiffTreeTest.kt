package dev.supermux.desktop.editor

import dev.supermux.net.DiffFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure-function tests for the desktop-only diff file tree (folder nesting derived from
 * [DiffFile.path], no filesystem). Sort order matches [sortedForTree]: directories first,
 * then files, case-insensitive within each group.
 */
class DiffTreeTest {

    private fun file(path: String, diff: String = "@@ -0,0 +1 @@\n+x\n") =
        DiffFile(path = path, status = "modified", diff = diff)

    private fun names(nodes: List<DiffTreeNode>): List<String> = nodes.map { it.name }

    @Test fun empty_input_is_an_empty_tree() {
        assertEquals(emptyList(), buildDiffTree(emptyList()))
    }

    @Test fun a_root_file_is_a_file_node_at_the_top_level() {
        val tree = buildDiffTree(listOf(file("README.md")))
        assertEquals(1, tree.size)
        val node = assertIs<DiffTreeNode.File>(tree.single())
        assertEquals("README.md", node.name)
        assertEquals("README.md", node.path)
        assertEquals("README.md", node.file.path)
    }

    @Test fun a_nested_path_builds_folder_nodes_down_to_the_file() {
        val tree = buildDiffTree(listOf(file("src/main/Foo.kt")))
        val src = assertIs<DiffTreeNode.Folder>(tree.single())
        assertEquals("src", src.name)
        assertEquals("src", src.path)
        val main = assertIs<DiffTreeNode.Folder>(src.children.single())
        assertEquals("main", main.name)
        assertEquals("src/main", main.path)
        val foo = assertIs<DiffTreeNode.File>(main.children.single())
        assertEquals("Foo.kt", foo.name)
        assertEquals("src/main/Foo.kt", foo.path)
    }

    @Test fun files_in_the_same_folder_share_one_folder_node() {
        val tree = buildDiffTree(listOf(file("src/a.kt"), file("src/b.kt")))
        val src = assertIs<DiffTreeNode.Folder>(tree.single())
        assertEquals(listOf("a.kt", "b.kt"), names(src.children))
    }

    @Test fun mixed_root_files_and_folders_put_folders_first() {
        val tree = buildDiffTree(listOf(file("z.txt"), file("src/a.kt"), file("README.md")))
        assertEquals(listOf("src", "README.md", "z.txt"), names(tree))
    }

    @Test fun folders_and_files_sort_case_insensitively_within_their_group() {
        val tree = buildDiffTree(
            listOf(file("Zeta/a.kt"), file("alpha/b.kt"), file("Banana.txt"), file("apple.txt")),
        )
        assertEquals(listOf("alpha", "Zeta", "apple.txt", "Banana.txt"), names(tree))
    }

    @Test fun empty_path_segments_are_ignored() {
        val tree = buildDiffTree(listOf(file("src//Foo.kt")))
        val src = assertIs<DiffTreeNode.Folder>(tree.single())
        val foo = assertIs<DiffTreeNode.File>(src.children.single())
        assertEquals("Foo.kt", foo.name)
        assertEquals("src//Foo.kt", foo.path)
    }

    @Test fun flatten_visible_includes_every_node_when_all_folders_are_expanded() {
        val tree = buildDiffTree(listOf(file("src/main/Foo.kt"), file("README.md")))
        val rows = flattenVisible(tree, expanded = allFolderPaths(tree))
        assertEquals(
            listOf("src" to 0, "main" to 1, "Foo.kt" to 2, "README.md" to 0),
            rows.map { it.node.name to it.depth },
        )
    }

    @Test fun flatten_visible_hides_children_of_a_collapsed_folder() {
        val tree = buildDiffTree(listOf(file("src/main/Foo.kt"), file("README.md")))
        val rows = flattenVisible(tree, expanded = emptySet())
        assertEquals(listOf("src" to 0, "README.md" to 0), rows.map { it.node.name to it.depth })
    }

    @Test fun all_folder_paths_walks_every_directory() {
        val tree = buildDiffTree(listOf(file("src/main/Foo.kt"), file("apps/a.kt")))
        assertEquals(setOf("apps", "src", "src/main"), allFolderPaths(tree))
    }

    @Test fun folder_diff_stats_sum_the_non_binary_children() {
        val tree = buildDiffTree(
            listOf(
                file("src/a.kt", diff = "@@ -1 +1,2 @@\n-old\n+new\n+also\n"),
                file("src/b.kt", diff = "@@ -1 +1 @@\n-x\n+y\n"),
                DiffFile(path = "src/pic.png", status = "added", diff = "", binary = true),
            ),
        )
        val src = assertIs<DiffTreeNode.Folder>(tree.single())
        // a.kt: +2 -1; b.kt: +1 -1; pic.png ignored as binary
        assertEquals(3 to 2, folderDiffStats(src))
    }
}
