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

    // A workspace terminal is the one that speaks the ordered revision; a
    // session/agent socket is still on the legacy framing and says nothing.
    @Test fun workspace_scope_uses_workspace_query_and_asks_for_revision_2() {
        assertEquals(
            "ws://h:1/ws/term?workspace=w1&terminal=main&terminalProtocol=2",
            termWsUrl("ws://h:1", "", "scratch", "main", workspaceId = "w1"),
        )
    }

    @Test fun workspace_scope_without_terminal_id() {
        assertEquals(
            "ws://h:1/ws/term?workspace=w1&terminalProtocol=2",
            termWsUrl("ws://h:1", "", "scratch", null, workspaceId = "w1"),
        )
    }

    @Test fun create_says_new_terminal_or_reconnect_and_is_omitted_when_unsaid() {
        assertEquals(
            "ws://h:1/ws/term?workspace=w1&terminal=main&terminalProtocol=2&create=1",
            termWsUrl("ws://h:1", "", "scratch", "main", workspaceId = "w1", create = true),
        )
        // A reconnect must never resurrect a terminal whose shell exited.
        assertEquals(
            "ws://h:1/ws/term?workspace=w1&terminal=main&terminalProtocol=2&create=0",
            termWsUrl("ws://h:1", "", "scratch", "main", workspaceId = "w1", create = false),
        )
    }

    // These were interpolated raw. A session name is free-form — "fix: the thing"
    // is one this repository has used — so this is not about a hostile id: an
    // ordinary title with a space, an ampersand or a non-ASCII character was
    // enough to send a parameter nobody meant, truncate the query at a `#`, or
    // put a byte in the handshake line that does not belong there.
    @Test fun ids_are_percent_encoded_so_a_name_cannot_become_a_parameter() {
        assertEquals(
            "ws://h:1/ws/term?session=a%26kind%3Dagent",
            termWsUrl("ws://h:1", "a&kind=agent", "scratch", null),
        )
        assertEquals(
            "ws://h:1/ws/term?session=fix%3A%20the%20thing&terminal=t%2F1",
            termWsUrl("ws://h:1", "fix: the thing", "scratch", "t/1"),
        )
        // A `#` used to truncate everything after it.
        assertEquals(
            "ws://h:1/ws/term?session=s%23frag&kind=agent",
            termWsUrl("ws://h:1", "s#frag", "agent", null),
        )
        // UTF-8, byte by byte: "dü" is 64 C3 BC.
        assertEquals(
            "ws://h:1/ws/term?workspace=d%C3%BC&terminalProtocol=2",
            termWsUrl("ws://h:1", "", "scratch", null, workspaceId = "dü"),
        )
        // The unreserved set is untouched, so every existing URL is unchanged.
        assertEquals(
            "ws://h:1/ws/term?workspace=w-1_2.3~4&terminal=main&terminalProtocol=2",
            termWsUrl("ws://h:1", "", "scratch", "main", workspaceId = "w-1_2.3~4"),
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
