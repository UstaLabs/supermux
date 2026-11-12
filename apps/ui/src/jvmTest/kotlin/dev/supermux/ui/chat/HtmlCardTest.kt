package dev.supermux.ui.chat

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.FromCardDto
import dev.supermux.proto.HtmlCardDto
import dev.supermux.proto.LogEntry
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.browser.AppWebViews
import dev.supermux.ui.browser.LocalAppWebViews
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.WebEngineSpi
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #104 car A: an agent's html card in the chat, its page API, and the user's message a card sent. */
@OptIn(ExperimentalTestApi::class)
class HtmlCardTest {
    private val dark = CardTheme(
        dark = true,
        vars = CARD_THEME_VARIABLES.map { it to "#112233" },
    )

    // --- The document the app builds ---

    @Test fun theThemeAndTheApiComeBeforeAnyOfThePage() {
        val page = "<!doctype html><html lang=\"en\"><head><title>t</title><script>supermux.send('x')</script></head><body><p>hi</p></body></html>"
        val doc = cardDocument(page, dark)
        assertTrue(doc.startsWith("<!doctype html>"), "a leading doctype, so the library's shim goes first")
        val api = doc.indexOf("defineProperty(window, 'supermux'")
        assertTrue(api > 0)
        assertTrue(doc.indexOf("--sm-fg:#112233") in 0 until doc.indexOf("<title>"))
        assertTrue(api < doc.indexOf("<script>supermux.send"), "the api is defined before the page's first script")
        assertTrue("color-scheme:dark" in doc)
        assertTrue("data-theme" in doc)
        assertTrue("<p>hi</p>" in doc)
    }

    // iOS: WKWebView rubber-bands even a page that doesn't scroll. The sample stops it in its pages' CSS (orbit.html):
    // overscroll-behavior none on the root. A card that scrolls inside keeps its scroll, without the outer bounce.
    @Test fun aCardsPageDoesNotBounce() {
        val doc = cardDocument("<p>hi</p>", dark)
        val base = doc.substringAfter("<style id=\"sm-base\">").substringBefore("</style>")
        val rule = base.substringAfter("html,body{").substringBefore("}")
        assertTrue("overscroll-behavior:none" in rule, "html and body keep their page from bouncing: $rule")
    }

    @Test fun aFragmentIsWrappedInADocument() {
        val doc = cardDocument("<button onclick=\"supermux.send('B')\">B</button>", dark.copy(dark = false))
        assertTrue(doc.startsWith("<!doctype html><html data-theme=\"light\">"))
        assertTrue(doc.indexOf("defineProperty(window, 'supermux'") < doc.indexOf("<button"))
        assertTrue("color-scheme:light" in doc)
    }

    @Test fun aPageWithoutAHeadGetsThePreludeBeforeItsHtml() {
        val doc = cardDocument("<html><body><script>1</script></body></html>", dark)
        assertTrue(doc.startsWith("<!doctype html>"))
        assertTrue(doc.indexOf("defineProperty(window, 'supermux'") < doc.indexOf("<html>"))
    }

    @Test fun theThemeHasEveryVariableFromTheAppsColours() = runComposeUiTest {
        var theme: CardTheme? = null
        var fgHex: String? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                theme = cardTheme(MaterialTheme.colorScheme, LocalSemantics.current)
                fgHex = cssColor(MaterialTheme.colorScheme.onSurface)
            }
        }
        waitForIdle()
        val names = theme!!.vars.map { it.first }
        for (name in listOf("--sm-fg", "--sm-muted-fg", "--sm-card", "--sm-border", "--sm-muted", "--sm-primary") + (1..5).map { "--sm-chart-$it" }) {
            assertTrue(name in names, name)
        }
        assertEquals(fgHex, theme!!.vars.toMap()["--sm-fg"])
        assertTrue(theme!!.dark)
    }

    // --- supermux.send's answer ---

    @Test fun aSendWithoutAClickOnAButtonIsRefused() = runTest {
        val sent = mutableListOf<String>()
        val answer = cardSendAnswer(JsonObject(mapOf("text" to JsonPrimitive("Use B"))), clicked = false) { sent += it; null }
        assertFalse(answer["ok"]!!.jsonPrimitive.boolean)
        assertEquals("no click on a button in the card", answer["reason"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), sent)
    }

    @Test fun anEmptyOrTooLongTextIsRefused() = runTest {
        val sent = mutableListOf<String>()
        for (params in listOf(JsonObject(mapOf("text" to JsonPrimitive(""))), JsonObject(mapOf("text" to JsonPrimitive("x".repeat(2001)))), JsonNull)) {
            val answer = cardSendAnswer(params, clicked = true) { sent += it; null }
            assertFalse(answer["ok"]!!.jsonPrimitive.boolean, "$params")
            assertTrue(answer["reason"]!!.jsonPrimitive.content.isNotEmpty())
        }
        assertEquals("the text is empty", cardSendAnswer(JsonObject(mapOf("text" to JsonPrimitive(""))), clicked = true) { null }["reason"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), sent)
    }

    @Test fun aClickedSendGoesOut() = runTest {
        val sent = mutableListOf<String>()
        val answer = cardSendAnswer(JsonObject(mapOf("text" to JsonPrimitive("x".repeat(2000)))), clicked = true) { sent += it; null }
        assertEquals(JsonObject(mapOf("ok" to JsonPrimitive(true))), answer)
        assertEquals(listOf("x".repeat(2000)), sent)
    }

    @Test fun theHostsRefusalIsThePagesAnswer() = runTest {
        val answer = cardSendAnswer(JsonObject(mapOf("text" to JsonPrimitive("Use B"))), clicked = true) { "one send a second" }
        assertFalse(answer["ok"]!!.jsonPrimitive.boolean)
        assertEquals("one send a second", answer["reason"]!!.jsonPrimitive.content)
    }

    /** The page API sends through the bridge's click-proven call, which carries the shim's verdict, not a plain call. */
    @Test fun thePagesSendIsACallAfterAClick() {
        assertTrue("bridge.callAfterClick('send', " in CARD_API_SCRIPT, CARD_API_SCRIPT)
        assertFalse("bridge.call('send'" in CARD_API_SCRIPT)
    }

    // --- The card in the chat ---

    private val card = HtmlCardDto("abc", "Sales by region", height = 300, desktopHeight = 220)

    private class Rig(pageGate: CompletableDeferred<Unit>? = null) {
        val engines = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 200f }))
        val sends = mutableListOf<Pair<FromCardDto, String>>()
        val page = "<div class=bar></div>"
        val host = HtmlCardHost(page = { pageGate?.await(); page }, send = { c, t -> sends += c to t; null })
        val calls: List<String> get() = engines.created.flatMap { it.calls }
    }

    private fun ComposeUiTest.show(rig: Rig, widthClass: WindowWidthClass = WindowWidthClass.Expanded, content: @Composable () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20, factory = rig.engines, scope = scope, clock = { mainClock.currentTime }),
        )
        setPlatformContent(widthClass = widthClass) {
            CompositionLocalProvider(LocalAppWebViews provides AppWebViews(runtime), LocalHtmlCards provides rig.host) { content() }
        }
    }

    @OptIn(WebEngineSpi::class)
    @Test fun aCardShowsItsTitleAndLoadsTheAppsDocumentInAnEphemeralProfile() = runComposeUiTest {
        val rig = Rig()
        var theme: CardTheme? = null
        show(rig) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                theme = cardTheme(MaterialTheme.colorScheme, LocalSemantics.current)
                HtmlCardView("o1", card, Modifier.width(600.dp))
            }
        }
        onNodeWithText("Sales by region").assertExists()
        val key = WebContent.Html(cardDocument(rig.page, theme!!)).key
        waitUntil(timeoutMillis = 5_000) { runOnUiThread { rig.calls.any { it == "load:$key" } } }
        val engine = runOnUiThread { rig.engines.created.single { e -> e.calls.any { it == "load:$key" } } }
        assertEquals(WebProfile.Ephemeral, engine.config.profile)
        assertTrue(engine.lastSetup!!.transparentBackground)
        assertTrue(engine.lastSetup!!.dark)
        runOnUiThread { rig.engines.created.map { it.violations.toList() } }.forEach { assertEquals(emptyList(), it) }
    }

    @Test fun beforeThePageArrivesTheCardReservesItsStoredHeight() = runComposeUiTest {
        val rig = Rig(pageGate = CompletableDeferred())
        show(rig, WindowWidthClass.Expanded) { SupermuxTheme(appearance = AppearanceMode.DARK) { HtmlCardView("o1", card, Modifier.width(600.dp)) } }
        onNodeWithTag("html-card-page:o1").assertHeightIsEqualTo(220.dp)
    }

    @Test fun onAPhoneTheCardReservesItsPhoneHeight() = runComposeUiTest {
        val rig = Rig(pageGate = CompletableDeferred())
        show(rig, WindowWidthClass.Compact) { SupermuxTheme(appearance = AppearanceMode.DARK) { HtmlCardView("o1", card, Modifier.width(360.dp)) } }
        onNodeWithTag("html-card-page:o1").assertHeightIsEqualTo(300.dp)
    }

    @Test fun aThemeChangeReachesTheLivePage() = runComposeUiTest {
        val rig = Rig()
        var appearance by mutableStateOf(AppearanceMode.DARK)
        var light: CardTheme? = null
        show(rig) {
            SupermuxTheme(appearance = appearance) {
                if (appearance == AppearanceMode.LIGHT) light = cardTheme(MaterialTheme.colorScheme, LocalSemantics.current)
                HtmlCardView("o1", card, Modifier.width(600.dp))
            }
        }
        waitUntil(timeoutMillis = 5_000) { runOnUiThread { rig.calls.any { it.startsWith("load:html:") } } }
        appearance = AppearanceMode.LIGHT
        waitForIdle()
        val fg = light!!.vars.toMap()["--sm-fg"]!!
        waitUntil(timeoutMillis = 5_000) { runOnUiThread { rig.calls.any { it.startsWith("post:") && "\"n\":\"theme\"" in it && "\"dark\":false" in it && fg in it } } }
    }

    @Test fun anAgentMessageWithACardShowsTheCardInTheTimeline() = runComposeUiTest {
        val rig = Rig()
        val entry = LogEntry(id = "o1", ts = "2026-11-10T12:00:00Z", direction = "outbound", text = "", card = card)
        show(rig) { SupermuxTheme(appearance = AppearanceMode.DARK) { TimelineItemRow(TimelineItem.Msg(entry)) } }
        onNodeWithTag("html-card:o1").assertExists()
        onNodeWithText("Sales by region").assertExists()
    }

    @Test fun theUsersMessageFromACardSaysWhichCard() = runComposeUiTest {
        val entry = LogEntry(id = "i1", ts = "2026-11-10T12:00:00Z", direction = "inbound", text = "Use B", channel = "web", fromCard = FromCardDto("abc", "Sales by region"))
        setPlatformContent { SupermuxTheme(appearance = AppearanceMode.DARK) { TimelineItemRow(TimelineItem.Msg(entry)) } }
        onNodeWithText("Use B").assertExists()
        onNodeWithText("from Sales by region").assertExists()
    }

    /**
     * The card gates its send on the shim's click verdict (handleAfterButtonClick), not on the host's input stamp: the
     * fake engine tells of no input at all, as a view a screen reader activated gets none, and a clicked send still goes.
     */
    @OptIn(WebEngineSpi::class)
    @Test fun aCardSendsOnTheShimsClickVerdictWithNoHostInput() = runComposeUiTest {
        val rig = Rig()
        show(rig) { SupermuxTheme(appearance = AppearanceMode.DARK) { HtmlCardView("o1", card, Modifier.width(600.dp)) } }
        waitUntil(timeoutMillis = 5_000) { runOnUiThread { rig.calls.any { it.startsWith("post:") && "\"n\":\"theme\"" in it } } }
        val engine = runOnUiThread { rig.engines.created.single { e -> e.calls.any { it.startsWith("load:html:") } } }
        fun reply(id: Long): String? = runOnUiThread { engine.calls.firstOrNull { it.startsWith("post:") && "\"t\":\"reply\"" in it && "\"id\":$id," in it } }
        val text = JsonObject(mapOf("text" to JsonPrimitive("Use B")))

        assertTrue(runOnUiThread { engine.callFromPage(1, "send", text, clicked = false) })
        waitUntil(timeoutMillis = 5_000) { reply(1) != null }
        assertTrue("no click on a button in the card" in reply(1)!!, reply(1))

        runOnUiThread { engine.callFromPage(2, "send", text, clicked = null) }
        waitUntil(timeoutMillis = 5_000) { reply(2) != null }
        assertTrue("no click on a button in the card" in reply(2)!!, "a plain call is no click: ${reply(2)}")
        assertEquals(emptyList(), runOnUiThread { rig.sends.toList() })

        runOnUiThread { engine.callFromPage(3, "send", text, clicked = true) }
        waitUntil(timeoutMillis = 5_000) { reply(3) != null }
        assertTrue("\"ok\":true" in reply(3)!!, reply(3))
        assertEquals(listOf(FromCardDto("abc", "Sales by region") to "Use B"), runOnUiThread { rig.sends.toList() })
    }
}
