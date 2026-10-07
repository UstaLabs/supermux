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

    @Test fun sortsReversedBrokerOrderLowToHigh() {
        val reversed = listOf("max", "xhigh", "high", "medium", "low").map { ReasoningLevel(it) }
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            sortEffortLevelsLowToHigh(reversed).map { it.id },
        )
    }

    @Test fun speedometerParams_mapLowToHigh_evenWhenReversed() {
        val reversed = listOf("max", "xhigh", "high", "medium", "low").map { ReasoningLevel(it) }
        assertEquals(5 to 1, effortSpeedometerParams("low", reversed))
        assertEquals(5 to 3, effortSpeedometerParams("high", reversed))
        assertEquals(5 to 5, effortSpeedometerParams("max", reversed))
        assertEquals(5 to 3, effortSpeedometerParams(null, reversed)) // mid when unknown
        assertEquals(1 to 1, effortSpeedometerParams("x", emptyList()))
    }

    @Test fun resolveStillDefaultsToHighWhenListIsReversed() {
        val reversed = listOf("max", "xhigh", "high", "medium", "low").map { ReasoningLevel(it) }
        assertEquals("high", resolveReasoningLevel(reversed, null))
        assertEquals("max", resolveReasoningLevel(reversed, "max"))
    }
}
