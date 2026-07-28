package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatDetailTest {
    @Test
    fun parse_accepts_low_medium_high() {
        assertEquals(ChatDetailLevel.LOW, ChatDetailLevel.parse("low"))
        assertEquals(ChatDetailLevel.MEDIUM, ChatDetailLevel.parse("medium"))
        assertEquals(ChatDetailLevel.HIGH, ChatDetailLevel.parse("high"))
        assertEquals(ChatDetailLevel.MEDIUM, ChatDetailLevel.parse("nope"))
        assertEquals(ChatDetailLevel.MEDIUM, ChatDetailLevel.parse(null))
    }

    @Test
    fun effective_preserves_all_levels() {
        assertEquals(ChatDetailLevel.LOW, effectiveChatDetail(ChatDetailLevel.LOW))
        assertEquals(ChatDetailLevel.MEDIUM, effectiveChatDetail(ChatDetailLevel.MEDIUM))
        assertEquals(ChatDetailLevel.HIGH, effectiveChatDetail(ChatDetailLevel.HIGH))
        assertTrue(isChatDetailImplemented(ChatDetailLevel.LOW))
        assertTrue(isChatDetailImplemented(ChatDetailLevel.MEDIUM))
        assertTrue(isChatDetailImplemented(ChatDetailLevel.HIGH))
        assertEquals(ChatDetailLevel.HIGH, sanitizeSetLevel(ChatDetailLevel.HIGH))
        assertEquals(ChatDetailLevel.LOW, sanitizeSetLevel(ChatDetailLevel.LOW))
    }

    @Test
    fun formatLowWorkingStatus_matrix() {
        assertEquals(
            "Working… · 12 seconds",
            formatLowWorkingStatus("Working…", "thinking", null, 0, "12 seconds"),
        )
        assertEquals(
            "Working… · Bash · 1 tool · 4 seconds",
            formatLowWorkingStatus("Working…", "running", "Bash", 1, "4 seconds"),
        )
    }
}
