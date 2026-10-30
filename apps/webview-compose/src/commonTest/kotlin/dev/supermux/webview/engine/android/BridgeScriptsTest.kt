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

    @Test fun aChannelsScriptSendsThroughThatChannel() {
        val script = bridgeScript(token, channelName(1))
        assertTrue("window.${CHANNEL_NAME}1" in script)
        assertTrue("window.$CHANNEL_NAME)" !in script && "window.$CHANNEL_NAME," !in script, "only its own channel")
    }

    /**
     * A lease's channels as its bridge origins change: a listener is never taken away mid-lease (WebView would drop what
     * the documents that have its object send, the current one too), origins added get a channel of their own, and the
     * shims follow the origins allowed now (review, minor).
     */
    @Test fun channelsOnlyGrowMidLeaseAndTheShimsFollowTheOrigins() {
        val channels = BridgeChannels()
        val a = "https://a.invalid"
        val b = "https://b.example"
        val first = assertNotNull(channels.update(setOf(a)))
        assertEquals(CHANNEL_NAME to setOf(a), first.name to first.rules)
        assertEquals(listOf(CHANNEL_NAME to setOf(a)), channels.shims().map { it.first.name to it.second })
        val second = assertNotNull(channels.update(setOf(a, b)), "b gets a channel of its own")
        assertEquals(channelName(1) to setOf(b), second.name to second.rules)
        assertEquals(setOf(a, b), channels.allowed)
        assertEquals(listOf(CHANNEL_NAME to setOf(a), channelName(1) to setOf(b)), channels.shims().map { it.first.name to it.second })
        assertNull(channels.update(setOf(a)), "b taken away: its channel stays")
        assertEquals(setOf(a), channels.allowed)
        assertEquals(listOf(CHANNEL_NAME to setOf(a)), channels.shims().map { it.first.name to it.second })
        assertNull(channels.update(emptySet()), "no JavaScript, or no origins: no shims, the channels stay")
        assertTrue(channels.shims().isEmpty())
        assertNull(channels.update(setOf(b)), "b again: its channel is still there")
        assertEquals(listOf(channelName(1) to setOf(b)), channels.shims().map { it.first.name to it.second })
        channels.clear()
        assertEquals(emptySet<String>(), channels.allowed)
        assertEquals(CHANNEL_NAME, assertNotNull(channels.update(setOf(b))).name, "a new WebView starts over")
    }

    @Test fun aSignedMessageSplitsIntoTokenAndJson() {
        assertEquals(token to """{"t":"event"}""", splitSigned(token + """{"t":"event"}"""))
        assertNull(splitSigned(token))
        assertNull(splitSigned("{}"))
    }
}
