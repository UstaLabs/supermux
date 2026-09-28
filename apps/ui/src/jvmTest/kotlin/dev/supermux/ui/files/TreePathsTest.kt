package dev.supermux.ui.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreePathsTest {
    @Test fun parentAndChild() {
        assertEquals("/a/b", parentOf("/a/b/c"))
        assertEquals("/", parentOf("/a"))
        assertNull(parentOf("/"))
        assertEquals("/a/b", childOf("/a", "b"))
        assertEquals("/b", childOf("/", "b"))
    }

    @Test fun within() {
        assertTrue(isWithin("/w", "/w"))
        assertTrue(isWithin("/w", "/w/src/a.kt"))
        assertFalse(isWithin("/w", "/work/a.kt"))
        assertTrue(isWithin("/", "/anything"))
    }

    @Test fun ancestorsWithinRootExcludeThePathItself() {
        assertEquals(listOf("/w", "/w/src", "/w/src/ui"), ancestorsWithin("/w", "/w/src/ui/A.kt"))
        assertEquals(emptyList(), ancestorsWithin("/w", "/other/A.kt"))
    }

    @Test fun relativeToWorkdir() {
        assertEquals("src/a.kt", relativeToWorkdir("/w", "/w/src/a.kt"))
        assertNull(relativeToWorkdir("/w", "/x/a.kt"))
        assertEquals(".", relativeToWorkdir("/w", "/w"))
        assertEquals("a.kt", relativeToWorkdir("/w/", "/w/a.kt"))
    }

    @Test fun displayName() {
        assertEquals("a.kt", displayName("/w/a.kt"))
        assertEquals("/", displayName("/"))
    }

    // ── affectedOpenPaths ────────────────────────────────────────────────────────────────────

    @Test fun deletingAFileAffectsOnlyThatFile() {
        assertEquals(
            listOf(MovedOpenPath("src/a.kt", null)),
            affectedOpenPaths("/w", "/w/src/a.kt", null, listOf("src/a.kt", "src/a.kts", "b.kt")),
        )
    }

    @Test fun deletingAFolderAffectsEverythingUnderItButNotItsNamePrefixSiblings() {
        assertEquals(
            listOf(MovedOpenPath("src/a.kt", null), MovedOpenPath("src/ui/b.kt", null)),
            affectedOpenPaths("/w", "/w/src", null, listOf("src/a.kt", "srcx/c.kt", "src/ui/b.kt", "top.kt")),
        )
    }

    @Test fun renamingAFileMapsItToTheNewName() {
        assertEquals(
            listOf(MovedOpenPath("src/a.kt", "src/b.kt")),
            affectedOpenPaths("/w", "/w/src/a.kt", "/w/src/b.kt", listOf("src/a.kt", "other.kt")),
        )
    }

    @Test fun renamingAFolderMapsEveryOpenFileUnderIt() {
        assertEquals(
            listOf(MovedOpenPath("src/a.kt", "lib/a.kt"), MovedOpenPath("src/ui/b.kt", "lib/ui/b.kt")),
            affectedOpenPaths("/w", "/w/src/", "/w/lib", listOf("src/a.kt", "src/ui/b.kt", "src2/x.kt")),
        )
    }

    @Test fun aLeadingSlashOrDuplicateIsToleratedAndKeptAsGiven() {
        assertEquals(
            listOf(MovedOpenPath("/src/a.kt", "src/z.kt")),
            affectedOpenPaths("/w", "/w/src/a.kt", "/w/src/z.kt", listOf("/src/a.kt", "/src/a.kt")),
        )
    }

    @Test fun aMoveOutsideTheWorkdirCountsAsGone() {
        assertEquals(
            listOf(MovedOpenPath("a.kt", null)),
            affectedOpenPaths("/w", "/w/a.kt", "/elsewhere/a.kt", listOf("a.kt")),
        )
    }

    @Test fun entriesOutsideTheWorkdirOrANoOpRenameAffectNothing() {
        assertEquals(emptyList(), affectedOpenPaths("/w", "/other/a.kt", null, listOf("a.kt")))
        assertEquals(emptyList(), affectedOpenPaths("/w", "/w/a.kt", "/w/a.kt", listOf("a.kt")))
        assertEquals(emptyList(), affectedOpenPaths("/w", "/", null, listOf("a.kt")))
    }

    @Test fun deletingTheWorkdirItselfAffectsEverything() {
        assertEquals(
            listOf(MovedOpenPath("a.kt", null)),
            affectedOpenPaths("/w", "/w", null, listOf("a.kt")),
        )
    }
}
