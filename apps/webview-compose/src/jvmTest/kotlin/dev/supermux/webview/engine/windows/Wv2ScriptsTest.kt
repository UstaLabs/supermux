package dev.supermux.webview.engine.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Wv2ScriptsTest {
    private val scripts = Wv2Scripts(receiverName = "__wv_r0123456789abcdef", channelName = "__wv_c0123456789abcdef")
    private val secret = "0123456789abcdef0123456789abcdef"

    @Test fun theSecretIsOnlyTheOuterFunctionsArgument() {
        val text = scripts.document(secret, setOf("https://a.invalid"), shim = true)
        assertEquals(1, Regex(secret).findAll(text).count(), "one occurrence: the argument")
        assertTrue(text.contains("})(\"$secret\", "), "passed in, never written inside a function")
        assertTrue(text.indexOf("'use strict'") in 0 until text.indexOf("send('start')"), "the functions that hold it are strict")
    }

    @Test fun withoutTheShimThePageGetsNothing() {
        val text = scripts.document(secret, setOf("https://a.invalid"), shim = false)
        assertFalse("hostBridge" in text)
        assertFalse(scripts.receiverName in text)
        assertFalse(scripts.channelName in text)
        assertTrue("send('paint ' + (at < 0 ? '-' : clock() - at) + ' ' + background())" in text, "a first paint is still reported")
    }

    @Test fun aPaintSaysWhenTheFirstPaintWithSomethingToShowWasPresented() {
        // WebView2's capture can be a composition or two behind the page: a paint at the load, before the page's first
        // frame reached the screen, showed WebView2's white blank (the checks on WARP). Chromium reports first paints by
        // when they were presented: the script waits for one, a contentful one first, and says how long ago it was.
        val text = scripts.document(secret, setOf("https://a.invalid"), shim = true)
        assertTrue("'first-contentful-paint'" in text && "'first-paint'" in text)
        assertTrue("send('paint ' + (at < 0 ? '-' : clock() - at) + ' ' + background())" in text)
        // And the page's own background: WebView2's blank frames are its default background, or nothing.
        assertTrue("'background-color'" in text)
        assertTrue(text.indexOf("PerformanceObserverEntryList.prototype.getEntries") in 0 until text.indexOf("send('start')"), "captured before any page script")
    }

    @Test fun theShimTakesItsChannelOnceAndTheReceiverRemovesIt() {
        val text = scripts.document(secret, setOf("https://a.invalid"), shim = true)
        assertTrue("delete window[\"${scripts.channelName}\"]" in text)
        assertTrue(text.indexOf("window.__hostBridgeReceive") > text.indexOf("hostBridge"), "the receiver captures the shim's receive after the shim")
        assertTrue("configurable: false" in text && "writable: false" in text)
    }

    @Test fun theOriginsAreTheLibrarysOwn() {
        val text = scripts.document(secret, setOf("https://b.invalid", "https://a.example"), shim = true)
        assertTrue("[\"https://a.example\",\"https://b.invalid\"]" in text, "sorted JSON")
    }

    @Test fun deliveryQuotesWhateverItCarries() {
        assertEquals(
            "window[\"__wv_r0123456789abcdef\"](\"{\\\"a\\\":\\\"</script>\\\"}\", \"https://a.invalid\")",
            scripts.deliveryCall("{\"a\":\"</script>\"}", "https://a.invalid"),
        )
    }

    @Test fun secretsAndNamesAreFresh() {
        val a = Wv2Scripts.newSecret()
        assertTrue(a.matches(Regex("[0-9a-f]{32}")), a)
        assertNotEquals(a, Wv2Scripts.newSecret())
        val one = Wv2Scripts.random()
        val two = Wv2Scripts.random()
        assertNotEquals(one.receiverName, two.receiverName)
        assertTrue(one.receiverName.matches(Regex("__wv_r[0-9a-f]{16}")), one.receiverName)
    }
}
