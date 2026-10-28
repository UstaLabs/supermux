package dev.supermux.webview.bridge

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
        assertEquals(BridgeMessage.Reply(1, JsonNull, null), BridgeCodec.decode("""{"t":"reply","id":1,"e":null,"r":null}"""))
    }

    @Test fun aNonStringErrorIsStillAnError() {
        assertEquals(BridgeMessage.Reply(1, null, "invalid error"), BridgeCodec.decode("""{"t":"reply","id":1,"e":5}"""))
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
            """{"t":"event","n":"x"""",
        ).forEach { assertNull(BridgeCodec.decode(it), "should reject: $it") }
    }

    @Test fun unquotedTokensAreNotJson() {
        listOf(
            """{"t":"event","n":"x","d":alert(1)}""",
            """{"t":"event","n":"x","d":NaN}""",
            """{"t":"event","n":"x","d":Infinity}""",
            """{"t":"event","n":"x","d":undefined}""",
            """{"t":"event","n":"x","d":[1,two]}""",
        ).forEach { assertNull(BridgeCodec.decode(it), it) }
    }

    @Test fun deepNestingIsRefusedBeforeParsing() {
        fun nested(depth: Int) = """{"t":"event","n":"x","d":${"[".repeat(depth)}${"]".repeat(depth)}}"""
        assertNotNull(BridgeCodec.decode(nested(31)))
        assertNull(BridgeCodec.decode(nested(32)))
        assertNull(BridgeCodec.decode(nested(100_000), maxChars = Int.MAX_VALUE))
    }

    @Test fun bracketsInsideStringsAreNotNesting() {
        for (text in listOf("[".repeat(1_000), "a\"[[", "\\[{")) {
            val event = BridgeMessage.Event("x", JsonPrimitive(text))
            assertEquals(event, BridgeCodec.decode(BridgeCodec.encode(event)))
        }
    }

    @Test fun anOversizedMessageIsRefusedUnread() {
        assertNull(BridgeCodec.decode("""{"t":"event","n":"x","d":"${"a".repeat(BridgeCodec.DEFAULT_MAX_CHARS)}"}"""))
    }

    @Test fun encodingRefusesNumbersJsonCannotCarry() {
        assertFailsWith<IllegalArgumentException> { BridgeCodec.encode(BridgeMessage.Event("x", JsonPrimitive(Double.NaN))) }
        assertFailsWith<IllegalArgumentException> { BridgeCodec.encode(BridgeMessage.Reply(1, JsonPrimitive(Double.POSITIVE_INFINITY))) }
    }
}
