package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Card #76: the live-text frames as the broker writes them, decoded with BrokerClient's settings. */
class LiveFramesTest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @Test fun replyStreamDecodes() {
        val f = json.decodeFromString<ServerFrame>("""{"type":"reply_stream","session":"s1","stream":"k1","at":3,"text":"lo"}""")
        assertEquals(ServerFrame.ReplyStream(session = "s1", stream = "k1", at = 3, text = "lo"), f)
        assertEquals("reply", (f as ServerFrame.ReplyStream).kind)
    }

    @Test fun thinkingStreamDecodes() {
        val f = json.decodeFromString<ServerFrame>("""{"type":"reply_stream","session":"s1","stream":"t1","kind":"thinking","at":0,"text":"Plan"}""")
        assertEquals("thinking", (f as ServerFrame.ReplyStream).kind)
    }

    @Test fun endDecodes() {
        assertEquals(
            ServerFrame.ReplyStreamEnd(session = "s1", stream = "k1"),
            json.decodeFromString<ServerFrame>("""{"type":"reply_stream_end","session":"s1","stream":"k1"}"""),
        )
    }

    @Test fun storedRowsCarryTheirStream() {
        val m = json.decodeFromString<ServerFrame>("""{"type":"message_append","session":"s1","entry":{"id":"out:web:1","ts":"t","direction":"outbound","text":"Hi","stream":"k1"}}""")
        assertEquals("k1", (m as ServerFrame.MessageAppend).entry.stream)
        val a = json.decodeFromString<ServerFrame>("""{"type":"activity_append","session":"s1","event":{"ts":"t","kind":"reasoning","title":"Thinking","detail":"Plan","stream":"t1"}}""")
        assertEquals("t1", (a as ServerFrame.ActivityAppend).event.stream)
    }

    @Test fun snapshotCarriesOpenStreams() {
        val s = json.decodeFromString<ServerFrame>("""{"type":"snapshot","streams":{"s1":[{"stream":"k1","kind":"reply","text":"so far"}]}}""")
        assertEquals(mapOf("s1" to listOf(LiveStreamDto("k1", "reply", "so far"))), (s as ServerFrame.Snapshot).streams)
    }
}
