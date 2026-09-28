package dev.supermux.ui.files

import dev.supermux.fs.DirSnapshot
import dev.supermux.fs.DirState
import dev.supermux.net.FsEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class FlattenTreeTest {
    private fun ready(path: String, vararg e: FsEntry) = DirState.Ready(DirSnapshot(path = path, version = "v", entries = e.toList()))
    private fun dir(n: String) = FsEntry(name = n, type = "dir")
    private fun file(n: String) = FsEntry(name = n, type = "file")

    @Test fun depthFirstWithDepthsAndStatuses() {
        val states = mapOf(
            "/w" to ready("/w", dir("src"), dir("docs"), file("README.md")),
            "/w/src" to ready("/w/src", dir("ui"), file("a.kt")),
            "/w/src/ui" to DirState.Loading(null),
            "/w/docs" to DirState.Failed("EACCES", "denied", null),
        )
        val v = TreeViewState("/w").apply { expand("/w/src"); expand("/w/src/ui"); expand("/w/docs") }
        val rows = flattenTree(v.rootPath, v.expanded) { states[it] ?: DirState.Unloaded }
        assertEquals(
            listOf("src:0:OPEN", "ui:1:LOADING", "a.kt:1:FILE", "docs:0:ERROR", "README.md:0:FILE"),
            rows.map { "${it.entry.name}:${it.depth}:${it.status}" },
        )
        assertEquals("denied", rows.first { it.entry.name == "docs" }.error)
    }

    @Test fun collapsedFoldersHideChildren_symlinkLoopsStop() {
        val states = mapOf(
            "/w" to ready("/w", FsEntry(name = "loop", type = "symlink", target = "dir")),
            "/w/loop" to DirState.Ready(DirSnapshot(path = "/w/loop", real = "/w", version = "v", entries = listOf(FsEntry(name = "loop", type = "symlink", target = "dir")))),
        )
        val rows = flattenTree("/w", setOf("/w/loop", "/w/loop/loop")) { states[it] ?: DirState.Unloaded }
        // /w/loop resolves to /w (an ancestor) → shown but not descended into.
        assertEquals(listOf("loop:0:LOOP"), rows.map { "${it.entry.name}:${it.depth}:${it.status}" })
    }

    @Test fun previousSnapshotShownWhileRefreshing() {
        val prev = DirSnapshot(path = "/w", version = "v1", entries = listOf(file("x")))
        val rows = flattenTree("/w", emptySet()) { DirState.Loading(prev) }
        assertEquals(listOf("x"), rows.map { it.entry.name })
    }

    @Test fun failedRefreshKeepsPreviousChildrenUnderTheErrorRow() {
        val prev = DirSnapshot(path = "/w/src", version = "v1", entries = listOf(file("a.kt")))
        val states = mapOf(
            "/w" to ready("/w", dir("src")),
            "/w/src" to DirState.Failed("EACCES", "denied", prev),
        )
        val rows = flattenTree("/w", setOf("/w/src")) { states[it] ?: DirState.Unloaded }
        assertEquals(listOf("src:0:ERROR", "a.kt:1:FILE"), rows.map { "${it.entry.name}:${it.depth}:${it.status}" })
    }
}
