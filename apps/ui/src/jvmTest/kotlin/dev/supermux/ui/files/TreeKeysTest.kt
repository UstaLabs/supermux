package dev.supermux.ui.files

import dev.supermux.net.FsEntry
import dev.supermux.ui.files.TreeKeyResult.Activate
import dev.supermux.ui.files.TreeKeyResult.Collapse
import dev.supermux.ui.files.TreeKeyResult.Delete
import dev.supermux.ui.files.TreeKeyResult.Expand
import dev.supermux.ui.files.TreeKeyResult.Rename
import dev.supermux.ui.files.TreeKeyResult.Select
import dev.supermux.ui.files.TreeKeyResult.Stay
import dev.supermux.ui.files.TreeKeyResult.Unhandled
import kotlin.test.Test
import kotlin.test.assertEquals

class TreeKeysTest {
    private fun row(path: String, depth: Int, status: RowStatus) =
        TreeRow(path, depth, FsEntry(name = path.substringAfterLast('/'), type = if (status == RowStatus.FILE) "file" else "dir"), status)

    // /w
    //   src/        (open)
    //     ui/       (closed)
    //     App.kt
    //   docs/       (loading, expanded, no children yet)
    //   loop/       (symlink loop, expanded)
    //   README.md
    //   build.gradle
    private val rows = listOf(
        row("/w/src", 0, RowStatus.OPEN),
        row("/w/src/ui", 1, RowStatus.CLOSED),
        row("/w/src/App.kt", 1, RowStatus.FILE),
        row("/w/docs", 0, RowStatus.LOADING),
        row("/w/loop", 0, RowStatus.LOOP),
        row("/w/README.md", 0, RowStatus.FILE),
        row("/w/build.gradle", 0, RowStatus.FILE),
    )
    private val expanded = setOf("/w/src", "/w/docs", "/w/loop")

    private fun act(key: TreeKey, selected: String?) = treeKeyAction(key, rows, selected, expanded)

    @Test fun emptyRowsHandleNothing() {
        for (k in listOf(TreeKey.Up, TreeKey.Down, TreeKey.Left, TreeKey.Right, TreeKey.Enter, TreeKey.Home, TreeKey.End, TreeKey.Rename, TreeKey.Delete, TreeKey.Type("a"))) {
            assertEquals(Unhandled, treeKeyAction(k, emptyList(), null, emptySet()), "$k")
        }
    }

    @Test fun upAndDownMoveAndStopAtTheEnds() {
        assertEquals(Select("/w/src/App.kt"), act(TreeKey.Down, "/w/src/ui"))
        assertEquals(Select("/w/src"), act(TreeKey.Up, "/w/src/ui"))
        assertEquals(Stay, act(TreeKey.Up, "/w/src"))
        assertEquals(Stay, act(TreeKey.Down, "/w/build.gradle"))
    }

    @Test fun noSelectionOrOneNotInRows() {
        for (sel in listOf(null, "/elsewhere")) {
            for (k in listOf(TreeKey.Up, TreeKey.Down, TreeKey.Left, TreeKey.Right)) assertEquals(Select("/w/src"), act(k, sel), "$k $sel")
            for (k in listOf(TreeKey.Enter, TreeKey.Rename, TreeKey.Delete)) assertEquals(Unhandled, act(k, sel), "$k $sel")
            assertEquals(Select("/w/src"), act(TreeKey.Home, sel))
            assertEquals(Select("/w/build.gradle"), act(TreeKey.End, sel))
        }
    }

    @Test fun homeAndEnd() {
        assertEquals(Select("/w/src"), act(TreeKey.Home, "/w/README.md"))
        assertEquals(Stay, act(TreeKey.Home, "/w/src"))
        assertEquals(Select("/w/build.gradle"), act(TreeKey.End, "/w/src"))
        assertEquals(Stay, act(TreeKey.End, "/w/build.gradle"))
    }

    @Test fun rightExpandsAClosedFolderThenEntersIt() {
        assertEquals(Expand("/w/src/ui"), act(TreeKey.Right, "/w/src/ui"))
        assertEquals(Select("/w/src/ui"), act(TreeKey.Right, "/w/src"))
        // Open but no children listed yet (loading), a symlink loop, or a file: stay.
        assertEquals(Stay, act(TreeKey.Right, "/w/docs"))
        assertEquals(Stay, act(TreeKey.Right, "/w/loop"))
        assertEquals(Stay, act(TreeKey.Right, "/w/README.md"))
    }

    @Test fun rightOnAnOpenEmptyFolderStaysInsteadOfJumpingToASibling() {
        val r = listOf(row("/w/a", 0, RowStatus.OPEN), row("/w/b", 0, RowStatus.FILE))
        assertEquals(Stay, treeKeyAction(TreeKey.Right, r, "/w/a", setOf("/w/a")))
    }

    @Test fun leftCollapsesAnOpenFolderElseGoesToTheParent() {
        assertEquals(Collapse("/w/src"), act(TreeKey.Left, "/w/src"))
        assertEquals(Collapse("/w/docs"), act(TreeKey.Left, "/w/docs"))
        assertEquals(Collapse("/w/loop"), act(TreeKey.Left, "/w/loop"))
        assertEquals(Select("/w/src"), act(TreeKey.Left, "/w/src/App.kt"))
        assertEquals(Select("/w/src"), act(TreeKey.Left, "/w/src/ui"))
        // Top level: no parent row.
        assertEquals(Stay, act(TreeKey.Left, "/w/README.md"))
    }

    @Test fun enterActivatesAndF2DeleteTargetTheSelection() {
        assertEquals(Activate("/w/README.md"), act(TreeKey.Enter, "/w/README.md"))
        assertEquals(Activate("/w/src"), act(TreeKey.Enter, "/w/src"))
        assertEquals(Rename("/w/src/App.kt"), act(TreeKey.Rename, "/w/src/App.kt"))
        assertEquals(Delete("/w/docs"), act(TreeKey.Delete, "/w/docs"))
    }

    @Test fun typeAheadIsCaseInsensitiveAndCycles() {
        // One letter looks AFTER the selection, wrapping.
        assertEquals(Select("/w/README.md"), act(TreeKey.Type("r"), "/w/src"))
        assertEquals(Select("/w/src"), act(TreeKey.Type("s"), "/w/build.gradle"))
        assertEquals(Select("/w/build.gradle"), act(TreeKey.Type("B"), null))
        // "d": docs, then (from docs) nothing else starts with d → wraps back to docs itself.
        assertEquals(Select("/w/docs"), act(TreeKey.Type("d"), "/w/src"))
        assertEquals(Stay, act(TreeKey.Type("d"), "/w/docs"))
        // A longer prefix keeps the current row while it still matches…
        assertEquals(Stay, act(TreeKey.Type("sr"), "/w/src"))
        // …and moves on when it doesn't.
        assertEquals(Select("/w/src/App.kt"), act(TreeKey.Type("ap"), "/w/src"))
        // No match: stay. Empty prefix: not ours.
        assertEquals(Stay, act(TreeKey.Type("zz"), "/w/src"))
        assertEquals(Unhandled, act(TreeKey.Type(""), "/w/src"))
    }
}
