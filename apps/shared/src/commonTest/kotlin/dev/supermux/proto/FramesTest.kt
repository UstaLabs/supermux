package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

class FramesTest {
    @Test fun parses_agent_state_with_workingSince() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"agent_state","session":"editor","phase":"working","workingSince":1717200000000}""",
        )
        assertTrue(f is ServerFrame.AgentState)
        assertEquals(1717200000000, (f as ServerFrame.AgentState).workingSince)
    }

    @Test fun parses_agent_state_with_since_and_workingSince() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"agent_state","session":"editor","phase":"working","tool":"Bash","since":1717200000000,"workingSince":1717200005000}""",
        )
        assertTrue(f is ServerFrame.AgentState)
        assertEquals(1717200000000, (f as ServerFrame.AgentState).since)
        assertEquals(1717200005000, (f as ServerFrame.AgentState).workingSince)
    }

    @Test fun parses_session_removed_by_id() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"session_removed","id":"550e8400-e29b-41d4-a716-446655440000"}""",
        )
        assertTrue(f is ServerFrame.SessionRemoved)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", (f as ServerFrame.SessionRemoved).id)
    }

    // Display lifecycle frames — broker emits {type:"display_added",display:{...}}
    // and {type:"display_removed",id}.
    @Test fun parses_display_added() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"display_added","display":{"id":"d-1504c1bf","sessionName":"ios-display-parity","provider":"linux-xvfb","display":":100","status":"running","createdAt":"2026-06-20T05:05:39.056Z","transport":"vnc"}}""",
        )
        assertTrue(f is ServerFrame.DisplayAdded)
        val d = (f as ServerFrame.DisplayAdded).display
        assertEquals("d-1504c1bf", d.id)
        assertEquals("vnc", d.transport)
        assertEquals("linux-xvfb", d.provider)
        assertEquals("running", d.status)
    }

    @Test fun parses_display_removed() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"display_removed","id":"d-1504c1bf"}""",
        )
        assertTrue(f is ServerFrame.DisplayRemoved)
        assertEquals("d-1504c1bf", (f as ServerFrame.DisplayRemoved).id)
    }

    @Test fun decodesAgentStateWithNewFields() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"agent_state","session":"s1","state":"working","working":true,"detail":"running","tool":"Bash","since":5,"workingSince":4,"phase":"running"}""",
        )
        assertTrue(f is ServerFrame.AgentState)
        val a = f as ServerFrame.AgentState
        assertEquals("working", a.state)
        assertEquals(true, a.working)
        assertEquals("running", a.detail)
        assertEquals("Bash", a.tool)
        assertEquals(4L, a.workingSince)
    }

    @Test fun agentStatusDefaultsAreSafe() {
        val s = json.decodeFromString<AgentStatus>("""{"phase":"idle"}""")
        assertEquals(false, s.working)
        assertEquals("idle", s.state)
        assertNull(s.detail)
        assertNull(s.tool)
        assertNull(s.workingSince)
    }
}
