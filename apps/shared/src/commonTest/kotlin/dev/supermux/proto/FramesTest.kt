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

    // session_state carries per-session patches (model/effort switches, mute,
    // shim connect). Natives dropped it pre-2026-07-11 → stale pills.
    @Test fun parses_session_state_model_and_reasoning() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"session_state","session":"abc","model":"claude-opus-4-8","reasoningLevel":"low"}""",
        )
        assertTrue(f is ServerFrame.SessionState)
        assertEquals("claude-opus-4-8", (f as ServerFrame.SessionState).model)
        assertEquals("low", f.reasoningLevel)
        assertNull(f.mute)
        assertNull(f.connected)
    }

    @Test fun parses_session_state_mute_only() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"session_state","session":"abc","mute":true}""",
        )
        assertTrue(f is ServerFrame.SessionState)
        assertEquals(true, (f as ServerFrame.SessionState).mute)
        assertNull(f.model)
    }

    @Test fun session_info_parses_reasoning_level_from_snapshot() {
        val s = json.decodeFromString<SessionInfo>(
            """{"id":"1","name":"a","workdir":"/w","agent":"claude","model":"claude-sonnet-5","reasoningLevel":"xhigh"}""",
        )
        assertEquals("xhigh", s.reasoningLevel)
    }

    @Test fun session_info_parses_user_status_snake_case() {
        val s = json.decodeFromString<SessionInfo>(
            """{"id":"1","name":"a","workdir":"/w","agent":"claude","user_status":"draft","sort_order":3,"draft_payload":{"text":"hello"}}""",
        )
        assertEquals("draft", s.userStatus)
        assertEquals(3, s.sortOrder)
        assertEquals("hello", s.draftPayload?.text)
    }

    @Test fun parses_session_removed_by_id() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"session_removed","id":"550e8400-e29b-41d4-a716-446655440000"}""",
        )
        assertTrue(f is ServerFrame.SessionRemoved)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", (f as ServerFrame.SessionRemoved).id)
    }

    @Test fun parses_session_renamed_with_natural_display_name() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"session_renamed","id":"s1","old":"debug-session-renaming","new":"Fix Session Renaming 🎉"}""",
        )
        assertTrue(f is ServerFrame.SessionRenamed)
        assertEquals("s1", (f as ServerFrame.SessionRenamed).id)
        assertEquals("debug-session-renaming", f.old)
        assertEquals("Fix Session Renaming 🎉", f.newName)
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
