package dev.supermux.webview.sample.checks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The independent review's findings, each a check that fails without its fix (docs/superpowers/plans/
 * 2026-10-30-webview-macos-review-fixes.md). Each judges where content is: a red marker the page draws at CSS (16, 16),
 * 24 × 24, found in the window as the window server composited it, and which element a press reached.
 */
object ReviewChecks {
    private const val BLUE = "#1e3a8a"
    private const val AMBER = "#f59e0b"
    private const val MARKER_CSS = 16f
    private const val MARKER_SIZE_CSS = 24f

    /**
     * A page of [background], with the red marker unless not [marker]; it counts its presses and tells its viewport's
     * size. [script] runs after.
     */
    private fun markerPage(name: String, background: String = BLUE, marker: Boolean = true, fill: Boolean = true, script: String = "") =
        WebContent.Html(
            """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>$name</title>
<style>html,body{margin:0;background:$background${if (fill) ";height:100%" else ""}}
#marker{position:absolute;left:16px;top:16px;width:24px;height:24px;background:#ff0000}</style></head>
<body>${if (marker) "<div id=\"marker\"></div>" else ""}<script>
var presses = 0;
document.addEventListener('mousedown', function () { presses++; });
hostBridge.handle('state', function () { return { presses: presses, width: innerWidth, height: innerHeight }; });
$script
</script></body></html>""",
        )

    private val light = WebSettings(darkMode = DarkMode.Light)

    /** One check's runtime and the scene it shows; [close] lets them all go. */
    private class Stage {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(config = RuntimeConfig(maxLive = 3, maxIdle = 0, snapshotCacheBytes = 16L shl 20), scope = scope)

        fun close() {
            runtime.clear()
            runtime.onMemoryPressure()
            scope.cancel()
        }
    }

    /** Shows [scene] under the runtime and a window registry, waits for [states] live and announced, runs [body]. */
    private suspend fun CheckScope.within(stage: Stage, states: List<WebViewState>, scene: @Composable () -> Unit, body: suspend (MacCheckPlatform) -> Unit) {
        val mac = platform as MacCheckPlatform
        compose { ProvideWebViewRuntime(stage.runtime) { ProvideWebViewOverlays { scene() } } }
        try {
            for (state in states) waitFor("${state.content.key} live and announced") { state.phase == WebViewPhase.Live && state.bridge.isReady }
            // The backing again (a resized or zoomed window loses it); the cut-outs follow the view's bounds a frame
            // later; the settle shows the page two ticks after.
            MacEngineTesting.paintBacking(mac.window, MacCheckPlatform.BACKING)
            delay(600)
            activate(mac)
            body(mac)
        } finally {
            compose(null)
            delay(300)
            stage.close()
        }
    }

    private suspend fun activate(mac: MacCheckPlatform) {
        if (MacEngineTesting.activate(mac.window)) return
        println("NEEDS-USER-CLICK the app isn't active: click once on its window")
        val active = withTimeoutOrNull(90_000) {
            while (!MacEngineTesting.activate(mac.window)) delay(500)
            true
        }
        if (active != true) throw AssertionError("the app never became active: input can't be checked")
    }

    private suspend fun waitFor(what: String, millis: Long = 20_000, condition: suspend () -> Boolean) {
        val met = withTimeoutOrNull(millis) {
            while (!condition()) delay(20)
            true
        }
        if (met != true) throw AssertionError("no $what within $millis ms")
    }

    private suspend fun presses(state: WebViewState): Int = (state.bridge.call("state") as JsonObject)["presses"]!!.jsonPrimitive.int

    private suspend fun viewport(state: WebViewState): Pair<Int, Int> {
        val answer = state.bridge.call("state") as JsonObject
        return answer["width"]!!.jsonPrimitive.int to answer["height"]!!.jsonPrimitive.int
    }

    /** Where the marker should be for a page in [slot]: CSS px are points, [MacCheckPlatform.density] px each. */
    private fun expectedMarker(mac: MacCheckPlatform, slot: Rect): Rect {
        val s = mac.density
        return Rect(slot.left + MARKER_CSS * s, slot.top + MARKER_CSS * s, slot.left + (MARKER_CSS + MARKER_SIZE_CSS) * s, slot.top + (MARKER_CSS + MARKER_SIZE_CSS) * s)
    }

    /** The red marker's box in [pixels], a copy of [area] (window px, the copy's px), or null if none shows. */
    private fun markerIn(pixels: Pixels, area: Rect): Rect? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val i = (y * pixels.width + x) * 4
            val r = pixels.rgba[i].toInt() and 0xff
            val g = pixels.rgba[i + 1].toInt() and 0xff
            val b = pixels.rgba[i + 2].toInt() and 0xff
            if (r >= 200 && g <= 70 && b <= 70) {
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
        if (right < 0) return null
        return Rect(area.left + left, area.top + top, area.left + right + 1, area.top + bottom + 1)
    }

    private fun near(a: Rect, b: Rect, tolerance: Float = 3f): Boolean =
        abs(a.left - b.left) <= tolerance && abs(a.top - b.top) <= tolerance && abs(a.right - b.right) <= tolerance && abs(a.bottom - b.bottom) <= tolerance

    /** How many pixels of [pixels] are [rgb], within 40 a channel. */
    private fun count(pixels: Pixels, rgb: Int): Int {
        var n = 0
        val tr = rgb shr 16 and 0xff
        val tg = rgb shr 8 and 0xff
        val tb = rgb and 0xff
        for (i in 0 until pixels.width * pixels.height) {
            val r = pixels.rgba[i * 4].toInt() and 0xff
            val g = pixels.rgba[i * 4 + 1].toInt() and 0xff
            val b = pixels.rgba[i * 4 + 2].toInt() and 0xff
            if (abs(r - tr) <= 40 && abs(g - tg) <= 40 && abs(b - tb) <= 40) n++
        }
        return n
    }

    /** The window's backing (pure green): where neither Compose nor a page draws. */
    private fun backing(pixels: Pixels): Int {
        var n = 0
        for (i in 0 until pixels.width * pixels.height) {
            val r = pixels.rgba[i * 4].toInt() and 0xff
            val g = pixels.rgba[i * 4 + 1].toInt() and 0xff
            val b = pixels.rgba[i * 4 + 2].toInt() and 0xff
            if (g >= 200 && r <= 80 && b <= 80) n++
        }
        return n
    }

    private fun Rect.inset(by: Float) = Rect(left + by, top + by, right - by, bottom - by)

    val all: List<Check> = listOf(
        // C1: AWT's content view isn't flipped; a page placed from its bottom moved whenever the window's height changed.
        Check("mac-review-a-window-height-change-keeps-the-page-in-its-slot", timeoutMillis = 120_000) {
            val stage = Stage()
            val state = WebViewState(markerPage("c1"))
            var slot: Rect? = null
            var header: Rect? = null
            var headerPresses by mutableIntStateOf(0)
            within(stage, listOf(state), scene = {
                Column(Modifier.fillMaxSize().background(Color(0xFF202124))) {
                    Box(
                        Modifier.fillMaxWidth().height(120.dp).background(Color(0xFF3C4043))
                            .clickable { headerPresses++ }.onGloballyPositioned { header = it.boundsInWindow() },
                    )
                    WebView(state, Modifier.padding(start = 40.dp).size(360.dp, 240.dp).onGloballyPositioned { slot = it.boundsInWindow() }, light, activation = Activation.Eager)
                }
            }) { mac ->
                val start = mac.windowState.size
                suspend fun judge(what: String) {
                    val page = assertNotNull(slot)
                    val area = page.inflate(8 * mac.density).let { Rect(max(0f, it.left), max(0f, it.top), it.right, it.bottom) }
                    val pixels = assertNotNull(mac.copy(area)?.pixels(), "a copy of the window ($what)")
                    val marker = markerIn(pixels, area)
                    val expected = expectedMarker(mac, page)
                    assertTrue(marker != null && near(marker, expected), "the page's marker at $marker, its slot's at $expected ($what)")
                    val s = mac.density
                    assertEquals("page", MacEngineTesting.routes(mac.window, page.left + 100 * s, page.top + 12 * s, wheel = false), "the page's top ($what)")
                    assertEquals("compose", MacEngineTesting.routes(mac.window, page.left + 100 * s, page.top - 12 * s, wheel = false), "just above the page ($what)")
                    val before = presses(state)
                    val headerBefore = headerPresses
                    MacEngineTesting.click(mac.window, page.left + 100 * s, page.top + 12 * s)
                    waitFor("the page's press ($what)", millis = 5_000) { presses(state) == before + 1 }
                    MacEngineTesting.click(mac.window, page.left + 100 * s, page.top - 12 * s)
                    waitFor("the header's press ($what)", millis = 5_000) { headerPresses == headerBefore + 1 }
                    assertEquals(before + 1, presses(state), "the header's press never reaches the page ($what)")
                }
                try {
                    judge("as the window opened")
                    mac.windowState.size = DpSize(start.width, start.height - 150.dp)
                    delay(1_500)
                    judge("150 dp shorter")
                    mac.windowState.size = DpSize(start.width, start.height + 150.dp)
                    delay(1_500)
                    judge("150 dp taller")
                    mac.windowState.placement = WindowPlacement.Maximized
                    delay(2_000)
                    judge("zoomed")
                } finally {
                    // Unzoomed and back at its size before anything else runs: macOS animates the unzoom, and a size set
                    // meanwhile is lost.
                    mac.windowState.placement = WindowPlacement.Floating
                    withTimeoutOrNull(10_000) {
                        while (mac.window.extendedState != java.awt.Frame.NORMAL || mac.windowState.size != start) {
                            delay(500)
                            if (mac.window.extendedState == java.awt.Frame.NORMAL) mac.windowState.size = start
                        }
                    }
                    delay(1_000)
                }
            }
        },
        // C2: a chat's demo growing as its page reports a new height, under the runtime's spring: the cover magnified it,
        // and the page refused presses while it grew.
        Check("mac-review-a-growing-embed-keeps-its-content-in-place-and-takes-presses", timeoutMillis = 120_000) {
            val stage = Stage()
            val grow = """hostBridge.handle('grow', function () {
  var n = 0, t = setInterval(function () {
    var d = document.createElement('div'); d.style.height = '60px'; document.body.appendChild(d);
    if (++n == 8) clearInterval(t);
  }, 300);
  return true;
});"""
            val state = WebViewState(markerPage("c2", fill = false, script = "document.body.appendChild(Object.assign(document.createElement('div'), {style: 'height:120px'}));$grow"))
            var slot: Rect? = null
            within(stage, listOf(state), scene = {
                Column(Modifier.fillMaxSize().background(Color(0xFF202124)).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(80.dp))
                    WebView(
                        state,
                        Modifier.padding(horizontal = 40.dp).width(420.dp).onGloballyPositioned { slot = it.boundsInWindow() },
                        light,
                        activation = Activation.Eager,
                        sizing = WebSizing.WrapContentHeight(max = 900.dp),
                    )
                    Spacer(Modifier.height(400.dp))
                }
            }) { mac ->
                delay(800)
                val first = assertNotNull(slot)
                val draws = MacEngineTesting.motionCoverDraws
                state.bridge.call("grow")
                var frames = 0
                var misplaced = 0
                var unseen = 0
                var refused = 0
                var backingSeen = 0
                var worst = ""
                val pressesBefore = presses(state)
                var clicked = 0
                val start = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    if ((now - start) / 1e9 > 4.5) break
                    frames++
                    val page = slot ?: continue
                    val expected = expectedMarker(mac, page)
                    val area = Rect(page.left, page.top, page.right, min(page.bottom, page.top + 120 * mac.density))
                    val pixels = mac.copy(area)?.pixels() ?: continue
                    val marker = markerIn(pixels, area)
                    if (marker == null) unseen++ else if (!near(marker, expected)) {
                        misplaced++
                        worst = "the marker at $marker, its place $expected"
                    }
                    backingSeen += backing(pixels)
                    if (MacEngineTesting.routes(mac.window, expected.center.x, expected.center.y, wheel = false) != "page") refused++
                    if (frames == 60 || frames == 150) {
                        MacEngineTesting.click(mac.window, expected.center.x, expected.center.y)
                        clicked++
                    }
                }
                val last = assertNotNull(slot)
                println("MEASURE review growing-embed: $frames frames, grew ${first.height} → ${last.height} px; marker misplaced $misplaced, unseen $unseen; presses refused $refused; backing px $backingSeen; the cover drawn ${MacEngineTesting.motionCoverDraws - draws} times")
                assertTrue(last.height >= first.height + 300 * mac.density, "the embed grew: ${first.height} → ${last.height} px")
                assertTrue(frames >= 120, "too few frames to judge: $frames")
                assertEquals(0, misplaced, "frames with the page's content out of place ($worst)")
                assertEquals(0, unseen, "frames that showed no marker")
                assertEquals(0, refused, "frames in which the page refused a press while it grew")
                assertEquals(0, backingSeen, "the window's backing in the slot")
                waitFor("the presses made while it grew", millis = 5_000) { presses(state) == pressesBefore + clicked }
            }
        },
        // I1: a press on the page also reached the Compose clickable under it while any overlay was marked.
        Check("mac-review-a-press-on-a-page-reaches-no-clickable-under-it") {
            val stage = Stage()
            val state = WebViewState(markerPage("i1"))
            var page: Rect? = null
            var parentClicks by mutableIntStateOf(0)
            var menu by mutableStateOf(false)
            within(stage, listOf(state), scene = {
                Box(Modifier.fillMaxSize().background(Color(0xFF202124)).padding(40.dp)) {
                    Box(Modifier.size(420.dp, 300.dp).background(Color(0xFF3C4043)).clickable { parentClicks++ }.padding(20.dp)) {
                        WebView(state, Modifier.fillMaxSize().onGloballyPositioned { page = it.boundsInWindow() }, light, activation = Activation.Eager)
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.webViewOverlay(MenuDefaults.shape)) {
                            DropdownMenuItem(text = { Text("Copy link") }, onClick = { menu = false })
                        }
                    }
                    // A marked overlay elsewhere in the window, a toolbar that stays.
                    Box(Modifier.align(Alignment.TopEnd).size(120.dp, 60.dp).webViewOverlay().background(Color(0xFF4F7CFF)))
                }
            }) { mac ->
                val bounds = assertNotNull(page)
                val s = mac.density
                MacEngineTesting.click(mac.window, bounds.left + 200 * s, bounds.top + 200 * s)
                waitFor("the page's press") { presses(state) == 1 }
                delay(300)
                assertEquals(0, parentClicks, "the clickable under the page took the page's press")
                menu = true
                delay(600)
                MacEngineTesting.click(mac.window, bounds.left + 300 * s, bounds.top + 220 * s)
                waitFor("the menu closed by a press on the page", millis = 3_000) { !menu }
                waitFor("the press reached the page") { presses(state) == 2 }
                delay(300)
                assertEquals(0, parentClicks, "the clickable under the page took the press that closed the menu")
            }
        },
        // I2: a page half scrolled out of its clipped scroller drew its hidden half over the page beside it.
        Check("mac-review-a-page-half-out-of-its-scroller-covers-no-other-page") {
            val stage = Stage()
            val a = WebViewState(markerPage("i2-a"))
            val b = WebViewState(markerPage("i2-b", background = AMBER, marker = false))
            var boundsA: Rect? = null
            var boundsB: Rect? = null
            // A comes once B is live: its view then lies above B's natively, as the review's probe had it.
            var showA by mutableStateOf(false)
            within(stage, listOf(b), scene = {
                Column(Modifier.fillMaxSize().background(Color(0xFF202124)).padding(start = 40.dp)) {
                    Spacer(Modifier.height(80.dp))
                    WebView(b, Modifier.size(360.dp, 120.dp).onGloballyPositioned { boundsB = it.boundsInWindow() }, light, activation = Activation.Eager)
                    // The scroller: its first 120 dp of A scrolled out of view, above it, over B.
                    Box(Modifier.size(360.dp, 240.dp).clipToBounds()) {
                        if (showA) {
                            WebView(a, Modifier.offset(y = (-120).dp).size(360.dp, 240.dp).onGloballyPositioned { boundsA = it.boundsInWindow() }, light, activation = Activation.Eager)
                        }
                    }
                }
            }) { mac ->
                showA = true
                waitFor("A live and announced") { a.phase == WebViewPhase.Live && a.bridge.isReady }
                delay(600)
                val pageB = assertNotNull(boundsB).inset(4f)
                val shownA = assertNotNull(boundsA).inset(4f)
                val pixels = assertNotNull(mac.copy(pageB)?.pixels(), "a copy of B")
                val blue = count(pixels, 0x1E3A8A)
                val amber = count(pixels, 0xF59E0B)
                println("MEASURE review clipped-page: B's area $amber amber px, $blue of A's blue")
                assertEquals(0, blue, "A's hidden half drawn over B")
                assertTrue(amber > pixels.width * pixels.height * 9 / 10, "B shows: $amber amber of ${pixels.width * pixels.height}")
                val s = mac.density
                MacEngineTesting.click(mac.window, pageB.left + 100 * s, pageB.center.y)
                waitFor("B's press") { presses(b) == 1 }
                assertEquals(0, presses(a), "A took B's press")
                MacEngineTesting.click(mac.window, shownA.left + 100 * s, shownA.bottom - 40 * s)
                waitFor("A's press, in its part that shows") { presses(a) == 1 }
            }
        },
        // I3: an app's UI zoom (its LocalDensity) put the page at the wrong size and place: the glue took Compose's px a
        // dp for its px a point.
        Check("mac-review-an-apps-ui-zoom-places-the-page-exactly-in-its-hole") {
            val stage = Stage()
            val state = WebViewState(markerPage("i3"))
            var slot: Rect? = null
            within(stage, listOf(state), scene = {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * 1.25f, density.fontScale)) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF202124)).padding(start = 60.dp, top = 80.dp)) {
                        WebView(state, Modifier.size(320.dp, 240.dp).onGloballyPositioned { slot = it.boundsInWindow() }, light, activation = Activation.Eager)
                    }
                }
            }) { mac ->
                val page = assertNotNull(slot)
                val (width, height) = viewport(state)
                val s = mac.density
                println("MEASURE review ui-zoom: slot ${page.width} × ${page.height} px at ${s}x; the page's viewport $width × $height CSS px")
                assertTrue(abs(width - page.width / s) <= 1f && abs(height - page.height / s) <= 1f, "the page's viewport $width × $height, its hole ${page.width / s} × ${page.height / s} points")
                val pixels = assertNotNull(mac.copy(page)?.pixels(), "a copy of the hole")
                val marker = markerIn(pixels, page)
                assertTrue(marker != null && near(marker, expectedMarker(mac, page)), "the page's marker at $marker, its slot's at ${expectedMarker(mac, page)}")
                assertEquals(0, backing(mac.copy(page.inset(2f))?.pixels() ?: pixels), "the window's backing in the hole: the page doesn't fill it")
            }
        },
    )
}
