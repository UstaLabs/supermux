package dev.supermux.webview.engine.web

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.PageSetup
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

private fun iframesIn(cut: HTMLElement): Int = js("cut.querySelectorAll('iframe').length")

/** A page of another site, as far as the browser is concerned: the test server's other host name. */
private fun crossSiteUrl(): String =
    js("location.protocol + '//' + (location.hostname === 'localhost' ? '127.0.0.1' : 'localhost') + ':' + location.port + '/webview-budget-page'")

private val setup = PageSetup(
    javaScriptEnabled = true,
    inspectable = false,
    dark = false,
    transparentBackground = false,
    userAgentSuffix = null,
    bridgeOrigins = emptySet(),
)

/**
 * The feasibility amendment 4: srcdoc demos share a renderer process, which the memory measurement covered; a page of
 * another site runs in a process of its own, so only [WebEngineDecisions.MAX_LIVE_URL] engines show one at a time.
 */
class UrlPageBudgetTest {
    private fun engines(count: Int) = List(count) { IframeWebEngine(EngineConfig(WebProfile.Default)) }

    /** As the runtime shows a view on screen: the setup, the content, then that the view is on screen. */
    private fun IframeWebEngine.show(content: WebContent) {
        configure(setup)
        load(content)
        setVisible(true)
    }

    private fun IframeWebEngine.hasPage(): Boolean = iframesIn(frame.cut) > 0

    @Test fun onlyTheBudgetOfCrossSitePagesIsLiveAndTheNextWaitsForASlot() = runTest(timeout = 1.minutes) {
        val budget = WebEngineDecisions.MAX_LIVE_URL
        val all = engines(budget + 1)
        all.forEach { it.show(WebContent.Url(crossSiteUrl())) }
        assertEquals(List(budget) { true } + false, all.map { it.hasPage() }, "the last one waits")
        all.first().release()
        assertEquals(true, all.last().hasPage(), "and gets the slot that freed")
        all.forEach { it.release() }
    }

    @Test fun aHiddenHolderGivesItsSlotToAVisiblePageAndGetsItBackWhenShown() = runTest(timeout = 1.minutes) {
        val budget = WebEngineDecisions.MAX_LIVE_URL
        val all = engines(budget + 1)
        val (hidden, newcomer) = all.first() to all.last()
        all.dropLast(1).forEach { it.show(WebContent.Url(crossSiteUrl())) }
        hidden.setVisible(false)
        newcomer.show(WebContent.Url(crossSiteUrl()))
        assertEquals(true, newcomer.hasPage(), "a visible page takes the slot of one off screen")
        assertEquals(false, hidden.hasPage(), "whose page goes, freeing its process")
        newcomer.setVisible(false)
        hidden.setVisible(true)
        assertEquals(true, hidden.hasPage(), "shown again, it loads afresh in the slot the hidden newcomer gave up")
        assertEquals(false, newcomer.hasPage())
        all.forEach { it.release() }
    }

    @Test fun demosDontCountAgainstIt() = runTest(timeout = 1.minutes) {
        val all = engines(WebEngineDecisions.MAX_LIVE_URL + 2)
        all.forEach { it.show(WebContent.Html("<!doctype html><p>demo</p>")) }
        assertEquals(all.map { true }, all.map { it.hasPage() })
        all.forEach { it.release() }
    }

    @Test fun aSlotFreesWhenItsEngineLoadsADemoOrResets() = runTest(timeout = 1.minutes) {
        val budget = WebEngineDecisions.MAX_LIVE_URL
        val all = engines(budget + 2)
        all.forEach { it.show(WebContent.Url(crossSiteUrl())) }
        all[0].show(WebContent.Html("<!doctype html><p>demo</p>"))
        assertEquals(true, all[budget].hasPage(), "the first in line got the slot a demo freed")
        all[1].reset()
        assertEquals(true, all[budget + 1].hasPage(), "the next got the one a reset freed")
        all.forEach { it.release() }
    }
}
