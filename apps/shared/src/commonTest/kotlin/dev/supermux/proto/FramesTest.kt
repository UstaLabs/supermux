package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
