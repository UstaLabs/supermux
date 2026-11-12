package dev.supermux.ui.chat

import dev.supermux.proto.GitLiteStatusDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #72: the footer says one thing about the work tree, or nothing. */
class ComposerGitSignTest {
    private fun base(ahead: Int = 0, behind: Int = 0, dirty: Int = 0, ref: String = "dev") =
        GitLiteStatusDto(mode = "base", compareRef = ref, ahead = ahead, behind = behind, dirty = dirty)
    private fun remote(ahead: Int = 0, behind: Int = 0, dirty: Int = 0, unpublished: Boolean? = false) =
        GitLiteStatusDto(mode = "remote", compareRef = "origin/x", ahead = ahead, behind = behind, dirty = dirty, unpublished = unpublished)

    @Test fun nothingWhenThereIsNoGitOrNothingToSay() {
        assertNull(composerGitSign(null))
        assertNull(composerGitSign(base()))
        assertNull(composerGitSign(remote()))
    }

    @Test fun uncommittedChangesComeFirst() {
        assertEquals(ComposerGitSign.Changed(3), composerGitSign(base(ahead = 2, behind = 5, dirty = 3)))
        assertEquals(ComposerGitSign.Changed(1), composerGitSign(remote(ahead = 1, dirty = 1)))
    }

    @Test fun aWorktreeCountsAgainstItsBaseByName() {
        assertEquals(ComposerGitSign.NotInBase(2, "release/2.4"), composerGitSign(base(ahead = 2, behind = 4, ref = "release/2.4")))
        assertEquals(ComposerGitSign.BaseMoved(4, "dev"), composerGitSign(base(behind = 4)))
        assertNull(composerGitSign(base(ahead = 2, ref = "")))
    }

    @Test fun aPlainCheckoutCountsAgainstItsUpstream() {
        assertEquals(ComposerGitSign.ToPush(2), composerGitSign(remote(ahead = 2, behind = 1)))
        assertEquals(ComposerGitSign.ToPull(1), composerGitSign(remote(behind = 1)))
        assertNull(composerGitSign(remote(ahead = 2, unpublished = true)))
    }
}
