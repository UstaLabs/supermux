package dev.supermux.webview.engine.webkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelayMessageTest {
    private val doc = "0123456789abcdef"

    @Test fun eachKindParses() {
        assertEquals(RelayMessage.Start(doc), RelayMessage.parse("$doc start"))
        assertEquals(RelayMessage.Paint(doc), RelayMessage.parse("$doc paint"))
        assertEquals(RelayMessage.Focus(doc, focused = true), RelayMessage.parse("$doc focus"))
        assertEquals(RelayMessage.Focus(doc, focused = false), RelayMessage.parse("$doc blur"))
        assertEquals(RelayMessage.Title(doc), RelayMessage.parse("$doc title"))
        assertEquals(RelayMessage.Url(doc), RelayMessage.parse("$doc url"))
        assertEquals(RelayMessage.Page(doc, "{\"t\":\"event\",\"n\":\"a b\"}"), RelayMessage.parse("$doc msg {\"t\":\"event\",\"n\":\"a b\"}"))
    }

    @Test fun anythingElseIsDropped() {
        assertNull(RelayMessage.parse(""))
        assertNull(RelayMessage.parse("$doc"))
        assertNull(RelayMessage.parse("$doc unknown"))
        assertNull(RelayMessage.parse("$doc msg"))
        assertNull(RelayMessage.parse("0123456789ABCDEF start"))
        assertNull(RelayMessage.parse("0123456789abcde start"))
        assertNull(RelayMessage.parse("0123456789abcdefstart"))
        assertNull(RelayMessage.parse("{\"t\":\"event\"}"))
    }
}
