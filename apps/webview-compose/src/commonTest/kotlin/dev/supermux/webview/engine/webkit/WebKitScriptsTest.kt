package dev.supermux.webview.engine.webkit

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebKitScriptsTest {
    private val scripts = WebKitScripts("webview", "webview-out-1", "webview-in-1")
    private val origins = setOf("https://c1.invalid", "webview-bundle://c1.invalid")

    @Test fun eachEngineHasEventNamesOfItsOwn() {
        val a = WebKitScripts.random(Random(1))
        val b = WebKitScripts.random(Random(2))
        assertNotEquals(a.outEvent, b.outEvent)
        assertNotEquals(a.inEvent, b.inEvent)
        assertTrue(Regex("^webview-out-[0-9a-f]{16}$").matches(a.outEvent))
        assertEquals(a.outEvent.removePrefix("webview-out-"), a.inEvent.removePrefix("webview-in-"))
    }

    @Test fun theRelayHoldsTheHandlerAndForwardsOnlyForListedOrigins() {
        val relay = scripts.relay(origins)
        assertContains(relay, "handlers[\"webview\"]")
        assertContains(relay, "document.addEventListener(\"webview-out-1\"")
        assertContains(relay, "[\"https://c1.invalid\",\"webview-bundle://c1.invalid\"]")
        assertContains(relay, "if (!(window === window.top)) return;")
        assertContains(relay, "if (origins.indexOf(self.origin) < 0) return;")
        assertContains(relay, "document.addEventListener(\"webview-out-1-ask\", function (e) { e.preventDefault(); });")
        assertContains(relay, "document.dispatchEvent(new CustomEvent(\"webview-out-1-up\"));")
        assertFalse("__" in relay.replace("__hostBridgeReceive", ""), "every placeholder is filled")
    }

    @Test fun theShimSendsThroughTheOutEventAndReceivesTheInEvent() {
        val shim = scripts.shim(origins)
        assertContains(shim, "fire(new E(\"webview-out-1\", { detail: s }))")
        assertContains(shim, "fire(new E(\"webview-out-1-ask\", { cancelable: true }))")
        assertContains(shim, "listen(\"webview-out-1-up\"")
        assertContains(shim, "(window === window.top) && [\"https://c1.invalid\",\"webview-bundle://c1.invalid\"].indexOf(self.origin) >= 0")
        assertContains(shim, "document.addEventListener(\"webview-in-1\"")
        assertContains(shim, "window.__hostBridgeReceive")
    }

    @Test fun theUsersInputIsReportedOnlyByRelaysAskedForItAndOnlyFromTrustedEvents() {
        assertFalse("pointerup" in scripts.relay(origins), "no input unless asked")
        val relay = scripts.relay(origins, input = true)
        for (event in listOf("pointerup", "click", "keydown", "pointerdown", "scroll", "wheel")) {
            assertContains(relay, "window.addEventListener('$event'")
        }
        assertContains(relay, "if (!e.isTrusted) return;")
        assertContains(relay, "send('gesture')")
        assertContains(relay, "send('use')")
        assertFalse("__" in relay.replace("__hostBridgeReceive", ""), "every placeholder is filled")
    }

    @Test fun theFramesInputSkipsTheMainFrameAndReportsUnderTheFrameToken() {
        val frames = scripts.frameInput()
        assertContains(frames, "if (window === window.top) return;")
        assertContains(frames, "handlers[\"webview\"]")
        assertContains(frames, "prefix = \"${WebKitScripts.FRAME_TOKEN}\" + ' '")
        assertContains(frames, "send('gesture')")
        assertFalse("__" in frames, "every placeholder is filled")
        assertEquals(WebKitScripts.FRAME_TOKEN, RelayMessage.parse("${WebKitScripts.FRAME_TOKEN} gesture")?.document)
    }

    @Test fun originsAreQuotedAsJavaScriptStrings() {
        val relay = scripts.relay(setOf("https://a.example\"];alert(1);//"))
        assertContains(relay, "[\"https://a.example\\\"];alert(1);//\"]")
    }

    @Test fun deliveryChecksTheOriginBeforeDispatching() {
        val body = scripts.deliveryBody
        assertTrue(body.indexOf("self.origin !== target") < body.indexOf("dispatchEvent"))
    }
}
