package dev.supermux.ui.chat

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.proto.HtmlCardDto
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.browser.AppWebViews
import dev.supermux.ui.browser.LocalAppWebViews
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.webview.LocalWebSurfaceHints
import dev.supermux.webview.WebSurfaceHints
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.WebEngineSpi
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A fake engine whose surface records the hints it was last composed with. */
@OptIn(WebEngineSpi::class)
private class HintEngine(val inner: FakeWebEngine) : WebEngine by inner {
    var seen: WebSurfaceHints? = null

    @Composable
    override fun Surface(modifier: Modifier) {
        seen = LocalWebSurfaceHints.current
        inner.Surface(modifier)
    }
}

/**
 * #104 on iOS: a card's height cap (a phone shows a page of up to 560 dp whole, as the webview sample does), and a card
 * whose page takes touches itself (`touch-action: none`, as a three.js canvas) is a pane, given every touch, while
 * other cards stay embeds, whose drags scroll the chat.
 */
@OptIn(ExperimentalTestApi::class, WebEngineSpi::class)
class HtmlCardPaneTest {
    private val card = HtmlCardDto("abc", "Orbit", height = 300)

    private class Rig(pageHeight: Float) {
        val fakes = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { pageHeight }))
        val engines = mutableListOf<HintEngine>()
        val factory = WebEngineFactory { config ->
            when (val made = fakes.create(config)) {
                is WebEngineResult.Created -> WebEngineResult.Created(HintEngine(made.engine as FakeWebEngine).also { engines += it })
                else -> made
            }
        }
        val host = HtmlCardHost(page = { "<div></div>" }, send = { _, _ -> null })
        val loaded: HintEngine? get() = engines.firstOrNull { e -> e.inner.calls.any { it.startsWith("load:") } }
    }

    private fun ComposeUiTest.show(rig: Rig, chat: Dp, width: Dp = 360.dp, widthClass: WindowWidthClass = WindowWidthClass.Compact) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20, factory = rig.factory, scope = scope, clock = { mainClock.currentTime }),
        )
        // Unbounded: the test window (768 px) would clip a card taller than it, which isn't the cap.
        setPlatformContent(widthClass = widthClass) {
            CompositionLocalProvider(
                LocalAppWebViews provides AppWebViews(runtime),
                LocalHtmlCards provides rig.host,
                LocalChatViewportHeight provides chat,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) { HtmlCardView("o1", card, Modifier.width(width).wrapContentHeight(unbounded = true)) }
            }
        }
        waitUntil(timeoutMillis = 5_000) { runOnUiThread { rig.loaded != null } }
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }

    // --- The cap ---

    @Test fun theCapIsTheLargerOf560AndSeventyPercentOfTheChat() {
        assertEquals(560.dp, cardMaxHeight(650.dp))
        assertEquals(560.dp, cardMaxHeight(800.dp))
        assertEquals(840.dp, cardMaxHeight(1200.dp))
    }

    @Test fun onAPhoneA473DpPageShowsWhole() = runComposeUiTest {
        val rig = Rig(pageHeight = 473f)
        show(rig, chat = 650.dp)
        // The view is as tall as its page: nothing scrolls inside.
        onNodeWithTag("html-card-page:o1").assertHeightIsEqualTo(473.dp)
    }

    @Test fun onAPhoneATallerPageIsCappedAt560() = runComposeUiTest {
        val rig = Rig(pageHeight = 3000f)
        show(rig, chat = 650.dp)
        onNodeWithTag("html-card-page:o1").assertHeightIsEqualTo(560.dp)
    }

    @Test fun onATallChatTheCapIsSeventyPercentOfIt() = runComposeUiTest {
        val rig = Rig(pageHeight = 3000f)
        show(rig, chat = 1200.dp, width = 600.dp, widthClass = WindowWidthClass.Expanded)
        onNodeWithTag("html-card-page:o1").assertHeightIsEqualTo(840.dp)
    }

    // --- Pane or embed ---

    @Test fun theCardsScriptLooksForATouchActionThatBlocksPanningAndTellsTheHost() {
        assertTrue("touch-action" in CARD_API_SCRIPT, "the script reads each element's computed touch-action")
        assertTrue("getComputedStyle" in CARD_API_SCRIPT)
        assertTrue("MutationObserver" in CARD_API_SCRIPT, "and again as the page adds elements")
        assertTrue("DOMContentLoaded" in CARD_API_SCRIPT)
        assertTrue("bridge.post('$CARD_TOUCH_EVENT', { pane: true })" in CARD_API_SCRIPT, "it tells the host it is a pane")
        assertTrue("bridge.on('$CARD_TOUCH_EVENT'" in CARD_API_SCRIPT, "and again when the host asks")
        // The document a card loads carries it.
        val doc = cardDocument("<canvas></canvas>", CardTheme(dark = true, vars = emptyList()))
        assertTrue("bridge.post('$CARD_TOUCH_EVENT'" in doc)
    }

    @Test fun onlyAPaneTrueEventMakesAPane() {
        assertTrue(cardWantsPane(JsonObject(mapOf("pane" to JsonPrimitive(true)))))
        assertFalse(cardWantsPane(JsonObject(mapOf("pane" to JsonPrimitive(false)))))
        assertFalse(cardWantsPane(JsonObject(mapOf("pane" to JsonPrimitive("true")))))
        assertFalse(cardWantsPane(JsonNull))
    }

    @Test fun aCardIsAnEmbedUntilItsPageAsksForTouches() = runComposeUiTest {
        val rig = Rig(pageHeight = 300f)
        show(rig, chat = 650.dp)
        val engine = rig.loaded!!
        assertEquals(WebSurfaceHints(inScrollingList = true), runOnUiThread { engine.seen }, "an embed: a drag on it scrolls the chat")
        // The host asks the page whether it takes touches, as a view adopting a page it didn't load must.
        waitUntil(timeoutMillis = 5_000) { runOnUiThread { engine.inner.calls.any { it.startsWith("post:") && "\"n\":\"$CARD_TOUCH_EVENT\"" in it } } }

        assertTrue(runOnUiThread { engine.inner.postFromPage(CARD_TOUCH_EVENT, JsonObject(mapOf("pane" to JsonPrimitive(true)))) })
        // The event reaches the host through the bridge's own handshake, a few turns later.
        waitUntil(timeoutMillis = 5_000) { runOnUiThread { engine.seen } == WebSurfaceHints(inScrollingList = false) }
        assertEquals(WebSurfaceHints(inScrollingList = false), runOnUiThread { engine.seen }, "a pane: the page takes every touch")
    }

    @Test fun anotherEventLeavesItAnEmbed() = runComposeUiTest {
        val rig = Rig(pageHeight = 300f)
        show(rig, chat = 650.dp)
        val engine = rig.loaded!!
        runOnUiThread { engine.inner.postFromPage(CARD_TOUCH_EVENT, JsonObject(mapOf("pane" to JsonPrimitive(false)))) }
        runOnUiThread { engine.inner.postFromPage("other", JsonObject(mapOf("pane" to JsonPrimitive(true)))) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(WebSurfaceHints(inScrollingList = true), runOnUiThread { engine.seen })
    }
}
