package dev.supermux.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentsTest {
    @Test fun a_plain_name_passes() {
        assertNull(agentNameError("Dockie"))
        assertNull(agentNameError("  travel assistant  "))
    }

    @Test fun blank_slash_leading_dot_and_long_names_are_refused() {
        assertEquals(AgentNameError.EMPTY, agentNameError("   "))
        assertEquals(AgentNameError.SLASH, agentNameError("a/b"))
        assertEquals(AgentNameError.SLASH, agentNameError("a\\b"))
        assertEquals(AgentNameError.LEADING_DOT, agentNameError(".hidden"))
        assertEquals(AgentNameError.LEADING_DOT, agentNameError("../x"))
        assertEquals(AgentNameError.TOO_LONG, agentNameError("x".repeat(81)))
        assertNull(agentNameError("a..b"))
    }

    @Test fun control_characters_are_refused() {
        assertEquals(AgentNameError.CONTROL_CHARACTER, agentNameError("a\u0001b"))
        assertEquals(AgentNameError.CONTROL_CHARACTER, agentNameError("a\u007Fb"))
    }

    @Test fun trailing_control_characters_kotlin_trim_would_strip_are_refused() {
        // Kotlin's trim() strips U+001C–U+001F; the broker's JS trim() does not and refuses them.
        assertEquals(AgentNameError.CONTROL_CHARACTER, agentNameError("a\u001F"))
        assertEquals(AgentNameError.CONTROL_CHARACTER, agentNameError("\u001Ca"))
    }

    @Test fun whitespace_js_trim_strips_is_trimmed_not_refused() {
        // JS trim() strips tab/newline/etc. before the broker checks control characters.
        assertNull(agentNameError("Ada\t"))
        assertNull(agentNameError("\nAda\r"))
        assertNull(agentNameError("\uFEFFAda\u00A0"))
        assertEquals(AgentNameError.CONTROL_CHARACTER, agentNameError("A\tda"))
    }

    @Test fun agentNameTrim_matches_js_trim() {
        assertEquals("Ada", agentNameTrim("\uFEFFAda"))
        assertEquals("Ada", agentNameTrim("\t\u000B\u000C\u2028\u3000Ada\u2029\u00A0 "))
        // U+001C–U+001F: Kotlin's trim() strips them, JS trim() keeps them.
        assertEquals("a\u001F", agentNameTrim("a\u001F"))
    }
}
