package dev.supermux.ui.editor

import dev.supermux.net.ChangedFile
import kotlin.test.Test
import kotlin.test.assertEquals

/** #72 v4: VS Code's groups and letters, from the /changes?base=head listing. */
class SourceControlGroupsTest {
    private fun f(path: String, staged: String?, status: String = "modified", baseBlob: String? = "abc") =
        ChangedFile(path = path, status = status, staged = staged, baseBlob = baseBlob)

    @Test fun a_partly_staged_file_is_in_both_staged_and_changes_and_a_conflict_only_in_merge() {
        val g = scmGroups(listOf(f("a", "all"), f("b", "partial"), f("c", "none"), f("d", "conflict")))
        assertEquals(listOf(ScmGroup.Merge, ScmGroup.Staged, ScmGroup.Changes), g.keys.toList())
        assertEquals(listOf("d"), g[ScmGroup.Merge]!!.map { it.path })
        assertEquals(listOf("a", "b"), g[ScmGroup.Staged]!!.map { it.path })
        assertEquals(listOf("b", "c"), g[ScmGroup.Changes]!!.map { it.path })
    }

    @Test fun an_empty_group_is_not_shown() {
        assertEquals(listOf(ScmGroup.Changes), scmGroups(listOf(f("c", "none"))).keys.toList())
    }

    @Test fun letters_follow_vs_code() {
        assertEquals("U", scmLetter(f("n", "none", status = "added", baseBlob = null), ScmGroup.Changes))
        assertEquals("A", scmLetter(f("n", "all", status = "added", baseBlob = null), ScmGroup.Staged))
        assertEquals("M", scmLetter(f("m", "none"), ScmGroup.Changes))
        assertEquals("D", scmLetter(f("x", "none", status = "deleted"), ScmGroup.Changes))
        assertEquals("R", scmLetter(f("r", "all", status = "renamed"), ScmGroup.Staged))
        assertEquals("C", scmLetter(f("c", "conflict"), ScmGroup.Merge))
    }
}
