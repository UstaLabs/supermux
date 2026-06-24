package dev.supermux.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitBadgeTest {
    @Test fun null_status_no_badge() {
        assertNull(gitBadge(null))
    }

    @Test fun in_sync_is_muted_check() {
        val b = gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main"))
        assertEquals(GitBadge("✓", GitBadgeKind.INSYNC, GitBadgeTone.MUTED, "main"), b)
    }

    @Test fun base_mode_uses_plus_minus_and_branch_kind() {
        val b = gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 2, behind = 1, dirty = 3))
        assertEquals("+2 −1 ·3", b?.text)
        assertEquals(GitBadgeKind.BASE, b?.kind)
        assertEquals(GitBadgeTone.ACTIVE, b?.tone)
        assertEquals("main", b?.compareRef)
    }

    @Test fun remote_mode_uses_arrows_and_remote_kind() {
        val b = gitBadge(GitLiteStatusDto(mode = "remote", compareRef = "origin/x", ahead = 2, behind = 1))
        assertEquals("↑2 ↓1", b?.text)
        assertEquals(GitBadgeKind.REMOTE, b?.kind)
    }

    @Test fun only_nonzero_parts_shown() {
        assertEquals("+2", gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 2))?.text)
        assertEquals("↓3", gitBadge(GitLiteStatusDto(mode = "remote", compareRef = "o", behind = 3))?.text)
    }

    @Test fun base_dirty_only_active() {
        val b = gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main", dirty = 5))
        assertEquals("·5", b?.text)
        assertEquals(GitBadgeKind.BASE, b?.kind)
        assertEquals(GitBadgeTone.ACTIVE, b?.tone)
    }

    @Test fun unpublished_remote_muted() {
        val b = gitBadge(GitLiteStatusDto(mode = "remote", compareRef = "x", unpublished = true))
        assertEquals(GitBadge("unpublished", GitBadgeKind.UNPUBLISHED, GitBadgeTone.MUTED, "x"), b)
    }

    @Test fun unpublished_ignored_in_base_mode() {
        val b = gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 1, unpublished = true))
        assertEquals("+1", b?.text)
        assertEquals(GitBadgeKind.BASE, b?.kind)
    }
}
