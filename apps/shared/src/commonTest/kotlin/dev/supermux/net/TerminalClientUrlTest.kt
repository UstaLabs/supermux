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

    @Test fun http_base_is_converted_to_ws() {
        assertEquals(
            "ws://h:1/ws/term?session=s",
            termWsUrl("http://h:1", "s", "scratch", null),
        )
    }

    @Test fun https_base_is_converted_to_wss() {
        assertEquals(
            "wss://h/ws/term?session=s&kind=agent",
            termWsUrl("https://h", "s", "agent", null),
        )
    }

    @Test fun terminal_focus_frame_carries_the_authoritative_grid_size() {
        assertEquals(
            "{\"type\":\"focus\",\"focused\":true,\"cols\":61,\"rows\":27}",
            terminalFocusFrame(true, 61, 27),
        )
        assertEquals(
            "{\"type\":\"focus\",\"focused\":false}",
            terminalFocusFrame(false, 61, 27),
        )
    }
}
