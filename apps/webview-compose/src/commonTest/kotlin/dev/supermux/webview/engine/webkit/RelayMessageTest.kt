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
        assertEquals(RelayMessage.Input(doc, gesture = true), RelayMessage.parse("$doc gesture"))
        assertEquals(RelayMessage.Input(doc, gesture = false), RelayMessage.parse("$doc use"))
        assertEquals(RelayMessage.Page(doc, "{\"t\":\"event\",\"n\":\"a b\"}"), RelayMessage.parse("$doc msg {\"t\":\"event\",\"n\":\"a b\"}"))
    }

    @Test fun aPaintMaySayHowLongAgoItsContentWasPresented() {
        // Windows' document script (Wv2Scripts): WebView2's capture can be a composition or two behind the page.
        assertEquals(RelayMessage.Paint(doc, presentedAgoMillis = 31.5), RelayMessage.parse("$doc paint 31.5"))
        assertEquals(RelayMessage.Paint(doc), RelayMessage.parse("$doc paint"))
        assertEquals(RelayMessage.Paint(doc), RelayMessage.parse("$doc paint soon"), "an age it can't read: a paint all the same")
        assertEquals(RelayMessage.Paint(doc), RelayMessage.parse("$doc paint -3"))
        assertEquals(RelayMessage.Paint(doc), RelayMessage.parse("$doc paint NaN"))
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
