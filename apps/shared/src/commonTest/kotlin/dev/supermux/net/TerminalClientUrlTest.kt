package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalClientUrlTest {
    @Test fun scratch_default_has_no_extra_params() {
        assertEquals(
            "ws://h:1/ws/term?session=s",
            termWsUrl("ws://h:1", "s", "scratch", null),
        )
    }

    @Test fun scratch_with_terminal_id_appends_terminal() {
        assertEquals(
            "ws://h:1/ws/term?session=s&terminal=abc123",
            termWsUrl("ws://h:1", "s", "scratch", "abc123"),
        )
    }

    @Test fun agent_appends_kind() {
        assertEquals(
            "ws://h:1/ws/term?session=s&kind=agent",
            termWsUrl("ws://h:1", "s", "agent", null),
        )
    }

    @Test fun agent_ignores_terminal_id() {
        assertEquals(
            "ws://h:1/ws/term?session=s&kind=agent",
            termWsUrl("ws://h:1", "s", "agent", "abc123"),
        )
    }
}
