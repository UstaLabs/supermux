package dev.supermux.webview.sample.checks

import dev.supermux.webview.WebCookie
import dev.supermux.webview.FindResult
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.supermux.webview.Activation
import dev.supermux.webview.DarkMode
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebSettings
import dev.supermux.webview.WebSizing
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.WebViewState
import dev.supermux.webview.runtime.ProvideWebViewOverlays
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.testing.MacEngineTesting
import dev.supermux.webview.webViewOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * The macOS engine's own checks (D1, D7, M2–M4): what a page under the canvas does with input, and what the layout
 * test found AWT would otherwise swallow. Each drives a real `WebView` with input posted into the app's own queue
 * (`MacEngineTesting`), so it goes through AppKit's routing as a person's would.
 */
object MacChecks {
    /** A page with a text field at CSS (8, 8)–(158, 48), counting presses, telling the host its state. */
    private val INPUT_PAGE = WebContent.Html(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>input</title>
<style>html,body{margin:0;height:100%;background:#e8f0fe;font:16px system-ui}
#field{position:absolute;left:8px;top:8px;width:150px;height:40px;box-sizing:border-box;font:16px system-ui}</style></head>
<body><input id="field" autocomplete="off" spellcheck="false"><script>
var presses = 0;
document.addEventListener('mousedown', function () { presses++; });
hostBridge.handle('state', function () {
  var f = document.getElementById('field');
  return { presses: presses, value: f.value, focused: document.activeElement === f };
});
</script></body></html>""",
    )

    /** What a check's scene shows, and where its parts are in the window (px). */
    private class Stage(withEmbed: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(RuntimeConfig(maxLive = 2, maxIdle = 0, snapshotCacheBytes = 16L shl 20, scope = scope))
        val state = WebViewState(INPUT_PAGE)
        val embedState = if (withEmbed) WebViewState(WebContent.Html("<!doctype html><title>embed</title><p>An embed</p>")) else null
        var page: Rect? = null
        var overlay: Rect? = null
        var field: Rect? = null
        var embed: Rect? = null
        var overlayPresses by mutableIntStateOf(0)
        var menu by mutableStateOf(false)
        var text by mutableStateOf("")
    }

    @Composable
    private fun Scene(stage: Stage) {
        ProvideWebViewRuntime(stage.runtime) {
            ProvideWebViewOverlays {
                Column(Modifier.fillMaxSize().background(Color(0xFF202124)).padding(16.dp).verticalScroll(rememberScrollState())) {
                    Box(Modifier.size(360.dp, 300.dp)) {
                        WebView(
                            stage.state,
                            Modifier.fillMaxSize().onGloballyPositioned { stage.page = it.boundsInWindow() },
                            settings = WebSettings(darkMode = DarkMode.Light),
                            activation = Activation.Eager,
                        )
                        // A marked overlay over the page's top-right corner: its presses are Compose's (D1).
                        val shape = RoundedCornerShape(8.dp)
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(120.dp, 80.dp)
                                .webViewOverlay(shape)
                                .background(Color(0xFF4F7CFF), shape)
                                .clickable { stage.overlayPresses++ }
                                .onGloballyPositioned { stage.overlay = it.boundsInWindow() },
                        )
                        DropdownMenu(
                            expanded = stage.menu,
                            onDismissRequest = { stage.menu = false },
                            modifier = Modifier.webViewOverlay(MenuDefaults.shape),
                        ) {
                            DropdownMenuItem(text = { Text("Copy link") }, onClick = { stage.menu = false })
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    BasicTextField(
                        stage.text,
                        { stage.text = it },
                        Modifier.size(360.dp, 40.dp).background(Color.White).onGloballyPositioned { stage.field = it.boundsInWindow() },
                    )
                    val embedState = stage.embedState
                    if (embedState != null) {
                        Spacer(Modifier.height(16.dp))
                        WebView(
                            embedState,
                            Modifier.width(360.dp).onGloballyPositioned { stage.embed = it.boundsInWindow() },
                            settings = WebSettings(darkMode = DarkMode.Light),
                            activation = Activation.Eager,
                            sizing = WebSizing.WrapContentHeight(max = 200.dp),
                        )
                    }
                }
            }
        }
    }

    /** Runs [body] with [stage] composed and its page live and announced; tears it all down after. */
    private suspend fun CheckScope.withPage(stage: Stage, body: suspend (MacCheckPlatform) -> Unit) {
        val mac = platform as MacCheckPlatform
        compose { Scene(stage) }
        try {
            waitFor("the page live and announced") { stage.state.phase == WebViewPhase.Live && stage.state.bridge.isReady }
            // The cut-outs follow the view's bounds a frame later; the settle shows the page two ticks after.
            delay(500)
            activate(mac)
            body(mac)
        } finally {
            compose(null)
            stage.runtime.clear()
            stage.runtime.onMemoryPressure()
            delay(300)
            stage.scope.cancel()
        }
    }

    /** The app active and its window key, or a person's click asked for (the script relays NEEDS-USER-CLICK). */
    private suspend fun activate(mac: MacCheckPlatform) {
        if (MacEngineTesting.activate(mac.window)) return
        println("NEEDS-USER-CLICK the app isn't active: click once on its window")
        val active = withTimeoutOrNull(90_000) {
            while (!MacEngineTesting.activate(mac.window)) delay(500)
            true
        }
        if (active != true) throw AssertionError("the app never became active: input can't be checked")
    }

    private suspend fun pageState(stage: Stage): JsonObject = stage.state.bridge.call("state") as JsonObject

    /** A press at CSS point ([x], [y]) of [area] (window px, at [density] px a CSS px). */
    private fun pressAt(mac: MacCheckPlatform, area: Rect, x: Float, y: Float) =
        MacEngineTesting.click(mac.window, area.left + x * mac.density, area.top + y * mac.density)

    private fun center(rect: Rect) = rect.center

    private suspend fun waitFor(what: String, millis: Long = 20_000, condition: suspend () -> Boolean) {
        val met = withTimeoutOrNull(millis) {
            while (!condition()) delay(20)
            true
        }
        if (met != true) throw AssertionError("no $what within $millis ms")
    }

    /** A page whose title says what it sees: its device pixel ratio (WebKit's page zoom scales it), refreshed on resize. */
    private val TEXT_PAGE = WebContent.Html(
        """<!doctype html><html><head><title>text-page</title></head><body>
<p>Find the password in this paragraph.</p><script>
function report() { document.title = 'dpr=' + window.devicePixelRatio.toFixed(2); }
window.addEventListener('resize', report);
setTimeout(report, 50);
</script></body></html>""",
    )

    /** A local page that puts the Cookie header its request carried, and what its script can read, in its title. */
    private fun cookieEcho(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val sent = exchange.requestHeaders.getFirst("Cookie").orEmpty().replace(Regex("[^A-Za-z0-9=;_ -]"), "")
            val body = "<!doctype html><title>cookies</title><script>document.title = 'req=$sent | js=' + document.cookie</script>"
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    val all: List<Check> = listOf(
        Check("mac-find-says-found-or-not") {
            val stage = Stage()
            withPage(stage) { _ ->
                stage.state.load(TEXT_PAGE)
                waitFor("the text page") { stage.state.title?.startsWith("dpr=") == true }
                assertEquals(FindResult(found = true), stage.state.navigator.find("password"), "a word on the page")
                assertEquals(FindResult(found = false), stage.state.navigator.find("absent-word"), "a word that isn't")
                stage.state.navigator.clearFind()
            }
        },
        Check("mac-zoom-scales-the-page") {
            val stage = Stage()
            withPage(stage) { _ ->
                stage.state.load(TEXT_PAGE)
                waitFor("the text page") { stage.state.title?.startsWith("dpr=") == true }
                val before = stage.state.title!!.removePrefix("dpr=").toDouble()
                assertTrue(stage.state.navigator.setZoom(2f), "the engine zooms")
                waitFor("the page at 200 %") { (stage.state.title?.removePrefix("dpr=")?.toDoubleOrNull() ?: 0.0) >= before * 1.9 }
                stage.state.navigator.setZoom(1f)
                waitFor("the page back at 100 %") { stage.state.title?.removePrefix("dpr=")?.toDoubleOrNull() == before }
            }
        },
        Check("mac-a-cookie-set-before-the-page-reaches-its-request-and-an-httponly-one-stays-out-of-its-script") {
            val server = cookieEcho()
            try {
                val stage = Stage()
                withPage(stage) { _ ->
                    val url = "http://127.0.0.1:${server.address.port}/"
                    assertTrue(stage.state.navigator.setCookie(WebCookie(name = "probe", value = "1", url = url, secure = false, httpOnly = false)))
                    assertTrue(stage.state.navigator.setCookie(WebCookie(name = "hidden", value = "1", url = url, secure = false, httpOnly = true)))
                    stage.state.navigator.load(url)
                    waitFor("the echo page") { stage.state.title?.startsWith("req=") == true }
                    val title = stage.state.title!!
                    val (sent, script) = title.removePrefix("req=").split(" | js=")
                    assertTrue("probe=1" in sent && "hidden=1" in sent, "the request carried both: $title")
                    assertTrue("probe=1" in script && "hidden" !in script, "the script sees only the plain one: $title")
                }
            } finally {
                server.stop(0)
            }
        },
        Check("mac-the-page-takes-its-presses-and-a-marked-overlay-its-own") {
            val stage = Stage()
            withPage(stage) { mac ->
                val page = assertNotNull(stage.page)
                val overlay = assertNotNull(stage.overlay)
                assertEquals("compose", MacEngineTesting.routes(mac.window, center(overlay).x, center(overlay).y, wheel = false), "the overlay's center")
                assertEquals("page", MacEngineTesting.routes(mac.window, page.left + 100 * mac.density, page.top + 200 * mac.density, wheel = false), "the page")
                pressAt(mac, page, 100f, 200f)
                waitFor("the page's press") { pageState(stage)["presses"]!!.jsonPrimitive.int == 1 }
                assertEquals(0, stage.overlayPresses, "the page's press is the page's alone")
                MacEngineTesting.click(mac.window, center(overlay).x, center(overlay).y)
                waitFor("the overlay's press") { stage.overlayPresses == 1 }
                assertEquals(1, pageState(stage)["presses"]!!.jsonPrimitive.int, "the overlay's press never reaches the page")
            }
        },
        Check("mac-a-press-on-the-page-closes-an-open-menu") {
            val stage = Stage()
            withPage(stage) { mac ->
                val page = assertNotNull(stage.page)
                stage.menu = true
                delay(500)
                pressAt(mac, page, 100f, 250f)
                waitFor("the menu closed by a press on the page", millis = 3_000) { !stage.menu }
                waitFor("the press reached the page too") { pageState(stage)["presses"]!!.jsonPrimitive.int >= 1 }
            }
        },
        Check("mac-command-keys-reach-the-page") {
            val stage = Stage()
            withPage(stage) { mac ->
                val page = assertNotNull(stage.page)
                pressAt(mac, page, 83f, 28f)
                waitFor("the field focused") { pageState(stage)["focused"]!!.jsonPrimitive.boolean }
                MacEngineTesting.type(mac.window, "abcdef")
                waitFor("typing in the page") { pageState(stage)["value"]!!.jsonPrimitive.content == "abcdef" }
                MacEngineTesting.command(mac.window, 'z')
                waitFor("⌘Z undoing the typing (AWT keeps it without the monitor, M2)") { pageState(stage)["value"]!!.jsonPrimitive.content != "abcdef" }
                MacEngineTesting.type(mac.window, "xyz")
                // As before ⌘Z: the edit action reaches the page at once, while keys typed before it may still be queued
                // in WebKit, one at a time, for its WebContent process; ⌘A then selected "x" alone ("yzq"). A person's
                // typing never outruns that queue; keys posted back to back do.
                waitFor("typing in the page again") { pageState(stage)["value"]!!.jsonPrimitive.content.endsWith("xyz") }
                MacEngineTesting.command(mac.window, 'a')
                MacEngineTesting.type(mac.window, "q")
                waitFor("⌘A selecting all, so q replaces it") { pageState(stage)["value"]!!.jsonPrimitive.content == "q" }
                assertEquals("", stage.text, "no key reached Compose's field")
            }
        },
        Check("mac-focus-follows-the-press") {
            val stage = Stage()
            withPage(stage) { mac ->
                val page = assertNotNull(stage.page)
                val field = assertNotNull(stage.field)
                pressAt(mac, page, 83f, 28f)
                waitFor("the page as first responder") { MacEngineTesting.firstResponder(mac.window).startsWith("page") }
                MacEngineTesting.click(mac.window, center(field).x, center(field).y)
                waitFor("Compose as first responder") { MacEngineTesting.firstResponder(mac.window).startsWith("compose") }
                MacEngineTesting.type(mac.window, "zz")
                waitFor("typing in Compose's field") { stage.text == "zz" }
                assertEquals("", pageState(stage)["value"]!!.jsonPrimitive.content, "nothing typed reached the page")
            }
        },
        Check("mac-voiceover-reaches-the-page") {
            val stage = Stage()
            withPage(stage) { mac ->
                // AWT's view exposes only Java's tree: the bridge adds the page (M3). Pinned here against JDK updates.
                assertTrue(MacEngineTesting.accessibilityReachesPage(mac.window), "VoiceOver reaches the page's web view")
            }
        },
        Check("mac-memory-pressure-halves-the-budget") {
            val stage = Stage()
            withPage(stage) { _ ->
                assertEquals(2, stage.runtime.pool.budget)
                MacEngineTesting.simulateMemoryPressure()
                waitFor("half the budget", millis = 3_000) { stage.runtime.pool.budget == 1 }
            }
        },
        Check("mac-an-embed-leaves-the-wheel-to-its-list-and-a-pane-keeps-it") {
            val stage = Stage(withEmbed = true)
            withPage(stage) { mac ->
                waitFor("the embed live") { stage.embedState?.phase == WebViewPhase.Live }
                delay(500)
                val page = assertNotNull(stage.page)
                val embed = assertNotNull(stage.embed)
                val paneAt = page.left + 100 * mac.density to page.top + 200 * mac.density
                val embedAt = center(embed)
                assertEquals("page", MacEngineTesting.routes(mac.window, paneAt.first, paneAt.second, wheel = true), "a pane scrolls itself")
                assertEquals("compose", MacEngineTesting.routes(mac.window, embedAt.x, embedAt.y, wheel = true), "an embed's wheel scrolls its list (D7)")
                assertEquals("page", MacEngineTesting.routes(mac.window, embedAt.x, embedAt.y, wheel = false), "an embed still takes its presses")
            }
        },
    )
}
