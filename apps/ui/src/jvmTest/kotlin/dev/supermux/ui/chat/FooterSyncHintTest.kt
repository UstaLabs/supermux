package dev.supermux.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #72 v5: the footer's sync chip says what a click does, against the branch's OWN upstream (VS Code's status bar). */
class FooterSyncHintTest {
    @Test fun vsCodesWordsForEachState() {
        assertEquals("Publish Branch", footerSyncHint(published = false, upstream = null, ahead = 0, behind = 0))
        assertEquals("Pull 2 commits from origin/feature", footerSyncHint(true, "origin/feature", ahead = 0, behind = 2))
        assertEquals("Push 1 commit to origin/feature", footerSyncHint(true, "origin/feature", ahead = 1, behind = 0))
        assertEquals("Pull 2 and push 1 commits between origin/feature", footerSyncHint(true, "origin/feature", ahead = 1, behind = 2))
        assertEquals("Synchronize Changes", footerSyncHint(true, "origin/feature", ahead = 0, behind = 0))
        assertNull(footerSyncHint(true, null, 0, 0), "an older broker sends no upstream name: no tooltip rather than a wrong one")
    }
}
