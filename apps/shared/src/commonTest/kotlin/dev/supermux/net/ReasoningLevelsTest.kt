package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReasoningLevelsTest {
    // Low→high, as the broker returns them (Claude static, a typical Codex model).
    private val claude = listOf("low", "medium", "high", "xhigh", "max").map { ReasoningLevel(it) }
    private val codex = listOf("minimal", "low", "medium", "high").map { ReasoningLevel(it) }

    @Test fun defaultsANewSessionToHigh() {
        assertEquals("high", resolveReasoningLevel(claude, null))
        assertEquals("high", resolveReasoningLevel(claude, ""))
        assertEquals("high", resolveReasoningLevel(codex, null))
    }

    @Test fun keepsAValidStoredChoice() {
        assertEquals("max", resolveReasoningLevel(claude, "max"))
        assertEquals("minimal", resolveReasoningLevel(codex, "minimal"))
    }

    @Test fun fallsBackToDefaultWhenStoredNotOffered() {
        assertEquals("high", resolveReasoningLevel(codex, "max"))
        assertEquals("high", resolveReasoningLevel(claude, "bogus"))
    }

    @Test fun returnsNullWhenNoLevels() {
        assertNull(resolveReasoningLevel(emptyList(), null))
        assertNull(resolveReasoningLevel(emptyList(), "high"))
    }

    @Test fun fallsBackToHighestWhenHighAbsent() {
        val levels = listOf(ReasoningLevel("low"), ReasoningLevel("medium"))
        assertEquals("medium", resolveReasoningLevel(levels, null))
        assertEquals("low", resolveReasoningLevel(levels, "low"))
    }

    @Test fun showsOnlyWithARealChoice() {
        assertTrue(showReasoningPicker(claude))
        assertTrue(showReasoningPicker(codex))
        assertFalse(showReasoningPicker(emptyList()))
        assertFalse(showReasoningPicker(listOf(ReasoningLevel("only"))))
    }
}
