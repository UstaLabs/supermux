package dev.supermux.webview.engine.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.supermux.webview.Activation
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.WebSizing
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.WebViewState
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.web.spike.ms
import dev.supermux.webview.engine.web.spike.percentile
import dev.supermux.webview.rememberWebViewState
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.webViewOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private const val DEMO = """<!doctype html><html><body style="margin:0"><div style="height:320px">demo</div><script>
hostBridge.handle('echo', function (x) { return x; });
hostBridge.call('sum', [1, 2, 3]).then(function (r) { hostBridge.post('summed', r); });
</script></body></html>"""

/** A page of another site on the test server (its other name), told apart by [id] (karma.config.d). */
private fun crossSitePage(id: String): String =
    js("location.protocol + '//' + (location.hostname === 'localhost' ? '127.0.0.1' : 'localhost') + ':' + location.port + '/webview-test/page?id=' + id")

/** Counts the documents the runtime asks the engines to start. */
private class CountingFactory : WebEngineFactory {
    var starts = 0

    override suspend fun create(config: EngineConfig): WebEngineResult = when (val result = IframeEngineFactory.create(config)) {
        is WebEngineResult.Created -> WebEngineResult.Created(Counted(result.engine))
        is WebEngineResult.Unavailable -> result
    }

    private inner class Counted(private val engine: WebEngine) : WebEngine by engine {
        override fun load(content: WebContent) {
            starts++
            engine.load(content)
        }

        override fun reload() {
            starts++
            engine.reload()
        }
    }
}

class WebViewOnIframeTest {
    private fun runtime(factory: WebEngineFactory = IframeEngineFactory, maxLive: Int = 4) = WebViewRuntime(
        factory = factory,
        config = RuntimeConfig(maxLive = maxLive, maxIdle = 2, snapshotCacheBytes = 0, hiddenReleaseMillis = 300),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )

    /**
     * Seamless handoff, end to end on the web: in every animation frame from the view's first until it has been live for
     * ten, the page is seen (its frame `visible`, and not at `opacity: 0`), or the view's cover is composed (its
     * placeholder: the web takes no snapshots). No frame shows neither: the cover goes only once the page is seen.
     */
    @Test fun noFrameShowsNeitherThePageNorItsCover() = runTest(timeout = 2.minutes) {
        val rt = runtime()
        val content = WebContent.Html("<!doctype html><html><body style='margin:0;background:#123;color:#eee'>still</body></html>")
        val state = WebViewState(content)
        var placeholderShown = false
        TestViewport.clear()
        TestViewport.show {
            ProvideWebViewRuntime(rt) {
                WebView(
                    state,
                    Modifier.size(400.dp, 300.dp),
                    activation = Activation.Eager,
                    placeholder = {
                        DisposableEffect(Unit) {
                            placeholderShown = true
                            onDispose { placeholderShown = false }
                        }
                        Box(Modifier.fillMaxSize().background(Color.Magenta))
                    },
                )
            }
        }
        val neither = ArrayList<Int>()
        var frame = 0
        var live = 0
        var seenFirst = -1
        var coverGone = -1
        while (live < 10) {
            frames(1)
            frame++
            val outer = (state.engine as? IframeWebEngine)?.frame?.outer
            val seen = outer != null && styleValue(outer, "visibility") == "visible" && styleValue(outer, "opacity") != "0"
            if (seen && seenFirst < 0) seenFirst = frame
            if (!placeholderShown && coverGone < 0 && frame > 1) coverGone = frame
            if (frame > 1 && !seen && !placeholderShown) neither += frame
            if (state.phase == WebViewPhase.Live) live++
            check(frame < 3_000) { "not live after $frame frames" }
        }
        assertEquals(emptyList(), neither, "frames that showed neither the page nor its cover")
        assertTrue(seenFirst in 1..coverGone, "the page seen at frame $seenFirst, the cover gone at $coverGone")
        TestViewport.clear()
        rt.scope.cancel()
    }

    @Test fun anAgentDemoGoesLiveCallsTheAppAndAnswersFast() = runTest(timeout = 2.minutes) {
        val rt = runtime()
        val content = WebContent.Html(DEMO)
        val state = WebViewState(content)
        var summed: JsonElement? = null
        state.bridge.handle("sum") { params -> JsonPrimitive(params.jsonArray.sumOf { it.jsonPrimitive.int }) }
        state.bridge.on("summed") { summed = it }
        // The viewport is up first, as it is in an app: the first show in a page also creates it and skiko's first
        // frames, a few hundred ms that aren't the view's.
        TestViewport.clear()
        val shownAt = now()
        TestViewport.show {
            ProvideWebViewRuntime(rt) { WebView(state, Modifier.size(400.dp, 300.dp), activation = Activation.Eager) }
        }
        waitUntil("the demo to go live", 20_000) { state.phase == WebViewPhase.Live }
        // Spec §10: visible and settled to first paint, ≤ 100 ms on desktop. Eager goes live at once; a cold start, with
        // no spare yet, so this is the slow case.
        println("WEBVIEW shown-to-first-paint engine=iframe cold ms=${(now() - shownAt).ms()}")
        assertEquals(content.origin.url, state.url)
        waitUntil("the page's call to come back") { summed != null }
        assertEquals(JsonPrimitive(6), summed)
        val times = ArrayList<Double>()
        repeat(200) { i ->
            val start = now()
            assertEquals(JsonPrimitive(i), state.bridge.call("echo", JsonPrimitive(i)))
            times += now() - start
        }
        println("WEBVIEW bridge-round-trip engine=iframe n=200 p50=${percentile(times, 0.5).ms()} p95=${percentile(times, 0.95).ms()}")
        TestViewport.clear()
        rt.scope.cancel()
    }

    @Test fun wrapContentHeightFollowsThePage() = runTest(timeout = 1.minutes) {
        val rt = runtime()
        val state = WebViewState(WebContent.Html(DEMO))
        TestViewport.show {
            ProvideWebViewRuntime(rt) {
                WebView(state, activation = Activation.Eager, sizing = WebSizing.WrapContentHeight(max = 500.dp))
            }
        }
        waitUntil("the page's height", 20_000) { state.contentSize?.height == 320.dp }
        TestViewport.clear()
        rt.scope.cancel()
    }

    @Test fun aListOfLiveDemosNeverStartsADocumentNobodyAskedFor() = runTest(timeout = 4.minutes) {
        val factory = CountingFactory()
        val rt = runtime(factory)
        val list = LazyListState()
        var started = 0
        val contents = (0 until 30).map { i ->
            WebContent.Html("<!doctype html><html><body style=\"margin:0\"><div style=\"height:180px\">demo $i</div><script>hostBridge.post('started', $i);</script></body></html>")
        }
        TestViewport.show {
            ProvideWebViewRuntime(rt) {
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    items(contents.size) { i ->
                        val state = rememberWebViewState(contents[i])
                        DisposableEffect(state) {
                            val stop = state.bridge.on("started") { started++ }
                            onDispose { stop() }
                        }
                        WebView(
                            state,
                            Modifier.padding(8.dp),
                            activation = Activation.WhenVisible(settleMillis = 100),
                            sizing = WebSizing.WrapContentHeight(max = 240.dp),
                        )
                    }
                }
            }
        }
        repeat(10) {
            repeat(8) {
                list.scrollBy(40f)
                frames(1)
            }
            pause(700)
        }
        repeat(80) {
            list.scrollBy(-40f)
            frames(1)
        }
        pause(1_500)
        println("WEBVIEW list-documents started-by-runtime=${factory.starts} pages-that-started=$started")
        assertTrue(started <= factory.starts, "a page started that the runtime never asked for: $started > ${factory.starts}")
        assertTrue(factory.starts > 0)
        TestViewport.clear()
        rt.scope.cancel()
    }

    @Test fun aComposeMenuOverALivePageTakesItsClick() = runTest(timeout = 2.minutes) {
        val rt = runtime()
        val state = WebViewState(WebContent.Html("<!doctype html><html><body style=\"margin:0;background:#cfe\">page</body></html>"))
        val shape = RoundedCornerShape(12.dp)
        var menu by mutableStateOf(false)
        var clicks by mutableIntStateOf(0)
        TestViewport.show {
            ProvideWebViewRuntime(rt) {
                Box(Modifier.fillMaxSize()) {
                    WebView(state, Modifier.offset(40.dp, 40.dp).size(600.dp, 400.dp), activation = Activation.Eager)
                    if (menu) {
                        Box(
                            Modifier.offset(200.dp, 150.dp).size(240.dp, 160.dp).clip(shape).background(Color(0xFF3366FF))
                                .webViewOverlay(shape).clickable { clicks++ },
                        )
                    }
                }
            }
        }
        waitUntil("the page to go live", 20_000) { state.phase == WebViewPhase.Live }
        assertTrue(hitAt(320.0, 230.0).startsWith("iframe:"), "no menu: the page takes the point")
        menu = true
        waitUntil("the hole") { hitAt(320.0, 230.0) == "compose" }
        val elsewhere = hitAt(100.0, 100.0)
        when (WebEngineDecisions.cutoutMode) {
            CutoutMode.ClipPath -> assertTrue(elsewhere.startsWith("iframe:"), "the rest of the page still takes clicks: $elsewhere")
            CutoutMode.HidePage -> assertEquals("compose", elsewhere, "the page hides while the menu is open")
        }
        clickCompose(320.0, 230.0)
        frames(3)
        assertEquals(1, clicks)
        menu = false
        waitUntil("the page to take the point again") { hitAt(320.0, 230.0).startsWith("iframe:") }
        TestViewport.clear()
        rt.scope.cancel()
    }

    @Test fun aPagesStateSurvivesItsEngineGoing() = runTest(timeout = 2.minutes) {
        val rt = runtime()
        val content = WebContent.Html(
            """<!doctype html><html><body><script>
            var n = 0;
            hostBridge.onSaveState(function () { return { n: n }; });
            hostBridge.onRestoreState(function (s) { n = s.n; hostBridge.post('restored', n); });
            hostBridge.on('bump', function () { n++; hostBridge.post('count', n); });
            </script></body></html>""",
        )
        val first = WebViewState(content)
        var count = 0
        first.bridge.on("count") { count = it.jsonPrimitive.int }
        var shown by mutableStateOf<WebViewState?>(first)
        TestViewport.show {
            ProvideWebViewRuntime(rt) { shown?.let { WebView(it, Modifier.size(300.dp, 200.dp), activation = Activation.Eager) } }
        }
        waitUntil("the first view to go live", 20_000) { first.phase == WebViewPhase.Live && first.bridge.isReady }
        repeat(3) { first.bridge.post("bump") }
        waitUntil("three bumps") { count == 3 }
        shown = null
        waitUntil("the page parked", 5_000) { rt.pool.parkedCount == 1 }
        // Memory runs short: the parked page saves itself, through a bridge of its own, and its engine goes.
        rt.onMemoryPressure()
        waitUntil("the parked page reclaimed", 5_000) { rt.pool.parkedCount == 0 }
        rt.onMemoryRecovered()
        val second = WebViewState(content)
        var restored = -1
        second.bridge.on("restored") { restored = it.jsonPrimitive.int }
        shown = second
        waitUntil("the page state to come back", 20_000) { restored == 3 }
        TestViewport.clear()
        rt.scope.cancel()
    }

    /** A view of the page that left takes it over live: the page goes on counting from where it was, never restored. */
    @Test fun anAdoptedPageIsTheLivePage() = runTest(timeout = 2.minutes) {
        val rt = runtime()
        val content = WebContent.Html(
            """<!doctype html><html><body><script>
            var n = 0;
            hostBridge.onSaveState(function () { return { n: n }; });
            hostBridge.onRestoreState(function (s) { n = s.n; hostBridge.post('restored', n); });
            hostBridge.on('bump', function () { n++; hostBridge.post('count', n); });
            </script></body></html>""",
        )
        val first = WebViewState(content)
        var count = 0
        first.bridge.on("count") { count = it.jsonPrimitive.int }
        var shown by mutableStateOf<WebViewState?>(first)
        TestViewport.show {
            ProvideWebViewRuntime(rt) { shown?.let { WebView(it, Modifier.size(300.dp, 200.dp), activation = Activation.Eager) } }
        }
        waitUntil("the first view to go live", 20_000) { first.phase == WebViewPhase.Live && first.bridge.isReady }
        repeat(3) { first.bridge.post("bump") }
        waitUntil("three bumps") { count == 3 }
        shown = null
        waitUntil("the page parked", 5_000) { rt.pool.parkedCount == 1 }
        val second = WebViewState(content)
        var restored = -1
        second.bridge.on("restored") { restored = it.jsonPrimitive.int }
        second.bridge.on("count") { count = it.jsonPrimitive.int }
        shown = second
        waitUntil("the second view live and talking", 20_000) { second.phase == WebViewPhase.Live && second.bridge.isReady }
        assertEquals(0, rt.pool.parkedCount, "the second view adopted the page")
        second.bridge.post("bump")
        waitUntil("a fourth bump") { count == 4 }
        assertEquals(-1, restored, "the live page got its own state back")
        TestViewport.clear()
        rt.scope.cancel()
    }

    /**
     * The review's probe. A page of another site whose view is off screen, still bound, gives its slot to a view on
     * screen. Its view back on screen, while views on screen hold every slot, showed nothing: "Live", no placeholder, no
     * iframe. It shows its placeholder now, and its page loads afresh once a slot frees.
     */
    @Test fun aViewBackOnScreenWithNoSlotShowsItsPlaceholderAndLoadsWhenOneFrees() = runTest(timeout = 3.minutes) {
        val rt = WebViewRuntime(
            factory = IframeEngineFactory,
            config = RuntimeConfig(maxLive = 8, maxIdle = 2, snapshotCacheBytes = 0, hiddenReleaseMillis = 600_000),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        )
        val budget = WebEngineDecisions.MAX_LIVE_URL
        val states = List(budget + 1) { WebViewState(WebContent.Url(crossSitePage("slots-$it"))) }
        val placeholderOn = BooleanArray(budget + 1)
        var onScreen by mutableStateOf((0 until budget).toSet())
        TestViewport.clear()
        TestViewport.show {
            ProvideWebViewRuntime(rt) {
                Box(Modifier.fillMaxSize()) {
                    states.forEachIndexed { i, state ->
                        WebView(
                            state,
                            Modifier.offset(if (i in onScreen) (160 * i).dp else 2_000.dp, 40.dp).size(150.dp, 200.dp),
                            activation = Activation.Eager,
                            placeholder = {
                                DisposableEffect(Unit) {
                                    placeholderOn[i] = true
                                    onDispose { placeholderOn[i] = false }
                                }
                                Box(Modifier.fillMaxSize().background(Color.Magenta))
                            },
                        )
                    }
                }
            }
        }
        waitUntil("the views on screen live", 30_000) { (0 until budget).all { states[it].phase == WebViewPhase.Live } }
        // The first view goes off screen, still bound; the last comes on and takes its slot.
        onScreen = (1..budget).toSet()
        waitUntil("the last view live", 30_000) { states[budget].phase == WebViewPhase.Live }
        // The first comes back, while views on screen hold every slot.
        onScreen = (0..budget).toSet()
        pause(1_000)
        assertTrue(placeholderOn[0], "back on screen with no slot, the view shows its placeholder: ${states[0].phase}")
        assertEquals(WebViewPhase.Activating, states[0].phase)
        // A slot frees: its page loads afresh.
        onScreen = (0 until budget).toSet()
        waitUntil("the first view live again", 30_000) { states[0].phase == WebViewPhase.Live }
        waitUntil("its placeholder gone") { !placeholderOn[0] }
        TestViewport.clear()
        rt.scope.cancel()
    }

    @Test fun contentTheWebCannotShowSaysWhy() = runTest(timeout = 1.minutes) {
        val rt = runtime()
        val state = WebViewState(WebContent.Bundle(WebOrigin("plugin-b"), WebResourceProvider { null }))
        TestViewport.show {
            ProvideWebViewRuntime(rt) { WebView(state, Modifier.size(300.dp, 200.dp), activation = Activation.Eager) }
        }
        waitUntil("the error", 20_000) { state.error != null }
        assertTrue(state.error is WebError.UnsupportedOnPlatform, "${state.error}")
        assertEquals(WebViewPhase.Live, state.phase)
        TestViewport.clear()
        rt.scope.cancel()
    }

    @Test fun placingAGridOfLiveDemosWithTheRealRuntime() = runTest(timeout = 4.minutes) {
        // The feasibility test's G3, measured again with the runtime (amendment 6): a 3-column grid of 140 dp demos,
        // WhenVisible, as many live as the budget allows. "steady" moves the grid 1 px a frame within 30 px, so every live
        // page stays on screen and is placed every frame; "through" scrolls 6 px a frame for 300 frames, as the spike
        // did, where WhenVisible creates no iframe mid-scroll and pages that leave are let go. Prints the JS time placing
        // frames per frame (the page's clock steps in 0.1 ms, and each placement is timed apart and summed) and the
        // frame intervals. Recorded, not judged: the Mac it runs on is shared.
        val rt = runtime(maxLive = WebEngineDecisions.MAX_LIVE)
        val grid = LazyGridState()
        val contents = (0 until 300).map { WebContent.Html("<!doctype html><html><body style=\"margin:0;background:#e8eefc\">demo $it</body></html>") }
        TestViewport.show {
            ProvideWebViewRuntime(rt) {
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), state = grid) {
                    items(contents.size, key = { it }) { i ->
                        WebView(
                            rememberWebViewState(contents[i]),
                            Modifier.fillMaxWidth().height(140.dp).padding(4.dp),
                            activation = Activation.WhenVisible(settleMillis = 100),
                        )
                    }
                }
            }
        }
        waitUntil("a screenful to go live", 60_000) { rt.pool.liveCount >= WebEngineDecisions.MAX_LIVE }
        pause(1_500)
        suspend fun measure(phase: String, step: (Int) -> Float) {
            val live = rt.pool.liveCount
            val intervals = ArrayList<Double>()
            val costs = ArrayList<Double>()
            WebPlacementTiming.enabled = true
            frames(1)
            WebPlacementTiming.take()
            var last = now()
            repeat(300) { i ->
                grid.scrollBy(step(i))
                frames(1)
                val t = now()
                intervals += t - last
                last = t
                costs += WebPlacementTiming.take()
            }
            WebPlacementTiming.enabled = false
            println(
                "WEBVIEW placement engine=iframe phase=$phase live-at-start=$live live-at-end=${rt.pool.liveCount} frames=300 " +
                    "placement-p50=${percentile(costs, 0.5).ms()} p95=${percentile(costs, 0.95).ms()} " +
                    "mean=${(costs.sum() / costs.size).ms()} max=${(costs.maxOrNull() ?: Double.NaN).ms()} " +
                    "interval-p50=${percentile(intervals, 0.5).ms()} p95=${percentile(intervals, 0.95).ms()} " +
                    "over-25ms=${intervals.count { it > 25.0 }}",
            )
        }
        measure("steady") { i -> if ((i / 30) % 2 == 0) 1f else -1f }
        measure("through") { 6f }
        TestViewport.clear()
        rt.scope.cancel()
    }
}
