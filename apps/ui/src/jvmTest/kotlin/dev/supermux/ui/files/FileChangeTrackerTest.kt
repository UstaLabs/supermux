package dev.supermux.ui.files

import dev.supermux.fs.DirSnapshot
import dev.supermux.net.FsEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileChangeTrackerTest {
    private var now = 0L
    private fun tracker() = FileChangeTracker(nowMs = { now }, writeGraceMs = 1_000)

    private fun snap(vararg files: Pair<String, Pair<Long, Long>>, dir: String = "/w/src") =
        DirSnapshot(path = dir, version = "v", entries = files.map { (n, ms) -> FsEntry(name = n, type = "file", mtime = ms.first, size = ms.second) })

    @Test fun firstSightingRecordsWithoutReporting() {
        val t = tracker()
        t.track("/w/src/a.kt")
        assertEquals(emptyList(), t.onSnapshot("/w/src", snap("a.kt" to (1L to 10L))))
    }

    @Test fun sameValuesAreANoOp() {
        val t = tracker()
        t.track("/w/src/a.kt")
        t.onSnapshot("/w/src", snap("a.kt" to (1L to 10L)))
        assertEquals(emptyList(), t.onSnapshot("/w/src", snap("a.kt" to (1L to 10L), "other.kt" to (5L to 5L))))
    }

    @Test fun aChangedMtimeReportsOnce() {
        val t = tracker()
        t.track("/w/src/a.kt")
        t.onSnapshot("/w/src", snap("a.kt" to (1L to 10L)))
        assertEquals(listOf("/w/src/a.kt"), t.onSnapshot("/w/src", snap("a.kt" to (2L to 10L))))
        assertEquals(emptyList(), t.onSnapshot("/w/src", snap("a.kt" to (2L to 10L))))
        // A second, later change is a new report.
        assertEquals(listOf("/w/src/a.kt"), t.onSnapshot("/w/src", snap("a.kt" to (3L to 12L))))
    }

    @Test fun aChangedSizeAloneReports() {
        val t = tracker()
        t.track("/w/src/a.kt")
        t.onSnapshot("/w/src", snap("a.kt" to (1L to 10L)))
        assertEquals(listOf("/w/src/a.kt"), t.onSnapshot("/w/src", snap("a.kt" to (1L to 11L))))
    }

    @Test fun aDeletedFileReportsOnce() {
        val t = tracker()
        t.track("/w/src/a.kt")
        t.onSnapshot("/w/src", snap("a.kt" to (1L to 10L)))
        assertEquals(listOf("/w/src/a.kt"), t.onSnapshot("/w/src", snap()))
        assertEquals(emptyList(), t.onSnapshot("/w/src", snap()))
        // Recreated: a change again.
        assertEquals(listOf("/w/src/a.kt"), t.onSnapshot("/w/src", snap("a.kt" to (4L to 1L))))
    }

    @Test fun aSnapshotThatDoesNotListTheFileYetIsNotItsFirstSighting() {
        val t = tracker()
        t.track("/w/src/new.kt")
        assertEquals(emptyList(), t.onSnapshot("/w/src", snap()))
        assertEquals(emptyList(), t.onSnapshot("/w/src", snap("new.kt" to (1L to 0L))))
        assertEquals(listOf("/w/src/new.kt"), t.onSnapshot("/w/src", snap("new.kt" to (2L to 3L))))
    }

    @Test fun onlyFilesOfThatFolderAreCompared() {
        val t = tracker()
        t.track("/w/src/a.kt")
        t.track("/w/a.kt")
        t.onSnapshot("/w/src", snap("a.kt" to (1L to 10L)))
        t.onSnapshot("/w", snap("a.kt" to (1L to 10L), dir = "/w"))
        assertEquals(listOf("/w/a.kt"), t.onSnapshot("/w/", snap("a.kt" to (9L to 10L), dir = "/w")))
        assertEquals(setOf("/w", "/w/src"), t.folders())
    }

    @Test fun untrackedFilesAreNeverReported() {
        val t = tracker()
        t.track("/w/src/a.kt")
        t.onSnapshot("/w/src", snap("a.kt" to (1L to 10L)))
        t.untrack("/w/src/a.kt")
        assertEquals(emptyList(), t.onSnapshot("/w/src", snap("a.kt" to (2L to 10L))))
        assertTrue(t.folders().isEmpty())
    }

    @Test fun ourOwnSaveIsNotAChangeOnDisk() {
        val t = tracker()
        t.track("/w/src/a.kt")
        t.onSnapshot("/w/src", snap("a.kt" to (1L to 10L)))
        // The folder event can land while the save is in flight…
        t.beginWrite("/w/src/a.kt")
        assertEquals(emptyList(), t.onSnapshot("/w/src", snap("a.kt" to (2L to 12L))))
        t.endWrite("/w/src/a.kt")
        // …or just after it answered (within the grace window).
        now += 500
        assertEquals(emptyList(), t.onSnapshot("/w/src", snap("a.kt" to (3L to 12L))))
        // Later, an outside edit reports again against the post-save baseline.
        now += 1_000
        assertEquals(emptyList(), t.onSnapshot("/w/src", snap("a.kt" to (3L to 12L))))
        assertEquals(listOf("/w/src/a.kt"), t.onSnapshot("/w/src", snap("a.kt" to (4L to 12L))))
    }

    @Test fun aSaveOfOneFileDoesNotHideAChangeToAnother() {
        val t = tracker()
        t.track("/w/src/a.kt")
        t.track("/w/src/b.kt")
        t.onSnapshot("/w/src", snap("a.kt" to (1L to 10L), "b.kt" to (1L to 10L)))
        t.beginWrite("/w/src/a.kt")
        assertEquals(listOf("/w/src/b.kt"), t.onSnapshot("/w/src", snap("a.kt" to (2L to 10L), "b.kt" to (2L to 10L))))
        t.endWrite("/w/src/a.kt")
    }

    @Test fun absoluteInWorkdirJoinsRelativePaths() {
        assertEquals("/w/src/a.kt", absoluteInWorkdir("/w", "src/a.kt"))
        assertEquals("/w/src/a.kt", absoluteInWorkdir("/w/", "/src/a.kt"))
        assertEquals("/a.kt", absoluteInWorkdir("/", "a.kt"))
    }
}
