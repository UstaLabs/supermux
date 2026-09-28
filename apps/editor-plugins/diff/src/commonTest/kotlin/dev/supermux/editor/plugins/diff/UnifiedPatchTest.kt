package dev.supermux.editor.plugins.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `UnifiedPatch.base` against real `git diff` output ([GIT_FIXTURES], generated on the Mac). */
class UnifiedPatchTest {
    @Test fun everyRealGitPatchGivesBackItsBase() {
        for ((name, base, working, patch) in GIT_FIXTURES.map { Quad(it[0], it[1], it[2], it[3]) }) {
            assertEquals(base, UnifiedPatch.base(working, patch), name)
        }
    }

    @Test fun theFixturesCoverWhatTheReviewAskedFor() {
        val patches = GIT_FIXTURES.associate { it[0] to it[3] }
        assertEquals(true, patches.getValue("sqlCommentRemoved").contains("\n--- comment\n"), "a removed '-- comment' line is in the patch as '--- comment'")
        assertEquals(true, patches.getValue("plusPlusAdded").contains("\n+++ y\n"))
        assertEquals(true, patches.getValue("rename").contains("rename from"))
        assertEquals(true, patches.getValue("noNewlineOld").contains("\\ No newline at end of file"))
    }

    @Test fun aContextMismatchIsRefused() {
        val f = GIT_FIXTURES.first { it[0] == "multipleHunks" }
        assertFailsWith<IllegalArgumentException> { UnifiedPatch.base(f[2].replace("line 19\n", "line nineteen\n"), f[3]) }
        // An added line that is not in the working copy.
        val g = GIT_FIXTURES.first { it[0] == "plusPlusAdded" }
        assertFailsWith<IllegalArgumentException> { UnifiedPatch.base(g[2].replace("++ y", "++ q"), g[3]) }
    }

    @Test fun aHunkShorterThanItsHeaderIsRefused() {
        assertFailsWith<IllegalArgumentException> { UnifiedPatch.base("a\n", "@@ -1,3 +1,3 @@\n a\n") }
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)
}
