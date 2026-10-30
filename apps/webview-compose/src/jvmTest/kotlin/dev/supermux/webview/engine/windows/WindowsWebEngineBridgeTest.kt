package dev.supermux.webview.engine.windows

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebSettings
import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.pageSetup
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WindowsWebEngineBridgeTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))
    private val ready = WebBridge.READY_PREFIX + ",\"d\":{\"nonce\":\"n1\"},\"from\":\"n1\"}"

    @AfterTest fun quiet() = WinPages.resetForTests()

    @Test fun theDocumentsMessagesAreHeardWithItsOrigin() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "0123456789abcdef msg $ready"))
        assertEquals(EngineEvent.PageMessage(demo.origin.origin, ready), rig.events.last())
        rig.close()
    }

    @Test fun anotherDocumentsTokenIsNotHeard() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "ffffffffffffffff msg $ready"))
        assertTrue(rig.events.none { it is EngineEvent.PageMessage })
        rig.close()
    }

    @Test fun aDocumentsFirstWordsBeforeItsCommitComeAfterTheCommit() = runTest {
        // V6: WebView2 may tell the document's start and $ready before its ContentLoading; the commit is heard first.
        val rig = WinRig(this)
        rig.warm()
        rig.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = setOf(demo.origin.origin)))
        rig.engine.load(demo)
        val id = rig.starts(demo.origin.url)
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "0123456789abcdef start"))
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "0123456789abcdef msg $ready"))
        assertTrue(rig.events.none { it is EngineEvent.PageMessage }, "held")
        rig.engine.onEvent(WinEvent.Committed(rig.page.epoch, id, false, demo.origin.url, 0))
        val url = rig.events.indexOfFirst { it is EngineEvent.UrlChanged }
        val message = rig.events.indexOfFirst { it is EngineEvent.PageMessage }
        assertTrue(url in 0 until message, "${rig.events}")
        rig.close()
    }

    @Test fun aBackNavigationsDocumentThatSpeaksBeforeItsCommitIsHeardAfterIt() = runTest {
        // V6 for the engine's own history navigations too: the new document's first words wait for its commit, which
        // binds its token after it, so its bridge lives (a start heard first would be unbound by the commit).
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onEvent(WinEvent.History(rig.page.epoch, true, false))
        rig.engine.goBack()
        val back = rig.starts(demo.origin.url, WinGlue.NAV_MAIN_FRAME or ((WinGlue.KIND_BACK_OR_FORWARD + 1) shl 4))
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "fedcba9876543210 start"))
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "fedcba9876543210 msg $ready"))
        assertTrue(rig.events.none { it is EngineEvent.PageMessage }, "held until the commit: ${rig.events}")
        rig.events.clear()
        rig.engine.onEvent(WinEvent.Committed(rig.page.epoch, back, false, demo.origin.url, 0))
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "fedcba9876543210 msg {\"t\":\"event\",\"n\":\"x\"}"))
        val url = rig.events.indexOfFirst { it is EngineEvent.UrlChanged }
        assertTrue(url >= 0 && rig.events.drop(url).count { it is EngineEvent.PageMessage } == 2, "${rig.events}")
        rig.close()
    }

    @Test fun deliveryCallsTheReceiverAndWaitsForNothing() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.postMessage("{\"t\":\"event\"}", demo.origin.origin)
        assertEquals(0L to Wv2Scripts("__wv_rtest", "__wv_ctest").deliveryCall("{\"t\":\"event\"}", demo.origin.origin), rig.page.executed.last())
        rig.close()
    }

    @Test fun javaScriptOffMeansNoShimInTheScript() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.engine.configure(WebSettings.Default.copy(javaScriptEnabled = false).pageSetup(dark = false, bridgeOrigins = setOf(demo.origin.origin)))
        rig.engine.load(demo)
        assertFalse("hostBridge" in rig.page.scripts.last().third)
        rig.close()
    }

    @Test fun aMidLeaseOriginChangeKeepsTheDocumentsSecret() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        val (epoch, secret, _) = rig.page.scripts.last()
        rig.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = setOf(demo.origin.origin, "https://b.example")))
        val (epoch2, secret2, script2) = rig.page.scripts.last()
        assertEquals(epoch to secret, epoch2 to secret2, "the shown document keeps signing; later documents get the new origins")
        assertTrue("https://b.example" in script2)
        rig.close()
    }

    @Test fun aPaintHandsOff() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.paints()
        rig.drawnInPlace()
        assertTrue(EngineEvent.FirstPaint in rig.events)
        rig.close()
    }

    @Test fun aParkedPageThatFloodsItsBridgeCrashes() = runTest {
        // L7: past the parked bound, the engine is crashed: discarded, never adopted.
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.unsubscribe()
        repeat(1_100) { rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "0123456789abcdef msg {\"t\":\"event\",\"n\":\"tick\",\"d\":$it}")) }
        assertFalse(rig.engine.isUsable)
        rig.close()
    }
}
