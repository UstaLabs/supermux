package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatDetailTest {
    @Test
    fun parseClampsHighAndGarbage() {
        assertEquals(ChatDetailLevel.LOW, ChatDetailLevel.parse("low"))
        assertEquals(ChatDetailLevel.MEDIUM, ChatDetailLevel.parse("medium"))
        assertEquals(ChatDetailLevel.MEDIUM, ChatDetailLevel.parse("high"))
        assertEquals(ChatDetailLevel.MEDIUM, ChatDetailLevel.parse("nope"))
        assertEquals(ChatDetailLevel.MEDIUM, ChatDetailLevel.parse(null))
    }

    @Test
    fun effectiveAndImplemented() {
        assertEquals(ChatDetailLevel.LOW, effectiveChatDetail(ChatDetailLevel.LOW))
        assertEquals(ChatDetailLevel.MEDIUM, effectiveChatDetail(ChatDetailLevel.MEDIUM))
        assertEquals(ChatDetailLevel.MEDIUM, effectiveChatDetail(ChatDetailLevel.HIGH))
        assertTrue(isChatDetailImplemented(ChatDetailLevel.LOW))
        assertFalse(isChatDetailImplemented(ChatDetailLevel.HIGH))
        assertNull(sanitizeSetLevel(ChatDetailLevel.HIGH))
        assertEquals(ChatDetailLevel.LOW, sanitizeSetLevel(ChatDetailLevel.LOW))
    }

    @Test
    fun turnBoundaryUsesLastUserNotWorkingSince() {
        val msgs = listOf(
            "inbound" to "1000",
            "outbound" to "2000",
            "inbound" to "3000",
        )
        val boundary = turnBoundaryMs(
            messages = msgs,
            isUserDirection = { it == "inbound" },
            tsToEpochMs = { it.toLong() },
            workingSinceMs = 99999L,
        )
        assertEquals(3000L, boundary)
    }

    @Test
    fun turnBoundaryFallbackWorkingSince() {
        val msgs = listOf("outbound" to "1000")
        assertEquals(
            12345L,
            turnBoundaryMs(msgs, { it == "inbound" }, { it.toLong() }, 12345L),
        )
        assertEquals(
            0L,
            turnBoundaryMs(emptyList(), { it == "inbound" }, { it.toLong() }, null),
        )
    }

    @Test
    fun countToolsAcrossWaitGap() {
        val since = 1000L
        val tools = listOf(500L, 1100L, 1200L, 5000L, 5200L)
        assertEquals(4, countToolsSince(tools, since))
    }

    @Test
    fun formatLowWorkingStatusMatrix() {
        assertEquals(
            "Working… · 12 seconds",
            formatLowWorkingStatus("Working…", "thinking", null, 0, "12 seconds"),
        )
        assertEquals(
            "Working… · 3 tools · 45 seconds",
            formatLowWorkingStatus("Working…", "thinking", null, 3, "45 seconds"),
        )
        assertEquals(
            "working · Bash · 1 tool · 4 seconds",
            formatLowWorkingStatus("working", "running", "Bash", 1, "4 seconds"),
        )
        assertEquals(
            "working · 2 tools · 9 seconds",
            formatLowWorkingStatus("working", "running", null, 2, "9 seconds"),
        )
    }
}
