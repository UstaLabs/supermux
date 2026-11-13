package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

/** #104: an agent's html card on its message, the user's message a card sent, and the send frame from a card. */
class HtmlCardWireTest {
    @Test fun anAgentMessageCarriesItsCardWithBothHeights() {
        val e = json.decodeFromString<LogEntry>(
            """{"id":"o1","ts":"t","direction":"outbound","text":"","card":{"id":"abc","title":"Sales","height":320,"desktop_height":240}}""",
        )
        assertEquals(HtmlCardDto("abc", "Sales", height = 320, desktopHeight = 240), e.card)
        assertNull(e.fromCard)
    }

    @Test fun aCardWithoutHeightsStillParses() {
        val e = json.decodeFromString<LogEntry>("""{"id":"o1","ts":"t","direction":"outbound","card":{"id":"abc","title":"Sales"}}""")
        assertEquals(HtmlCardDto("abc", "Sales"), e.card)
    }

    @Test fun theUsersMessageFromACardNamesIt() {
        val e = json.decodeFromString<LogEntry>("""{"id":"i1","ts":"t","direction":"inbound","text":"Use B","from_card":{"id":"abc","title":"Sales"}}""")
        assertEquals(FromCardDto("abc", "Sales"), e.fromCard)
    }

    @Test fun aSendFromACardCarriesItsIdAndNoAttachments() {
        val s = json.encodeToString(ClientFrame.serializer(), ClientFrame.Send("s1", args = SendArgs("Use B", fromCard = CardRef("abc"))))
        val args = json.parseToJsonElement(s).jsonObject["args"]!!.jsonObject
        assertEquals("""{"text":"Use B","from_card":{"id":"abc"}}""", args.toString())
    }

    @Test fun aPlainSendHasNoFromCardKey() {
        // The broker treats any from_card, even null, as a send from a card.
        val s = json.encodeToString(ClientFrame.serializer(), ClientFrame.Send("s1", args = SendArgs("hi")))
        assertFalse("from_card" in json.parseToJsonElement(s).jsonObject["args"]!!.jsonObject)
    }
}
