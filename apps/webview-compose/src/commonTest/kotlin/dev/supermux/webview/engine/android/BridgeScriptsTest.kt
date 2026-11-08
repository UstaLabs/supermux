package dev.supermux.webview.engine.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeScriptsTest {
    private val token = "Ab3dEf6hIj9kLm2nOp5qRs8t"

    @Test fun originRulesAreTheHttpAndHttpsBridgeOrigins() {
        assertEquals(
            setOf("https://cabc.invalid", "http://localhost:8080", "https://app.example.com"),
            allowedOriginRules(setOf("https://cabc.invalid", "http://localhost:8080", "https://app.example.com", "my-app://x")),
        )
        assertEquals(emptySet(), allowedOriginRules(emptySet()))
    }

    @Test fun messageOriginsAreSerializedAsTheBridgeComparesThem() {
        assertEquals("https://a.test", messageOrigin("https://A.test"))
        assertEquals("https://a.test", messageOrigin("https://a.test:443"))
        assertEquals("http://localhost:8080", messageOrigin("http://localhost:8080"))
        assertEquals("null", messageOrigin("null"))
    }

    @Test fun theScriptIsTheShimSendingThroughTheChannelWithTheToken() {
        val script = bridgeScript(token)
        assertTrue(script.startsWith("(function () {"), "the shim comes first")
        assertTrue("window.$CHANNEL_NAME" in script)
        assertTrue("__hostBridgeReceive" in script)
        assertTrue("Reflect.apply" in script)
        assertEquals(1, Regex(token).findAll(script).count(), "the token appears only in the closure that sends")
    }

    @Test fun aTokenIsTwentyFourLettersAndDigits() {
        assertFailsWith<IllegalArgumentException> { bridgeScript("short") }
        assertFailsWith<IllegalArgumentException> { bridgeScript("Ab3dEf6hIj9kLm2nOp5qRs8'") }
    }

    /**
     * The bridge's one listener as a lease's origins change (review, minor). It is never taken away while a document
     * may use it (WebView would drop what that document sends, even of an origin still allowed), and never joined by a
     * second (one added to a WebView with a document crashed WebView 156): a change it covers keeps it, with only the
     * shim following the origins allowed; one it doesn't replaces it at the next boundary, when the document goes, or
     * at once on a WebView that has never had one.
     */
    @Test fun theListenerStaysWhileItCoversWhatIsAllowedAndIsReplacedOnlyAtABoundary() {
        val channels = BridgeChannels()
        val a = "https://a.invalid"
        val b = "https://b.example"
        assertEquals(setOf(a), channels.update(setOf(a), idle = true), "a WebView that never had a document: at once")
        assertEquals(setOf(a), channels.shimRules())
        assertNull(channels.boundary(), "it covers what is allowed")
        assertNull(channels.update(setOf(a, b), idle = false), "b added mid-lease: the listener stays for the document")
        assertEquals(setOf(a), channels.shimRules(), "a shim only where the listener is")
        assertEquals(setOf(a, b), channels.allowed)
        assertNull(channels.update(setOf(a), idle = false), "b taken off again before the document went: nothing to do")
        assertNull(channels.boundary())
        assertNull(channels.update(emptySet(), idle = false), "no JavaScript: no shim, the listener stays")
        assertEquals(emptySet<String>(), channels.shimRules())
        assertNull(channels.update(setOf(a, b), idle = false))
        assertEquals(setOf(a, b), channels.boundary(), "the document goes: the listener is replaced, for a and b")
        assertEquals(setOf(a, b), channels.shimRules())
        assertNull(channels.boundary(), "once")
        assertNull(channels.update(setOf(b), idle = false), "a taken off: the listener stays, and covers b")
        assertEquals(setOf(b), channels.shimRules())
        channels.clear()
        assertEquals(emptySet<String>(), channels.allowed)
        assertNull(channels.update(emptySet(), idle = true), "no origins: no listener")
        assertNull(channels.update(setOf(b), idle = false), "a document is there: at its boundary")
        assertEquals(setOf(b), channels.boundary())
    }

    @Test fun aSignedMessageSplitsIntoTokenAndJson() {
        assertEquals(token to """{"t":"event"}""", splitSigned(token + """{"t":"event"}"""))
        assertNull(splitSigned(token))
        assertNull(splitSigned("{}"))
    }
}
