package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

class ChatFramesTest {
    @Test fun parses_message_append_inbound_and_outbound() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"message_append","session":"s1","entry":{"id":"in:1","ts":"2026-06-01T00:00:00Z","direction":"inbound","channel":"web","chat_id":"web","message_id":"m1","text":"hi"}}""")
        assertTrue(f is ServerFrame.MessageAppend)
        assertEquals("inbound", (f as ServerFrame.MessageAppend).entry.direction)
        assertEquals("hi", f.entry.text)
    }
    @Test fun parses_activity_append_tool() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"activity_append","session":"s1","event":{"ts":"2026-06-01T00:00:00Z","kind":"tool","tool":"Bash","title":"Bash: ls","phase":"started","seq":1,"callId":"c1"}}""")
        assertTrue(f is ServerFrame.ActivityAppend)
        assertEquals("Bash", (f as ServerFrame.ActivityAppend).event.tool)
    }
    @Test fun parses_snapshot_with_logs_activity_agentState() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"snapshot","sessions":[],"logs":{"s1":[{"id":"o1","ts":"t","direction":"outbound","text":"hello"}]},"activity":{"s1":[{"ts":"t","kind":"thinking","title":"Thought for 3s","seq":0}]},"agentState":{"s1":{"phase":"working","since":1717200000000}}}""")
        assertTrue(f is ServerFrame.Snapshot)
        val snap = f as ServerFrame.Snapshot
        assertEquals("hello", snap.logs["s1"]!![0].text)
        assertEquals("thinking", snap.activity["s1"]!![0].kind)
        assertEquals("working", snap.agentState["s1"]!!.phase)
    }
    @Test fun encodes_send_frame() {
        val s = Json.encodeToString(ClientFrame.serializer(), ClientFrame.Send("s1", args = SendArgs("hello")))
        assertTrue(s.contains("\"type\":\"send\"") && s.contains("\"text\":\"hello\"") && s.contains("\"op\":\"reply\""))
    }
}
