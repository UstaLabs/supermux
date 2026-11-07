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

    @Test fun aDuplicateIsToleratedAndKeptAsGiven() {
        assertEquals(
            listOf(MovedOpenPath("src/a.kt", "src/z.kt")),
            affectedOpenPaths("/w", "/w/src/a.kt", "/w/src/z.kt", listOf("src/a.kt", "src/a.kt")),
        )
    }

    @Test fun aMoveOutsideTheWorkdirFollowsTheFileByItsAbsolutePath() {
        assertEquals(
            listOf(MovedOpenPath("a.kt", "/elsewhere/a.kt")),
            affectedOpenPaths("/w", "/w/a.kt", "/elsewhere/a.kt", listOf("a.kt")),
        )
    }

    @Test fun filesOpenOutsideTheWorkdirFollowMovesToo() {
        assertEquals(
            listOf(MovedOpenPath("/other/a.kt", "/other/b.kt"), MovedOpenPath("/other/c.kt", "c.kt")),
            affectedOpenPaths("/w", "/other/a.kt", "/other/b.kt", listOf("/other/a.kt", "a.kt")) +
                affectedOpenPaths("/w", "/other/c.kt", "/w/c.kt", listOf("/other/c.kt")),
        )
        assertEquals(listOf(MovedOpenPath("/other/a.kt", null)), affectedOpenPaths("/w", "/other", null, listOf("/other/a.kt")))
    }

    @Test fun unrelatedEntriesOrANoOpRenameAffectNothing() {
        assertEquals(emptyList(), affectedOpenPaths("/w", "/other/a.kt", null, listOf("a.kt", "/other/b.kt")))
        assertEquals(emptyList(), affectedOpenPaths("/w", "/w/a.kt", "/w/a.kt", listOf("a.kt")))
        assertEquals(emptyList(), affectedOpenPaths("/w", "/", null, listOf("a.kt")))
    }

    @Test fun deletingTheWorkdirItselfAffectsEverything() {
        assertEquals(
            listOf(MovedOpenPath("a.kt", null)),
            affectedOpenPaths("/w", "/w", null, listOf("a.kt")),
        )
    }

    // A Windows host: the broker answers with `\` and names a folder whatever its letter case.
    @Test fun parentAndChildOnAWindowsHost() {
        assertEquals("C:\\Users\\me", parentOf("C:\\Users\\me\\proj"))
        assertEquals("C:\\", parentOf("C:\\Users"))
        assertNull(parentOf("C:\\"))
        assertEquals("C:\\Users\\me\\proj\\src", childOf("C:\\Users\\me\\proj", "src"))
        assertEquals("C:\\Users", childOf("C:\\", "Users"))
        assertEquals("\\\\server\\share\\a", childOf("\\\\server\\share", "a"))
    }

    @Test fun aPosixNameWithABackslashIsOneName() {
        assertEquals("/w", parentOf("/w/a\\b"))
        assertEquals("/w/a\\b", childOf("/w", "a\\b"))
        assertEquals("a\\b", displayName("/w/a\\b"))
    }

    @Test fun withinOnAWindowsHostIgnoresLetterCase() {
        assertTrue(isWithin("C:\\Users\\me\\proj", "c:\\users\\me\\proj\\src\\a.kt"))
        assertTrue(isWithin("C:\\", "C:\\anything"))
        assertFalse(isWithin("C:\\Users\\me\\proj", "C:\\Users\\me\\project\\a.kt"))
        assertFalse(isWithin("C:\\w", "D:\\w\\a.kt"))
    }

    @Test fun ancestorsOnAWindowsHost() {
        assertEquals(
            listOf("C:\\w", "C:\\w\\src"),
            ancestorsWithin("C:\\w", "C:\\w\\src\\A.kt"),
        )
    }

    @Test fun displayNameOnAWindowsHost() {
        assertEquals("proj", displayName("C:\\Users\\me\\proj"))
        assertEquals("C:\\", displayName("C:\\"))
    }

    @Test fun editorPathsOnAWindowsHost() {
        assertEquals("C:\\w\\src\\a.kt", editorAbsolutePath("C:\\w", "src/a.kt"))
        assertEquals("D:\\x\\a.kt", editorAbsolutePath("C:\\w", "D:\\x\\a.kt"))
        assertEquals("src/a.kt", editorPathFor("C:\\w", "c:\\W\\src\\a.kt"))
    }

    @Test fun movesOnAWindowsHost() {
        assertEquals(
            listOf(MovedOpenPath("src/a.kt", "lib/a.kt"), MovedOpenPath("D:\\x\\b.kt", null)),
            affectedOpenPaths("C:\\w", "C:\\w\\src", "C:\\w\\lib", listOf("src/a.kt", "srcx/c.kt")) +
                affectedOpenPaths("C:\\w", "d:\\X", null, listOf("D:\\x\\b.kt")),
        )
        assertEquals(emptyList(), affectedOpenPaths("C:\\w", "C:\\", null, listOf("a.kt")))
    }
}
