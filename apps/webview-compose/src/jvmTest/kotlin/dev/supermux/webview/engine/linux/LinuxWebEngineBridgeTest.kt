package dev.supermux.webview.engine.linux

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.webkit.WebKitUrls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LinuxWebEngineBridgeTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))
    private val bundle = WebContent.Bundle(WebOrigin("app"), WebResourceProvider { null })
    private val ready = "{\"t\":\"event\",\"n\":\"\$ready\",\"d\":{\"nonce\":\"n1\"},\"from\":\"n1\"}"

    @Test fun aHostMessageGoesThroughTheScriptWorldWithItsTarget() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.postMessage("{\"t\":\"event\",\"n\":\"ping\"}", demo.origin.origin)
        assertEquals(listOf("callAsync ${WebEngineWorld.WORLD}"), rig.view.calls.filter { it.startsWith("callAsync") })
        assertEquals(
            mapOf("json" to "{\"t\":\"event\",\"n\":\"ping\"}", "target" to demo.origin.origin, "name" to "webview-in-t"),
            rig.view.delivered.single(),
        )
        rig.close()
    }

    @Test fun aBundlesTargetIsItsOriginAsItsPagesSeeIt() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(bundle, WebKitUrls.bundleUrl(bundle.origin, "index.html"))
        rig.engine.postMessage("{}", bundle.origin.origin)
        assertEquals("webview-bundle://app.invalid", rig.view.delivered.single()["target"])
        rig.close()
    }

    @Test fun pageMessagesCarryTheCommittedDocumentsOrigin() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WpeEvent.Relay("0123456789abcdef msg $ready"))
        assertEquals(EngineEvent.PageMessage(demo.origin.origin, ready), rig.events.last())
        rig.close()
    }

    /**
     * A parked page that floods its bridge: past what the queue holds while no view subscribes, the engine goes the way
     * of a crash (unusable), so the pool discards it rather than hand it to a view.
     */
    @Test fun aParkedPageThatFloodsItsBridgeCrashes() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.collector?.cancel()
        runCurrent()
        val tick = "{\"t\":\"event\",\"n\":\"tick\",\"from\":\"n1\"}"
        repeat(1_024) { rig.engine.onEvent(WpeEvent.Relay("0123456789abcdef msg $tick")) }
        assertTrue(rig.engine.isUsable, "within what a parked page may hold")
        rig.engine.onEvent(WpeEvent.Relay("0123456789abcdef msg $tick"))
        assertFalse(rig.engine.isUsable)
        rig.close()
    }

    @Test fun aBundlesPageMessagesComeFromItsLibraryOrigin() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(bundle, WebKitUrls.bundleUrl(bundle.origin, "index.html"))
        rig.engine.onEvent(WpeEvent.Relay("0123456789abcdef msg $ready"))
        assertEquals(EngineEvent.PageMessage("https://app.invalid", ready), rig.events.last())
        rig.close()
    }

    @Test fun anotherDocumentsMessagesAreDropped() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WpeEvent.Relay("fedcba9876543210 msg $ready"))
        assertTrue(rig.events.none { it is EngineEvent.PageMessage }, "${rig.events}")
        rig.close()
    }

    @Test fun nothingIsDeliveredOnceReleased() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.release()
        rig.engine.postMessage("{}", demo.origin.origin)
        assertTrue(rig.view.delivered.isEmpty())
        rig.close()
    }
}
