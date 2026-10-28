package dev.supermux.webview.bridge

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BridgeCodecTest {
    @Test fun everyKindRoundTrips() {
        val messages = listOf(
            BridgeMessage.Call(7, "add", buildJsonArray { add(1); add(2) }),
            BridgeMessage.Reply(7, JsonPrimitive(3)),
            BridgeMessage.Reply(8, error = "boom"),
            BridgeMessage.Event("ready", buildJsonObject { put("ok", true) }),
            BridgeMessage.Event("bare"),
        )
        for (message in messages) assertEquals(message, BridgeCodec.decode(BridgeCodec.encode(message)))
    }

    @Test fun aReplyWithANullResultDecodesAsJsonNull() {
        assertEquals(BridgeMessage.Reply(1, JsonNull, null), BridgeCodec.decode("""{"t":"reply","id":1,"r":null}"""))
    }

    @Test fun malformedInputIsNull() {
        listOf(
            "", "not json", "[]", "{}", "\"str\"",
            """{"t":"call","m":"x"}""",
            """{"t":"call","id":"1","m":"x"}""",
            """{"t":"call","id":1}""",
            """{"t":"event"}""",
            """{"t":"other","id":1}""",
            """{"t":5}""",
        ).forEach { assertNull(BridgeCodec.decode(it), "should reject: $it") }
    }

    @Test fun anOversizedMessageIsDroppedUnread() {
        val huge = """{"t":"event","n":"x","d":"${"a".repeat(BridgeCodec.MAX_LENGTH)}"}"""
        assertNull(BridgeCodec.decode(huge))
    }
}
