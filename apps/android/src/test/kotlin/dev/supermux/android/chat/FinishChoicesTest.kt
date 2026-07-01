package dev.supermux.android.chat

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FinishChoicesTest {
    @Test fun merge_can_always_skip() {
        assertTrue(canSkipTests("merge", false))
        assertTrue(canSkipTests("merge", true))
    }

    @Test fun pr_skips_only_when_not_requiring_green() {
        assertTrue(canSkipTests("pr", false))
        assertFalse(canSkipTests("pr", true))
    }
}
