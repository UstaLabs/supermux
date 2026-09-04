package dev.supermux.android.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatOverflowTestIdsTest {
    @Test fun overflowContinueTestIdMatchesContract() {
        assertEquals("overflow_continue", ChatOverflowTestIds.CONTINUE)
    }
}
