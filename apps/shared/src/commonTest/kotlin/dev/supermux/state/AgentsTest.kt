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
        assertEquals("Enter a name", agentNameError("   "))
        assertEquals("A name can't contain / or \\", agentNameError("a/b"))
        assertEquals("A name can't contain / or \\", agentNameError("a\\b"))
        assertEquals("A name can't start with a dot", agentNameError(".hidden"))
        assertEquals("A name can't start with a dot", agentNameError("../x"))
        assertEquals("A name can be at most 80 characters", agentNameError("x".repeat(81)))
        assertNull(agentNameError("a..b"))
    }

    @Test fun control_characters_are_refused() {
        assertEquals("A name can't contain control characters", agentNameError("a\u0001b"))
        assertEquals("A name can't contain control characters", agentNameError("a\u007Fb"))
    }

    @Test fun trailing_control_characters_kotlin_trim_would_strip_are_refused() {
        // Kotlin's trim() strips U+001C–U+001F; the broker's JS trim() does not and refuses them.
        assertEquals("A name can't contain control characters", agentNameError("a\u001F"))
        assertEquals("A name can't contain control characters", agentNameError("\u001Ca"))
    }

    @Test fun whitespace_js_trim_strips_is_trimmed_not_refused() {
        // JS trim() strips tab/newline/etc. before the broker checks control characters.
        assertNull(agentNameError("Ada\t"))
        assertNull(agentNameError("\nAda\r"))
        assertNull(agentNameError("\uFEFFAda\u00A0"))
        assertEquals("A name can't contain control characters", agentNameError("A\tda"))
    }

    @Test fun agentNameTrim_matches_js_trim() {
        assertEquals("Ada", agentNameTrim("\uFEFFAda"))
        assertEquals("Ada", agentNameTrim("\t\u000B\u000C\u2028\u3000Ada\u2029\u00A0 "))
        // U+001C–U+001F: Kotlin's trim() strips them, JS trim() keeps them.
        assertEquals("a\u001F", agentNameTrim("a\u001F"))
    }

    @Test fun the_brokers_own_message_wins() {
        val f = AccountResult.Failed(409, "name_taken", "An agent named Dockie already exists")
        assertEquals("An agent named Dockie already exists", agentFailureText(f, "create the agent"))
    }

    @Test fun known_codes_have_copy_when_the_broker_sent_no_message() {
        assertEquals("That name can't be used.", agentFailureText(AccountResult.Failed(400, "name_invalid", null), "create the agent"))
        assertEquals("An agent with that name already exists.", agentFailureText(AccountResult.Failed(409, "name_taken", null), "create the agent"))
        assertEquals(
            "That provider isn't installed. Install it in Settings ▸ Providers.",
            agentFailureText(AccountResult.Failed(409, "agent_not_installed", null), "create the agent"),
        )
    }

    @Test fun anything_else_is_a_generic_retry_line() {
        assertEquals(
            "Couldn't kill the agent — check the connection and try again.",
            agentFailureText(AccountResult.Failed(null, null, null), "kill the agent"),
        )
    }
}
