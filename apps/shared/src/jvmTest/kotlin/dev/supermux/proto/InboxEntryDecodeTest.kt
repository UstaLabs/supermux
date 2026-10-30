package dev.supermux.proto

import dev.supermux.net.MessageSearchResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An inbox row (a message from another session, supermux or a council) keeps who sent it, the
 * council tag and the error flag (decision #19): the chat draws it from these, so the decoder must
 * not drop them the way it drops a key the type does not know.
 */
class InboxEntryDecodeTest {
    /** What BrokerClient decodes frames with. */
    private val app = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    /** The fixture tests' strict parser: it throws on a key the type does not know. */
    private val strict = Json { ignoreUnknownKeys = false; classDiscriminator = "type" }

    private val inboxFrame = """
        {"type":"message_append","session":"s-3","entry":{"id":"in:inbox:m_1","ts":"2026-10-29T20:51:44.166Z","direction":"inbound","channel":"inbox","chat_id":"","message_id":"m_1","text":"Hi from personal-4","from_session_id":"s-4","from_session_name":"personal-4","from_agent":"claude","council_id":"c-1","council_round":2,"council_letter":"B","error":false}}
    """.trimIndent()

    private val errorFrame = """
        {"type":"message_append","session":"s-3","entry":{"id":"out:1","ts":"2026-10-29T20:52:00.000Z","direction":"outbound","channel":"web","chat_id":"web","op":"reply","text":"error: boom","error":true}}
    """.trimIndent()

    @Test fun an_inbox_row_keeps_its_sender_council_tag_and_error_flag() {
        for (json in listOf(app, strict)) {
            val entry = (json.decodeFromString<ServerFrame>(inboxFrame) as ServerFrame.MessageAppend).entry
            assertEquals("inbox", entry.channel)
            assertEquals("s-4", entry.from_session_id)
            assertEquals("personal-4", entry.from_session_name)
            assertEquals("claude", entry.from_agent)
            assertEquals("c-1", entry.council_id)
            assertEquals(2, entry.council_round)
            assertEquals("B", entry.council_letter)
            assertFalse(entry.error)
        }
    }

    @Test fun an_error_reply_decodes_its_flag_and_an_ordinary_row_has_none_of_it() {
        val error = (app.decodeFromString<ServerFrame>(errorFrame) as ServerFrame.MessageAppend).entry
        assertTrue(error.error)
        val plain = (app.decodeFromString<ServerFrame>(
            """{"type":"message_append","session":"s1","entry":{"id":"in:1","ts":"2026-06-01T00:00:00Z","direction":"inbound","channel":"web","chat_id":"web","message_id":"m1","text":"hi"}}""",
        ) as ServerFrame.MessageAppend).entry
        assertFalse(plain.error)
        assertNull(plain.from_session_id)
        assertNull(plain.from_session_name)
        assertNull(plain.from_agent)
        assertNull(plain.council_id)
    }

    @Test fun a_search_hit_names_an_inbox_messages_channel_and_sender() {
        val hits = app.decodeFromString<MessageSearchResponse>(
            """{"hits":[{"session_id":"s-3","session_name":"personal-3","workdir":"/w","agent":"claude","message_id":"in:inbox:m1","ts":"2026-10-29T20:51:44Z","direction":"inbound","channel":"inbox","from_session_name":"personal-4","snippet":"Hi"},{"session_id":"s-3","message_id":"in:web:1","direction":"inbound","snippet":"older broker"}]}""",
        ).hits
        assertEquals("inbox", hits[0].channel)
        assertEquals("personal-4", hits[0].from_session_name)
        // An older broker sends neither.
        assertEquals("", hits[1].channel)
        assertNull(hits[1].from_session_name)
    }
}
