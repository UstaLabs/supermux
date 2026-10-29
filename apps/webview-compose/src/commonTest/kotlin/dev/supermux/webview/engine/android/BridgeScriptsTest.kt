package dev.supermux.webview.engine.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test fun aSignedMessageSplitsIntoTokenAndJson() {
        assertEquals(token to """{"t":"event"}""", splitSigned(token + """{"t":"event"}"""))
        assertNull(splitSigned(token))
        assertNull(splitSigned("{}"))
    }
}
