package dev.supermux.webview.engine.windows

import dev.supermux.webview.DialogResult
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebSettings
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.pageSetup
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class WindowsWebEngineRestoreTest {
    private val site = WebContent.Url("https://a.example/")

    @AfterTest fun quiet() = WinPages.resetForTests()

    private suspend fun WinRig.saved(): ByteArray? {
        var state: ByteArray? = null
        val job = scope.launch { state = engine.saveState() }
        page.evaluated.lastOrNull()?.let { (request, script, _) ->
            if (script.startsWith("[scroll")) engine.onEvent(WinEvent.Result(request, true, "{\"result\":{\"type\":\"object\",\"value\":[0,120]}}"))
        }
        job.join()
        return state
    }

    @Test fun inMemoryHtmlSavesNothing() = runTest {
        val rig = WinRig(this)
        rig.warm()
        val html = WebContent.Html("<p>hi</p>", WebOrigin("demo"))
        rig.show(html, html.origin.url)
        assertNull(rig.saved())
        rig.close()
    }

    @Test fun aFailedLoadSavesNothingAndARetryLoadsTheContent() = runTest {
        // L3: the floor or an error page is never saved, and a retry never reloads what failed.
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = emptySet()))
        rig.engine.load(site)
        val id = rig.starts(site.url)
        rig.engine.onEvent(WinEvent.Completed(rig.page.epoch, id, false, 13, 0, site.url))
        assertNull(rig.saved())
        rig.page.calls.clear()
        rig.engine.reload()
        assertEquals(site.url, rig.page.navigations.last().second)
        assertTrue(rig.page.calls.none { it.startsWith("go ") }, "${rig.page.calls}")
        rig.close()
    }

    @Test fun aRestoreGoesBackToThePageAndItsScrollAsTheEnginesOwn() = runTest {
        val first = WinRig(this, WebProfile.Default)
        first.warm()
        first.show(site, site.url)
        // The page went on to page 2 itself.
        val next = first.starts("https://a.example/page2", WinGlue.NAV_MAIN_FRAME or WinGlue.NAV_USER or ((WinGlue.KIND_NEW_DOCUMENT + 1) shl 4))
        first.engine.onEvent(WinEvent.Committed(first.page.epoch, next, false, "https://a.example/page2", 0))
        val state = assertNotNull(first.saved())
        val second = WinRig(this, WebProfile.Default)
        second.warm()
        second.engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = emptySet()))
        assertFalse(second.engine.restoreState(state, WebContent.Url("https://other.example/")), "another content's state")
        assertTrue(second.engine.restoreState(state, site))
        assertEquals("https://a.example/page2", second.page.navigations.last().second)
        val asked = second.client.asked.size
        val id = second.starts("https://a.example/page2")
        assertEquals(asked, second.client.asked.size, "a restore is never the client's to decide")
        second.engine.onEvent(WinEvent.Committed(second.page.epoch, id, false, "https://a.example/page2", 0))
        second.engine.onEvent(WinEvent.Completed(second.page.epoch, id, true, 0, 200, "https://a.example/page2"))
        assertEquals(Triple(0L, "scrollTo(0.0, 120.0)", false), second.page.evaluated.last(), "scrolled, with no user gesture")
        assertTrue(first.page.executed.isEmpty() && second.page.executed.isEmpty(), "ExecuteScript is never used")
        first.close()
        second.close()
    }

    @Test fun theFloorIsNeverRestored() = runTest {
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        assertFalse(rig.engine.restoreState(Wv2SavedState.encode(site.key, Wv2Navigation.FLOOR_URL, 0.0 to 0.0), site))
        rig.close()
    }

    @Test fun aLoadThatOnlyChangesTheFragmentKeepsItsDocumentAndLiftsTheCover() = runTest {
        // V5 / L21: whether or not WebView2 raises NavigationStarting for it, the document stays, painted, and signing.
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/doc#a"), "https://a.example/doc#a")
        rig.paints()
        rig.drawnInPlace()
        rig.events.clear()
        val secret = rig.page.secret
        rig.engine.load(WebContent.Url("https://a.example/doc#b"))
        assertEquals(secret, rig.page.secret, "the same secret: the document still signs")
        assertTrue(EngineEvent.UrlChanged("https://a.example/doc#b") in rig.events)
        rig.drawnInPlace()
        assertTrue(EngineEvent.FirstPaint in rig.events, "${rig.events}")
        rig.engine.onEvent(WinEvent.Message(rig.page.epoch, "0123456789abcdef msg {\"t\":\"event\",\"n\":\"x\"}"))
        assertTrue(rig.events.last() is EngineEvent.PageMessage, "its words still heard")
        rig.close()
    }

    @Test fun aFragmentOnlyLoadsDocumentIsStillHeard() = runTest {
        // The glue stamps a document's own events (its title, history, dialogs) with the epoch it committed in; a
        // fragment-only load gives that same document a new epoch, and what it says still counts.
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/doc#a"), "https://a.example/doc#a")
        val committedIn = rig.page.epoch
        rig.paints()
        rig.drawnInPlace()
        rig.engine.load(WebContent.Url("https://a.example/doc#b"))
        rig.events.clear()
        rig.engine.onEvent(WinEvent.Title(committedIn, "still here"))
        assertTrue(EngineEvent.TitleChanged("still here") in rig.events, "${rig.events}")
        rig.client.dialog = DialogResult.Confirmed
        rig.engine.onEvent(WinEvent.Dialog(committedIn, 7, 1, "sure?", null, "https://a.example/doc#b"))
        assertEquals(1, rig.page.answers.getValue(7).first, "the client's answer, not an earlier document's default")
        // A document before it still says nothing.
        rig.engine.onEvent(WinEvent.Title(committedIn - 1, "an earlier document"))
        assertTrue(EngineEvent.TitleChanged("an earlier document") !in rig.events)
        rig.close()
    }
}
