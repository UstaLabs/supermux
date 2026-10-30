package dev.supermux.webview

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.OverlayCutouts
import dev.supermux.webview.runtime.PRIORITY_HIDDEN
import dev.supermux.webview.runtime.PageKey
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.SavedPageState
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The host's side of the contracts it lives by (Task 14b): engines that fail or hang, the pool's holder contract,
 * per-document state, and what a view remembers. Several pin behaviour the review's mutants showed untested.
 */
@OptIn(ExperimentalTestApi::class)
class WebViewHostTest {
    private val url = WebContent.Url("https://example.test/")
    private val notes = WebContent.Html("<p>notes</p>", WebOrigin("notes"))

    private fun <T> ComposeUiTest.ui(block: () -> T): T = runOnUiThread(block)

    private fun ComposeUiTest.calls(engine: FakeWebEngine): List<String> = ui { engine.calls.toList() }

    private fun ComposeUiTest.assertNoViolations(factory: FakeWebEngineFactory) = ui {
        factory.created.forEach { assertEquals(emptyList(), it.violations, "engine ${it.id}'s violations") }
    }

    private fun ComposeUiTest.waitFor(what: String, condition: () -> Boolean) =
        waitUntil(conditionDescription = what, timeoutMillis = TIMEOUT, condition = condition)

    private fun ComposeUiTest.centerColor(tag: String): Color {
        val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    /** The engine that loaded [content]. */
    private fun ComposeUiTest.engineOf(factory: FakeWebEngineFactory, content: WebContent): FakeWebEngine =
        ui { factory.created.single { engine -> engine.calls.any { it == "load:${content.key}" } } }

    /** Records every cut-out it is given. */
    private class Recording(val fake: FakeWebEngine) : WebEngine by fake {
        val applied = mutableListOf<OverlayCutouts>()

        override fun applyOverlays(cutouts: OverlayCutouts) {
            applied += cutouts
            fake.applyOverlays(cutouts)
        }
    }

    // --- Engines that fail ---

    @Test fun anEngineThatThrowsIsTreatedAsCrashedAndReported() = runComposeUiTest {
        val fake = FakeWebEngineFactory()
        val runtime = testRuntime(
            WebEngineFactory { config ->
                val engine = (fake.create(config) as WebEngineResult.Created).engine
                WebEngineResult.Created(
                    object : WebEngine by engine {
                        override fun configure(setup: PageSetup) {
                            throw IllegalStateException("engine broke")
                        }
                    },
                )
            },
        )
        val errors = mutableListOf<Throwable>()
        ui { runtime.onError = { errors += it } }
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitFor("crashed") { state.phase == WebViewPhase.Crashed }
        assertEquals("engine broke", ui { errors.first().message })
        // Destroyed, never reused.
        waitFor("destroyed") { ui { fake.created.first().released } }
        waitFor("no live engine") { ui { runtime.pool.liveCount } == 0 }
        assertNoViolations(fake)
    }

    /** Leaving the composition saves the page in bounded time, even from an engine that never answers. */
    @Test fun aViewThatLeavesWhileItsEngineHangsStillGivesItBack() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        // Spare room: an engine given back healthy would be reset and kept.
        val runtime = testRuntime(factory, maxIdle = 2)
        var shown by mutableStateOf(true)
        var state: WebViewState? = null
        setContent {
            ProvideWebViewRuntime(runtime) {
                if (shown) WebView(rememberWebViewState(url).also { state = it }, Modifier.size(100.dp))
            }
        }
        waitFor("live") { state?.phase == WebViewPhase.Live }
        val engine = ui { factory.created.first() }
        val gate = CompletableDeferred<Unit>()
        try {
            ui { engine.saveStateGate = gate }
            shown = false
            waitFor("given back") { ui { runtime.pool.liveCount } == 0 }
            // An engine that stopped answering is destroyed, not reset for another view.
            waitFor("destroyed") { ui { engine.released } }
            assertTrue("reset" !in calls(engine), "${calls(engine)}")
            assertEquals(1, calls(engine).count { it == "save" })
        } finally {
            ui { gate.complete(Unit) }
        }
        waitForIdle()
        assertNoViolations(factory)
    }

    /** A view that lost its state (it moved while the old view was saving) hears nothing more of the old page. */
    @Test fun aViewThatLostItsStateHearsNothingMoreOfItsPage() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("draft")))
        val runtime = testRuntime(factory)
        val state = WebViewState(notes)
        var wide by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                if (wide) Row { WebView(state, Modifier.size(100.dp)) } else Column { WebView(state, Modifier.size(100.dp)) }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val first = ui { factory.created.single() }
        val gate = CompletableDeferred<Unit>()
        try {
            ui { first.saveStateGate = gate }
            wide = true
            waitFor("live on the moved view's engine") {
                ui { factory.created.size } == 2 && state.phase == WebViewPhase.Live && ui { state.bridge.isReady }
            }
            // The old view still holds its engine, saving it, and the old page goes on talking.
            assertEquals(2, ui { runtime.pool.liveCount })
            val url = state.url
            ui {
                first.emit(EngineEvent.TitleChanged("the old page"))
                first.emit(EngineEvent.UrlChanged("https://elsewhere.test/"))
            }
            waitForIdle()
            ui {
                assertNull(state.title)
                assertEquals(url, state.url)
                assertTrue(state.bridge.isReady, "the old page's commit closed the moved view's bridge")
            }
        } finally {
            ui { gate.complete(Unit) }
        }
        waitFor("the old engine given back") { ui { runtime.pool.liveCount } == 1 }
        assertNoViolations(factory)
    }

    /** The runtime counts the snapshot pixels views hold as covers: a view on screen holds one, off screen or gone none. */
    @Test fun theRuntimeCountsTheCoverPixelsOfViewsOnScreen() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = 50)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        var composed by mutableStateOf(true)
        var activation by mutableStateOf<Activation>(Activation.WhenVisible(settleMillis = 0))
        setContent {
            ProvideWebViewRuntime(runtime) {
                if (composed) {
                    Box(Modifier.size(if (shown) 100.dp else 0.dp)) { WebView(state, Modifier.size(100.dp), activation = activation) }
                }
            }
        }
        fun covers() = ui { runtime.coverCount to runtime.coverBytes }
        assertEquals(0 to 0L, covers(), "no snapshot yet")
        waitFor("a snapshot") { ui { state.snapshotKey != null } }
        // Kept at the screen's density (1 here), in full colour.
        val px = 100L
        assertEquals(1 to px * px * 4, covers(), "on screen, a live view holds its latest snapshot")
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        assertEquals(0 to 0L, covers(), "off screen, it holds none")
        activation = Activation.OnInteraction
        shown = true
        waitForIdle()
        assertEquals(1 to px * px * 4, covers(), "shown again, it holds the cache's")
        composed = false
        waitForIdle()
        assertEquals(0 to 0L, covers(), "gone from the composition, it holds none")
        assertNoViolations(factory)
    }

    /** Covers stay within the snapshot budget: off screen, a view keeps only its snapshot's key, not its pixels. */
    @Test fun aViewOffScreenHoldsNoSnapshotPixels() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = 50)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        var activation by mutableStateOf<Activation>(Activation.WhenVisible(settleMillis = 0))
        setContent {
            ProvideWebViewRuntime(runtime) {
                // Hidden at 0×0, where the engine has nothing to snapshot: the view's last cover isn't replaced.
                Box(Modifier.size(if (shown) 100.dp else 0.dp)) { WebView(state, Modifier.size(100.dp), activation = activation) }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("a snapshot") { ui { state.snapshotKey != null } }
        val host = assertNotNull(ui { state.owner as? WebViewHost })
        assertNotNull(ui { host.cover }, "on screen, its cover is ready")
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        assertNull(ui { host.cover }, "off screen, it holds no pixels")
        assertNotNull(ui { state.snapshotKey })
        activation = Activation.OnInteraction
        shown = true
        waitForIdle()
        assertNotNull(ui { host.cover }, "shown again, it reads its snapshot back from the cache")
        assertNoViolations(factory)
    }

    // --- Hidden views keep their engine, until a visible view needs it ---

    /**
     * A plain Column scrolls a live embed off screen for 10 s and back: the same engine and the same page, never
     * reloaded or reset, Live throughout, and told it was hidden and shown again.
     */
    @Test fun aHiddenEmbedKeepsItsEngineWhileNobodyNeedsIt() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        val scroll = ScrollState(0)
        val phases = mutableListOf<WebViewPhase>()
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.size(100.dp).verticalScroll(scroll)) {
                    WebView(state, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                    Box(Modifier.size(100.dp, 300.dp))
                }
            }
            LaunchedEffect(state) { snapshotFlow { state.phase }.collect { phases += it } }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        val screen = with(density) { 200.dp.toPx() }
        runOnIdle { scroll.dispatchRawDelta(screen) }
        waitFor("the engine told it's hidden") { "visible:false" in calls(engine) }
        mainClock.advanceTimeBy(10_000)
        assertEquals(WebViewPhase.Live, state.phase, "hidden for 10 s")
        assertEquals(1, ui { runtime.pool.liveCount })
        runOnIdle { scroll.dispatchRawDelta(-screen) }
        waitFor("the engine told it's shown again") { calls(engine).last { it.startsWith("visible:") } == "visible:true" }
        waitForIdle()
        ui {
            assertEquals(1, factory.created.size)
            assertTrue(state.engine === engine, "another engine shows the page")
        }
        val calls = calls(engine)
        assertEquals(1, calls.count { it.startsWith("load") || it.startsWith("restore") }, "$calls")
        assertFalse("reset" in calls, "$calls")
        assertEquals(listOf("visible:true", "visible:false", "visible:true"), calls.filter { it.startsWith("visible:") })
        val seen = ui { phases.toList() }
        assertEquals(listOf(WebViewPhase.Live), seen.dropWhile { it != WebViewPhase.Live }.distinct(), "phases: $seen")
        assertNoViolations(factory)
    }

    /** With room for one engine, a hidden embed gives its engine to a visible one: saved first, then reset for it. */
    @Test fun aHiddenEmbedGivesWayToAVisibleOne() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 1)
        val a = WebViewState(WebContent.Url("https://a.test/"))
        val b = WebViewState(WebContent.Url("https://b.test/"))
        val scroll = ScrollState(0)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.size(100.dp).verticalScroll(scroll)) {
                    WebView(a, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                    WebView(b, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                }
            }
        }
        waitFor("a live") { a.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        runOnIdle { scroll.dispatchRawDelta(with(density) { 100.dp.toPx() }) }
        waitFor("b live") { b.phase == WebViewPhase.Live }
        assertEquals(WebViewPhase.Cold, a.phase)
        assertEquals(1, ui { factory.created.size }, "b got a's engine")
        val calls = calls(engine)
        val saved = calls.indexOf("save")
        assertTrue(saved >= 0 && saved < calls.indexOf("reset"), "a was saved, then reset: $calls")
        assertEquals(listOf("load:${a.content.key}", "load:${b.content.key}"), calls.filter { it.startsWith("load") })
        assertNoViolations(factory)
    }

    /** Nobody needs its engine, but a view hidden for longer than hiddenReleaseMillis still gives it back. */
    @Test fun hiddenReleaseMillisStillReclaims() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = 5_000)
        val state = WebViewState(url)
        val scroll = ScrollState(0)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.size(100.dp).verticalScroll(scroll)) {
                    WebView(state, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                    Box(Modifier.size(100.dp, 300.dp))
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        runOnIdle { scroll.dispatchRawDelta(with(density) { 200.dp.toPx() }) }
        waitFor("the engine told it's hidden") { "visible:false" in calls(engine) }
        mainClock.advanceTimeBy(4_000)
        assertEquals(WebViewPhase.Live, state.phase, "hidden for 4 of 5 s")
        mainClock.advanceTimeBy(2_000)
        waitFor("released") { state.phase == WebViewPhase.Cold && ui { runtime.pool.liveCount } == 0 }
        assertTrue("save" in calls(engine), "${calls(engine)}")
        assertNoViolations(factory)
    }

    // --- A view that leaves the composition parks its live page; the next view of the page adopts it ---

    /**
     * A chat of [count] agent demos in a LazyColumn a message tall: each item is a new view of its page when it shows.
     *
     * Compose keeps the item that scrolled out of a step composed, unplaced, until the list measures without it, at the
     * next step, and then deactivates it, which disposes it: so the tests scroll an item at a time, as a reader does,
     * and an item two steps behind is disposed.
     */
    private class Chat(count: Int) {
        val pages = List(count) { WebContent.Html("<p>demo $it</p>", WebOrigin("demo$it")) }
        val list = LazyListState()

        /** The state each item shows now: a new one each time the item comes back. */
        val states = HashMap<Int, WebViewState>()

        /** What the first demo's page posted to the app as "marker". */
        val markers = mutableListOf<String>()

        @Composable
        fun Show(settings: WebSettings = WebSettings(darkMode = DarkMode.Light), settle: (Int) -> Long = { 0 }) {
            LazyColumn(Modifier.size(100.dp), state = list) {
                items(pages.size, key = { it }) { i ->
                    val state = rememberWebViewState(pages[i])
                    states[i] = state
                    if (i == 0) {
                        DisposableEffect(state) {
                            val stop = state.bridge.on("marker") { markers += it.jsonPrimitive.content }
                            onDispose { stop() }
                        }
                    }
                    WebView(
                        state,
                        Modifier.size(100.dp).testTag("demo-$i"),
                        settings = settings,
                        activation = Activation.WhenVisible(settleMillis = settle(i)),
                        placeholder = { Box(Modifier.fillMaxSize().background(Color.Green)) },
                    )
                }
            }
        }
    }

    /** Scrolls [chat] to [index] an item at a time, letting each step settle. */
    private fun ComposeUiTest.scrollTo(chat: Chat, index: Int) {
        val item = with(density) { 100.dp.toPx() }
        while (true) {
            val now = runOnIdle { chat.list.firstVisibleItemIndex }
            if (now == index) return
            runOnIdle { chat.list.dispatchRawDelta(if (index > now) item else -item) }
        }
    }

    /** Items other than the first wait this long before they go live: only the first demo ever has an engine. */
    private val onlyTheFirst: (Int) -> Long = { if (it == 0) 0 else 600_000 }

    /**
     * Scrolled out, a LazyColumn item is disposed and parks its live page; scrolled back, the new item adopts it: the
     * same engine and document, loaded once, its bridge reconnected, and Live from the first frame it is bound in, never
     * covered or white meanwhile.
     */
    @Test fun aLazyItemScrolledOutAndBackAdoptsItsLivePage() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(color = Color.Red))
        val runtime = testRuntime(factory)
        val chat = Chat(6)
        setContent { ProvideWebViewRuntime(runtime) { chat.Show(settle = onlyTheFirst) } }
        waitFor("the first demo live and talking") { ui { chat.states[0]?.let { it.phase == WebViewPhase.Live && it.bridge.isReady } == true } }
        waitFor("its snapshot") { ui { chat.states[0]?.snapshotKey != null } }
        val engine = engineOf(factory, chat.pages[0])
        val first = ui { chat.states.getValue(0) }
        scrollTo(chat, 3)
        waitFor("the first demo parked") { ui { runtime.pool.parkedCount } == 1 }
        assertEquals(0, ui { runtime.pool.liveCount })
        assertEquals("visible:false", calls(engine).last { it.startsWith("visible:") })
        // Back, the last step a frame at a time: the item shows its page in every frame (its snapshot, then the page),
        // never its placeholder; once bound it is Live, and a frame later the page itself shows.
        scrollTo(chat, 1)
        mainClock.autoAdvance = false
        runOnUiThread { chat.list.dispatchRawDelta(-with(density) { 100.dp.toPx() }) }
        var frames = 0
        var boundAt = -1
        while (true) {
            mainClock.advanceTimeByFrame()
            if (++frames > 100) fail("the item was never bound")
            if (onAllNodesWithTag("demo-0").fetchSemanticsNodes().isEmpty()) continue
            assertEquals(Color.Red, centerColor("demo-0"), "frame $frames")
            val shown = ui {
                val host = chat.states[0]?.owner as? WebViewHost
                if (host?.engine != null) {
                    if (boundAt < 0) boundAt = frames
                    assertTrue(host.painted, "frame $frames: bound, but not Live")
                }
                host?.engine != null && host.coverToDraw(dark = false) == null
            }
            if (shown) break
        }
        assertTrue(frames - boundAt <= 2, "the page showed ${frames - boundAt} frames after it was bound")
        mainClock.autoAdvance = true
        val back = ui { chat.states.getValue(0) }
        assertTrue(back !== first, "the item is a new view, with a new state")
        waitFor("live again") { back.phase == WebViewPhase.Live }
        ui { assertSame<Any?>(engine, back.engine) }
        val calls = calls(engine)
        assertEquals(1, calls.count { it.startsWith("load") }, "$calls")
        assertTrue(calls.none { it.startsWith("restore") || it == "reset" || it == "reload" }, "$calls")
        assertEquals(1, ui { engine.documentsStarted }, "the page was started again")
        assertEquals("visible:true", calls.last { it.startsWith("visible:") })
        // The page itself is the one that was left: what it says reaches the new view's bridge.
        waitFor("the bridge reconnected") { ui { back.bridge.isReady } }
        ui { assertTrue(engine.postFromPage("marker", JsonPrimitive("still here"))) }
        waitFor("the marker heard") { ui { chat.markers.toList() } == listOf("still here") }
        assertNoViolations(factory)
    }

    /**
     * While parked, the page moved within its document (a hash, or pushState): the engine kept that commit for the next
     * subscriber, as every engine does. The view that adopts the page hears it with its bridge already connected, so the
     * page is asked to announce itself (`$hello`), and what it posts next reaches the app.
     */
    @Test fun anAdoptedPageThatMovedWhileParkedStillTalksToTheApp() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val chat = Chat(6)
        setContent { ProvideWebViewRuntime(runtime) { chat.Show(settle = onlyTheFirst) } }
        waitFor("the first demo live and talking") { ui { chat.states[0]?.let { it.phase == WebViewPhase.Live && it.bridge.isReady } == true } }
        val engine = engineOf(factory, chat.pages[0])
        scrollTo(chat, 3)
        waitFor("the first demo parked") { ui { runtime.pool.parkedCount } == 1 }
        val moved = chat.pages[0].origin.url + "#step-2"
        ui { engine.emit(EngineEvent.UrlChanged(moved)) }
        val before = calls(engine).size
        scrollTo(chat, 0)
        waitFor("adopted") { ui { chat.states[0]?.let { it.phase == WebViewPhase.Live && it.engine === engine } == true } }
        waitForIdle()
        val back = ui { chat.states.getValue(0) }
        val since = calls(engine).drop(before)
        assertTrue(since.any { it.startsWith("post:") && "\$hello" in it }, "the page was never asked to announce itself: $since")
        assertTrue(ui { back.bridge.isReady }, "the adopted page's bridge has no document")
        assertEquals(moved, ui { back.url })
        ui { assertTrue(engine.postFromPage("marker", JsonPrimitive("still here"))) }
        waitFor("the marker heard") { ui { chat.markers.toList() } == listOf("still here") }
        assertEquals(1, ui { engine.documentsStarted }, "the page was started again")
        assertNoViolations(factory)
    }

    /**
     * A parked page that floods its bridge past what its engine holds for it goes the way of a crash: the pool lets the
     * engine go, and the item, scrolled back, restores the page on another engine rather than adopt one that said less
     * than it did.
     */
    @Test fun aParkedPageThatFloodsItsBridgeIsRestoredNotAdopted() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val chat = Chat(6)
        setContent { ProvideWebViewRuntime(runtime) { chat.Show(settle = onlyTheFirst) } }
        waitFor("the first demo live and talking") { ui { chat.states[0]?.let { it.phase == WebViewPhase.Live && it.bridge.isReady } == true } }
        val engine = engineOf(factory, chat.pages[0])
        scrollTo(chat, 3)
        waitFor("the first demo parked") { ui { runtime.pool.parkedCount } == 1 }
        ui { repeat(1_100) { engine.postFromPage("tick", JsonPrimitive(it)) } }
        assertFalse(ui { engine.isUsable })
        scrollTo(chat, 0)
        waitFor("live again") { ui { chat.states[0]?.phase } == WebViewPhase.Live }
        val back = ui { chat.states[0]?.engine }
        assertTrue(back !== engine, "the flooded page was adopted")
        assertTrue(ui { engine.released }, "the flooded engine was kept")
        assertTrue(calls(back as FakeWebEngine).any { it == "restore:fake:${chat.pages[0].key}" }, "${calls(back)}")
        assertNoViolations(factory)
    }

    /**
     * Two engines go round a long list, so the first item's parked page is reclaimed for the items below it: back at
     * the top, the item restores its page on another engine, under its snapshot, never white or the placeholder.
     */
    @Test fun aLazyItemBeyondTheBudgetReloadsUnderItsCover() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(color = Color.Red))
        val runtime = testRuntime(factory, maxLive = 2)
        val chat = Chat(8)
        setContent { ProvideWebViewRuntime(runtime) { chat.Show() } }
        waitFor("the first demo live and snapshotted") {
            ui { chat.states[0]?.let { it.phase == WebViewPhase.Live && it.snapshotKey != null } == true }
        }
        val first = engineOf(factory, chat.pages[0])
        for (i in 1..4) {
            scrollTo(chat, i)
            waitFor("demo $i live") { ui { chat.states[i]?.phase } == WebViewPhase.Live }
        }
        assertTrue("reset" in calls(first), "the first demo's parked page was never reclaimed: ${calls(first)}")
        // The engines paint only when told now, so the cover has frames to hide.
        ui {
            factory.behavior = factory.behavior.copy(paintsOnLoad = false)
            factory.created.forEach { it.behavior = it.behavior.copy(paintsOnLoad = false) }
        }
        scrollTo(chat, 1)
        mainClock.autoAdvance = false
        runOnUiThread { chat.list.dispatchRawDelta(-with(density) { 100.dp.toPx() }) }
        var restoring: FakeWebEngine? = null
        for (frame in 0 until 60) {
            mainClock.advanceTimeByFrame()
            if (onAllNodesWithTag("demo-0").fetchSemanticsNodes().isEmpty()) continue
            assertEquals(Color.Red, centerColor("demo-0"), "frame $frame")
            restoring = ui { factory.created.lastOrNull { engine -> engine.calls.any { it == "restore:fake:${chat.pages[0].key}" } } }
            if (restoring != null) break
        }
        val engine = assertNotNull(restoring, "the first demo never restored")
        repeat(5) {
            mainClock.advanceTimeByFrame()
            assertEquals(Color.Red, centerColor("demo-0"), "restoring, frame $it")
        }
        assertEquals(WebViewPhase.Activating, ui { chat.states[0]?.phase })
        ui { engine.emit(EngineEvent.FirstPaint) }
        mainClock.autoAdvance = true
        waitFor("live again") { ui { chat.states[0]?.phase } == WebViewPhase.Live }
        assertEquals(Color.Red, centerColor("demo-0"))
        assertNoViolations(factory)
    }

    /** The theme changed while the page was parked: the new item adopts it, and configures it again, mid-lease. */
    @Test fun aReconfiguredItemAdoptsAndReconfigures() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val chat = Chat(6)
        var dark by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                chat.Show(settings = WebSettings(darkMode = if (dark) DarkMode.Dark else DarkMode.Light), settle = onlyTheFirst)
            }
        }
        waitFor("the first demo live") { ui { chat.states[0]?.phase } == WebViewPhase.Live }
        val engine = engineOf(factory, chat.pages[0])
        assertEquals(false, ui { engine.lastSetup?.dark })
        scrollTo(chat, 3)
        waitFor("the first demo parked") { ui { runtime.pool.parkedCount } == 1 }
        dark = true
        scrollTo(chat, 0)
        waitFor("live again") { ui { chat.states[0]?.let { it.phase == WebViewPhase.Live && it.engine === engine } == true } }
        val calls = calls(engine)
        assertEquals(1, calls.count { it.startsWith("load") }, "$calls")
        assertEquals(2, calls.count { it == "configure" }, "$calls")
        assertTrue(calls.none { it == "reset" || it.startsWith("restore") }, "$calls")
        assertEquals(true, ui { engine.lastSetup?.dark })
        assertNoViolations(factory)
    }

    /**
     * A parked page the pool reclaims for another view saves what it has then, not only what it had when its view left:
     * the next view of it gets that back.
     */
    @Test fun aReclaimedParkedPageSavesWhatItHasNow() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("when it left")))
        val runtime = testRuntime(factory, maxLive = 1)
        val chat = Chat(6)
        // The items below wait a second before they go live: the parked page changes meanwhile.
        setContent { ProvideWebViewRuntime(runtime) { chat.Show(settle = { if (it == 0) 0 else 1_000 }) } }
        waitFor("the first demo live") { ui { chat.states[0]?.phase } == WebViewPhase.Live }
        val engine = engineOf(factory, chat.pages[0])
        scrollTo(chat, 3)
        waitFor("the first demo parked") { ui { runtime.pool.parkedCount } == 1 }
        val page = PageKey(WebProfile.Ephemeral, chat.pages[0].key)
        assertEquals(SavedPageState("\"when it left\"", chat.pages[0].origin.origin), ui { runtime.pages.recall(page)?.pageState })
        ui { engine.behavior = engine.behavior.copy(pageState = JsonPrimitive("now")) }
        mainClock.advanceTimeBy(1_500)
        // A demo below goes live on the one engine there is: the parked page is reclaimed for it.
        waitFor("reclaimed") { "reset" in calls(engine) && ui { runtime.pool.liveCount } == 1 }
        assertEquals(SavedPageState("\"now\"", chat.pages[0].origin.origin), ui { runtime.pages.recall(page)?.pageState })
        assertNoViolations(factory)
    }

    /**
     * The same when the page moved within its document while parked, so its engine holds that commit for the next
     * subscriber: the reclaim's own bridge hears it already connected, asks the page to announce itself, and saves what
     * the page has now.
     */
    @Test fun aReclaimedPageThatMovedWhileParkedSavesWhatItHasNow() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("when it left")))
        val runtime = testRuntime(factory, maxLive = 1)
        val chat = Chat(6)
        setContent { ProvideWebViewRuntime(runtime) { chat.Show(settle = { if (it == 0) 0 else 1_000 }) } }
        waitFor("the first demo live") { ui { chat.states[0]?.phase } == WebViewPhase.Live }
        val engine = engineOf(factory, chat.pages[0])
        scrollTo(chat, 3)
        waitFor("the first demo parked") { ui { runtime.pool.parkedCount } == 1 }
        val page = PageKey(WebProfile.Ephemeral, chat.pages[0].key)
        ui {
            engine.emit(EngineEvent.UrlChanged(chat.pages[0].origin.url + "#step-2"))
            engine.behavior = engine.behavior.copy(pageState = JsonPrimitive("now"))
        }
        mainClock.advanceTimeBy(1_500)
        waitFor("reclaimed") { "reset" in calls(engine) && ui { runtime.pool.liveCount } == 1 }
        assertEquals(SavedPageState("\"now\"", chat.pages[0].origin.origin), ui { runtime.pages.recall(page)?.pageState })
        assertNoViolations(factory)
    }

    /** A logout: no view adopts the last user's parked page, nor does content that is gone come back live. */
    @Test fun aClearOrAForgetGivesParkedPagesBackUnsaved() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxIdle = 2)
        val chat = Chat(6)
        setContent { ProvideWebViewRuntime(runtime) { chat.Show(settle = onlyTheFirst) } }
        waitFor("the first demo live") { ui { chat.states[0]?.phase } == WebViewPhase.Live }
        val engine = engineOf(factory, chat.pages[0])
        scrollTo(chat, 3)
        waitFor("the first demo parked") { ui { runtime.pool.parkedCount } == 1 }
        ui { runtime.forget(chat.pages[0]) }
        waitFor("given back") { ui { runtime.pool.parkedCount } == 0 }
        assertTrue("reset" in calls(engine), "${calls(engine)}")
        assertNull(ui { runtime.pages.recall(PageKey(WebProfile.Ephemeral, chat.pages[0].key)) }, "a forgotten page saved again")
        scrollTo(chat, 0)
        waitFor("the first demo live again") { ui { chat.states[0]?.phase } == WebViewPhase.Live }
        val loads = ui { factory.created.sumOf { e -> e.calls.count { it == "load:${chat.pages[0].key}" } } }
        assertEquals(2, loads, "the forgotten page was adopted instead of loaded")
        // The same for every page at a clear.
        scrollTo(chat, 3)
        waitFor("parked again") { ui { runtime.pool.parkedCount } == 1 }
        ui { runtime.clear() }
        waitFor("given back again") { ui { runtime.pool.parkedCount } == 0 }
        assertNoViolations(factory)
    }

    /**
     * A LazyColumn keeps the items it measured on its way to the new first one composed, live, but places only those
     * on screen, until it measures without them. Two items that scrolled out at once stay so: they count as hidden,
     * told so and ranked lowest, so a view that scrolls in takes one of their engines (saved, then reset for it) and
     * never the engine of a view still on screen. With room for four engines, the two that came in take the free slot
     * and one retained item's engine; the other retained item keeps its page.
     */
    @Test fun aLazyListsRetainedButUnplacedItemsCountAsHidden() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 4)
        val pages = List(8) { WebContent.Html("<p>item $it</p>", WebOrigin("item$it")) }
        val list = LazyListState()
        val states = HashMap<Int, WebViewState>()
        setContent {
            ProvideWebViewRuntime(runtime) {
                // Three items on screen at once.
                LazyColumn(Modifier.size(100.dp, 150.dp), state = list) {
                    items(pages.size, key = { it }) { i ->
                        val state = rememberWebViewState(pages[i])
                        states[i] = state
                        // The items that scroll in wait a second: the test sees first what those that left report.
                        val settle = if (i < 3) 0L else 1_000L
                        val activation = Activation.WhenVisible(settleMillis = settle)
                        WebView(state, Modifier.size(100.dp, 50.dp), activation = activation)
                    }
                }
            }
        }
        waitFor("the first three live") { ui { (0..2).all { states[it]?.phase == WebViewPhase.Live } } }
        val retained = (0..1).map { engineOf(factory, pages[it]) }
        val stays = engineOf(factory, pages[2])
        // Two items scroll out at once: composed still, but no longer placed.
        runOnIdle { list.dispatchRawDelta(with(density) { 100.dp.toPx() }) }
        for (engine in retained) {
            waitFor("engine ${engine.id} told it's hidden") {
                calls(engine).lastOrNull { it.startsWith("visible:") } == "visible:false"
            }
        }
        ui {
            for (i in 0..1) {
                val host = assertNotNull(states[i]?.owner as? WebViewHost, "item $i")
                assertFalse(host.disposed, "item $i was disposed, not retained")
                assertNotNull(host.engine, "item $i")
                assertEquals(PRIORITY_HIDDEN, host.priority, "item $i")
            }
            assertEquals(3, runtime.pool.liveCount)
            assertEquals(0, runtime.pool.parkedCount)
        }
        assertEquals("visible:true", calls(stays).last { it.startsWith("visible:") })
        // Settled, the two that scrolled in go live: one in the free slot, the other on a retained item's engine, which
        // saves its page first. The view still on screen keeps its own.
        mainClock.advanceTimeBy(1_000)
        waitFor("the items on screen live") { ui { (2..4).all { states[it]?.phase == WebViewPhase.Live } } }
        assertEquals(4, ui { factory.created.size })
        val taken = retained.filter { "reset" in calls(it) }
        assertEquals(1, taken.size, "the retained items' engines: ${retained.map { calls(it) }}")
        val given = calls(taken.single())
        val saved = given.indexOf("save")
        assertTrue(saved >= 0 && saved < given.indexOf("reset"), "saved, then reset: $given")
        val taker = ui { (3..4).single { states[it]?.engine === taken.single() } }
        assertTrue("load:${pages[taker].key}" in given, "$given")
        val kept = calls((retained - taken.toSet()).single())
        assertEquals(1, kept.count { it.startsWith("load") }, "the other retained item kept its page: $kept")
        assertFalse("reset" in kept, "$kept")
        val onScreen = calls(stays)
        assertTrue(onScreen.none { it == "save" || it == "reset" }, "the view on screen gave its engine: $onScreen")
        ui { assertSame<Any?>(stays, states[2]?.engine) }
        assertNoViolations(factory)
    }

    // --- Sharp snapshots: a chat keeps a picture of every page it has shown, as sharp as the page ---

    /**
     * A chat on a phone, as the Fold's cover screen shows it: 360 × 280 dp embeds at density 2.625, two live engines,
     * and the phone's 32 MB of snapshots. After 40 pages, every picture is still in the cache, each at the screen's
     * density and in full colour (2.8 MB as pixels, so 11 would fit as pixels), stored encoded.
     */
    @Test fun aPhoneChatKeepsASharpPictureOfEveryPageItShowed() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 2, snapshotCacheBytes = 32L shl 20)
        val pages = List(41) { WebContent.Html("<p>demo $it</p>", WebOrigin("demo$it")) }
        val list = LazyListState()
        val states = HashMap<Int, WebViewState>()
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
                ProvideWebViewRuntime(runtime) {
                    LazyColumn(Modifier.size(360.dp, 280.dp), state = list) {
                        items(pages.size, key = { it }) { i ->
                            val state = rememberWebViewState(pages[i])
                            states[i] = state
                            WebView(state, Modifier.size(360.dp, 280.dp), activation = Activation.WhenVisible(settleMillis = 0))
                        }
                    }
                }
            }
        }
        for (i in 0 until 40) {
            if (i > 0) runOnIdle { list.dispatchRawDelta(280f * 2.625f) }
            waitFor("page $i snapshotted") { ui { states[i]?.snapshotKey != null } }
        }
        val kept = ui { (0 until 40).count { i -> states[i]?.snapshotKey?.let { it in runtime.snapshots } == true } }
        println("sharp snapshots: $kept of 40 pictures kept, in ${ui { runtime.snapshots.sizeBytes }} bytes stored")
        assertEquals(40, kept, "$kept of 40 pictures kept")
        ui {
            val key = assertNotNull(states[39]?.snapshotKey)
            assertEquals(945, key.width, "the screen's density: 360 dp at 2.625")
            assertEquals(ImageBitmapConfig.Argb8888, assertNotNull(runtime.snapshots.request(key)).config)
        }
        assertNoViolations(factory)
    }

    // --- A snapshot that copies the screen takes one only with nothing marked over the page ---

    /**
     * An engine Compose draws over is never told its cut-outs, but before each snapshot it hears whether anything the
     * app marked is over the page: an engine whose snapshot copies the screen may take one only when nothing is.
     */
    @Test fun anEngineComposeDrawsOverHearsWhatIsOverThePageBeforeEachSnapshot() = runComposeUiTest {
        val fake = FakeWebEngineFactory(behavior = FakeEngineBehavior(needsCutouts = false))
        val heard = mutableListOf<String>()
        val runtime = testRuntime(
            WebEngineFactory { config ->
                val engine = (fake.create(config) as WebEngineResult.Created).engine as FakeWebEngine
                WebEngineResult.Created(
                    object : WebEngine by engine {
                        override fun overlaysAtSnapshot(cutouts: OverlayCutouts) {
                            heard += if (cutouts.isNone) "nothing over" else "something over"
                        }

                        override suspend fun snapshot(): ImageBitmap? {
                            heard += "snapshot"
                            return engine.snapshot()
                        }

                        @Composable
                        override fun Surface(modifier: Modifier) = engine.Surface(modifier)
                    },
                )
            },
        )
        val state = WebViewState(url)
        var menu by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(200.dp)) {
                    WebView(state, Modifier.fillMaxSize().testTag("view"), activation = Activation.Eager)
                    if (menu) Box(Modifier.offset(50.dp, 50.dp).size(80.dp).webViewOverlay())
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("the settle snapshot") { ui { "snapshot" in heard } }
        assertEquals(listOf("something over", "snapshot"), ui { heard.toList() })
        menu = false
        waitForIdle()
        onNodeWithTag("view").performClick()
        mainClock.advanceTimeBy(600)
        waitFor("the snapshot after the tap") { ui { heard.count { it == "snapshot" } } == 2 }
        assertEquals(listOf("something over", "snapshot", "nothing over", "snapshot"), ui { heard.toList() })
        assertTrue(calls(ui { fake.created.single() }).none { it.startsWith("overlays:") }, "told its cut-outs")
        assertNoViolations(fake)
    }

    // --- The client ---

    @Test fun openHereLoadsWhatThePolicyAllowsHereAndOpensTheRestExternally() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(notes)
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        val client = object : WebClient {
            override fun onNewWindow(request: NewWindowRequest): NewWindowDecision = NewWindowDecision.OpenHere
        }
        setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp), client = client) }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engineClient = assertNotNull(ui { factory.created.single().client })
        // Its own origin: here. Another site: the policy sends it out. Without a gesture it may not leave.
        assertEquals(NewWindowDecision.OpenHere, engineClient.onNewWindow(NewWindowRequest(notes.origin.url + "page", isUserGesture = true)))
        assertEquals(NewWindowDecision.OpenExternally, engineClient.onNewWindow(NewWindowRequest("https://elsewhere.test/", isUserGesture = true)))
        assertEquals(NewWindowDecision.Block, engineClient.onNewWindow(NewWindowRequest("https://elsewhere.test/b", isUserGesture = false)))
        waitFor("opened") { ui { opened.size } == 1 }
        waitForIdle()
        assertEquals(listOf("https://elsewhere.test/"), ui { opened.toList() })
        assertNoViolations(factory)
    }

    // --- Page memory ---

    @Test fun aSwitchToAnotherConfigRemembersTheOldPageUnderItsOwnKey() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val demo = WebContent.Html("<p>demo</p>", WebOrigin("demo"))
        runOnIdle { state.load(demo) }
        waitFor("the demo live") { ui { factory.created.size } == 2 && state.phase == WebViewPhase.Live }
        val memory = assertNotNull(ui { runtime.pages.recall(PageKey(WebProfile.Default, url.key)) })
        val key = assertNotNull(memory.snapshotKey)
        assertEquals(url.key, key.contentKey)
        assertNotNull(ui { runtime.snapshots[key] })
        assertEquals("fake:${url.key}", memory.engineState?.decodeToString())
        // Never into the state, which shows the demo now.
        ui {
            assertTrue(state.snapshotKey.let { it == null || it.contentKey == demo.key }, "${state.snapshotKey}")
            assertTrue(state.savedEngineState.let { it == null || it.decodeToString() == "fake:${demo.key}" })
        }
        assertNoViolations(factory)
    }

    // --- The pool's holder contract, and per-lease state ---

    @Test fun anEngineGivenBackLeavesNoClientBehind() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = 50)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(if (shown) 100.dp else 0.dp)) {
                    WebView(state, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        assertNotNull(ui { engine.client })
        shown = false
        waitFor("given back") { ui { runtime.pool.liveCount } == 0 }
        assertNull(ui { engine.client }, "the view's client stays with an engine it no longer holds")
        assertNoViolations(factory)
    }

    /** A page that never moves is cut out at its real bounds from the start: nothing re-lays it out after it binds. */
    @Test fun aPageThatNeverMovesIsCutOutAtItsRealBoundsFromTheStart() = runComposeUiTest {
        val fake = FakeWebEngineFactory()
        val engines = mutableListOf<Recording>()
        val runtime = testRuntime(
            WebEngineFactory { config ->
                WebEngineResult.Created(Recording((fake.create(config) as WebEngineResult.Created).engine as FakeWebEngine).also { engines += it })
            },
        )
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("cut-outs") { ui { engines.single().applied.isNotEmpty() } }
        val side = with(density) { 100.dp.toPx() }
        val first = ui { engines.single().applied.first() }
        assertEquals(Rect(0f, 0f, side, side), first.visibleBounds)
        assertTrue(first.isNone)
        assertNoViolations(fake)
    }

    /** Each document is heard once: a navigation replaces the event subscription, it doesn't add one. */
    @Test fun aNewDocumentIsHeardOnce() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        runOnIdle { state.navigator.load("https://example.test/next") }
        waitFor("the next page live") { state.url == "https://example.test/next" && state.phase == WebViewPhase.Live }
        val heard = mutableListOf<ConsoleMessage>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            state.console.collect { heard += it }
        }
        ui { engine.emit(EngineEvent.Console(ConsoleMessage(ConsoleLevel.Log, "once"))) }
        waitFor("heard") { ui { heard.size } >= 1 }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals(1, ui { heard.size })
        job.cancel()
        assertNoViolations(factory)
    }

    @Test fun aDeniedViewThatScrollsAwayStopsListening() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 1)
        val pane = WebViewState(WebContent.Url("https://pane.test/"))
        val embed = WebViewState(WebContent.Url("https://embed.test/"))
        var embedShown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    WebView(pane, Modifier.size(100.dp), activation = Activation.Eager)
                    Box(Modifier.size(if (embedShown) 100.dp else 0.dp)) {
                        WebView(embed, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                    }
                }
            }
        }
        waitFor("the embed waiting") { pane.phase == WebViewPhase.Live && ui { runtime.pool.capacityListenerCount } == 1 }
        embedShown = false
        waitFor("not listening") { ui { runtime.pool.capacityListenerCount } == 0 }
        assertNoViolations(factory)
    }

    /** A pane that stops being Eager ranks lower at once: the pool hears of it, and an Eager view waiting gets in. */
    @Test fun aPaneThatStopsBeingEagerLetsAnEagerViewIn() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 1)
        val pane = WebViewState(WebContent.Url("https://pane.test/"))
        val other = WebViewState(WebContent.Url("https://other.test/"))
        var paneActivation by mutableStateOf<Activation>(Activation.Eager)
        var otherShown by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    WebView(pane, Modifier.size(100.dp), activation = paneActivation)
                    if (otherShown) WebView(other, Modifier.size(100.dp), activation = Activation.Eager)
                }
            }
        }
        waitFor("the pane live") { pane.phase == WebViewPhase.Live }
        otherShown = true
        waitFor("the other waiting") { ui { runtime.pool.capacityListenerCount } == 1 }
        paneActivation = Activation.WhenVisible(settleMillis = 0)
        waitFor("the other live") { other.phase == WebViewPhase.Live }
        assertEquals(WebViewPhase.Cold, pane.phase)
        assertNoViolations(factory)
    }

    /** Focus moving to another page lowers the one that had it: the pool hears of it, and a waiting view gets in. */
    @Test fun focusMovingToAnotherPageLetsADeniedViewIn() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 2)
        val a = WebContent.Url("https://a.test/")
        val b = WebContent.Url("https://b.test/")
        val stateA = WebViewState(a)
        val stateB = WebViewState(b)
        val stateC = WebViewState(WebContent.Url("https://c.test/"))
        var cShown by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    WebView(stateA, Modifier.size(100.dp), activation = Activation.Eager)
                    WebView(stateB, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                    if (cShown) WebView(stateC, Modifier.size(100.dp), activation = Activation.Eager)
                }
            }
        }
        waitFor("a and b live") { stateA.phase == WebViewPhase.Live && stateB.phase == WebViewPhase.Live }
        // b's page takes focus: it outranks everything, and c (Eager) finds nobody to evict.
        ui { engineOf(factory, b).emit(EngineEvent.FocusChanged(true)) }
        waitFor("b focused") { ui { (runtime.focused as? WebViewHost)?.state === stateB } }
        cShown = true
        waitFor("c waiting") { ui { runtime.pool.capacityListenerCount } == 1 }
        // a's page takes it: b is a plain visible embed again, below c.
        ui { engineOf(factory, a).emit(EngineEvent.FocusChanged(true)) }
        waitFor("c live") { stateC.phase == WebViewPhase.Live }
        assertEquals(WebViewPhase.Cold, stateB.phase)
        assertEquals(WebViewPhase.Live, stateA.phase)
        assertNoViolations(factory)
    }

    /** Among views of one rank, the one used least recently goes first: using a page counts, not just leasing it. */
    @Test fun theViewUsedLastIsEvictedLast() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 2)
        val a = WebViewState(WebContent.Url("https://a.test/"))
        val b = WebViewState(WebContent.Url("https://b.test/"))
        val c = WebViewState(WebContent.Url("https://c.test/"))
        var bShown by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    WebView(a, Modifier.size(100.dp).testTag("a"), activation = Activation.WhenVisible(settleMillis = 0))
                    if (bShown) WebView(b, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                    WebView(c, Modifier.size(100.dp).testTag("c"), activation = Activation.OnInteraction)
                }
            }
        }
        waitFor("a live") { a.phase == WebViewPhase.Live }
        bShown = true
        waitFor("b live") { b.phase == WebViewPhase.Live }
        // a was leased first, but used since b was.
        onNodeWithTag("a").performClick()
        waitForIdle()
        // c takes focus (a is a plain embed again) and needs an engine: b, used least recently, gives way.
        onNodeWithTag("c").performClick()
        waitFor("c live") { c.phase == WebViewPhase.Live }
        assertEquals(WebViewPhase.Cold, b.phase)
        assertEquals(WebViewPhase.Live, a.phase)
        assertNoViolations(factory)
    }

    @Test fun theFirstPaintPrewarmsASpare() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxIdle = 1)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("a spare") { ui { runtime.pool.idleCount } == 1 }
        assertEquals(2, ui { factory.created.size })
        assertNoViolations(factory)
    }

    @Test fun anOnInteractionViewThatWentColdWaitsForAnotherTap() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = 50)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(100.dp).clipToBounds()) {
                    WebView(
                        state,
                        Modifier.size(100.dp).offset(y = if (shown) 0.dp else 200.dp).testTag("view"),
                        activation = Activation.OnInteraction,
                    )
                }
            }
        }
        onNodeWithTag("view").performClick()
        waitFor("live") { state.phase == WebViewPhase.Live }
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        shown = true
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        assertEquals(WebViewPhase.Cold, state.phase)
        assertEquals(1, ui { factory.created.size })
        assertNoViolations(factory)
    }

    /** A save provider is a document's: a later document that registers none isn't asked, and the state is kept. */
    @Test fun aDocumentWithoutASaveProviderIsNotAskedForItsState() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("draft")))
        val runtime = testRuntime(factory, hiddenReleaseMillis = 50)
        val state = WebViewState(notes)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(if (shown) 100.dp else 0.dp)) {
                    WebView(state, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        val saved = SavedPageState("\"draft\"", notes.origin.origin)
        assertEquals(saved, ui { state.pageState })
        // The next engine's page no longer registers a save provider.
        ui { factory.behavior = FakeEngineBehavior() }
        shown = true
        waitFor("live again") { state.phase == WebViewPhase.Live && ui { factory.created.size } == 2 }
        val second = ui { factory.created.last() }
        waitFor("the state delivered") { calls(second).any { it.startsWith("post:") && "\$restoreState" in it } }
        shown = false
        waitFor("cold again") { state.phase == WebViewPhase.Cold }
        assertTrue(calls(second).none { "\$saveState" in it }, "${calls(second)}")
        assertEquals(saved, ui { state.pageState })
        assertNoViolations(factory)
    }

    /** A view that goes away while its engine is on its way gives the engine back instead of binding it. */
    @Test fun aViewDisposedWhileItsEngineIsOnItsWayGivesItBack() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp), activation = Activation.Eager) } }
        mainClock.advanceTimeBy(200)
        val host = assertNotNull(ui { state.owner as? WebViewHost }, "the view took the state before leasing")
        // Disposed while its own coroutine still runs, as when the composition goes and the effect hasn't been cancelled yet.
        ui { host.dispose() }
        ui {
            factory.createGate = null
            gate.complete(Unit)
        }
        waitFor("created") { ui { factory.created.size } == 1 }
        waitForIdle()
        mainClock.advanceTimeBy(200)
        assertEquals(0, ui { runtime.pool.liveCount })
        assertTrue(calls(ui { factory.created.single() }).none { it == "configure" || it.startsWith("load") })
        assertNoViolations(factory)
    }

    // --- Task 16b: a load detaches the bridge, profiles are a boundary, one engine per state ---

    /** What the old page says between a load() and the new document's start lands nowhere, least of all in the state. */
    @Test fun whatTheOldPageSaysAfterALoadIsNotHeard() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        val next = WebContent.Url("https://example.test/next")
        runOnIdle {
            // Emitted by the old page just before the app loads other content; heard only after the load.
            engine.emit(EngineEvent.TitleChanged("the old page"))
            engine.emit(EngineEvent.ContentSize(300f, 777f))
            state.load(next)
        }
        waitFor("the next page live") { state.url == next.url && state.phase == WebViewPhase.Live }
        waitForIdle()
        ui {
            assertNull(state.title)
            assertNull(state.contentSize)
        }
        assertNoViolations(factory)
    }

    /** The same engine shows the next URL: what the app sends right after the load goes to the next page only. */
    @Test fun whatIsSentRightAfterALoadReachesOnlyTheNewDocument() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val a = WebContent.Url("https://a.test/")
        val b = WebContent.Url("https://b.test/")
        val state = WebViewState(a)
        val settings = WebSettings(bridgeOrigins = setOf("https://a.test", "https://b.test"))
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp), settings = settings) } }
        waitFor("live and ready") { state.phase == WebViewPhase.Live && ui { state.bridge.isReady } }
        val engine = ui { factory.created.single() }
        var call: kotlinx.coroutines.Deferred<Result<kotlinx.serialization.json.JsonElement>>? = null
        runOnIdle {
            state.load(b)
            assertTrue(state.bridge.post("token", JsonPrimitive("b's token")))
            call = runtime.scope.async { runCatching { state.bridge.call("hello", JsonPrimitive("b's question")) } }
        }
        waitFor("b live and ready") { state.url == b.url && state.phase == WebViewPhase.Live && ui { state.bridge.isReady } }
        waitFor("both sent") { calls(engine).count { it.startsWith("post:") && "b's" in it } == 2 }
        val calls = calls(engine)
        val loadB = calls.indexOf("load:${b.key}")
        val toB = calls.withIndex().filter { (_, c) -> c.startsWith("post:") && "b's" in c }.map { it.index }
        assertTrue(loadB >= 0 && toB.all { it > loadB }, "b's messages reached a's document: $calls")
        assertEquals(1, ui { factory.created.size })
        // The fake's page answers no calls: this one went to b's document, and waits there for its timeout.
        ui { call?.cancel() }
        assertNoViolations(factory)
    }

    /** The new content needs another engine: what the app sent right after the load waits for it, on that engine. */
    @Test fun whatIsSentRightAfterALoadFollowsTheContentToItsNewEngine() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val demo = WebContent.Html("<p>demo</p>", WebOrigin("demo"))
        runOnIdle {
            state.load(demo)
            assertTrue(state.bridge.post("config", JsonPrimitive("the demo's")))
        }
        waitFor("the demo live and ready") { ui { factory.created.size } == 2 && state.phase == WebViewPhase.Live && ui { state.bridge.isReady } }
        val (first, second) = ui { factory.created.toList() }
        waitFor("delivered") { calls(second).any { it.startsWith("post:") && "the demo's" in it } }
        assertTrue(calls(first).none { "the demo's" in it }, "${calls(first)}")
        assertNoViolations(factory)
    }

    /**
     * Page memory is per profile: the same content's draft goes back only to a view of it in the same profile. With one
     * engine, each profile's parked page is reclaimed for the other's view: never adopted by it.
     */
    @Test fun aPageIsRememberedPerProfile() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("alice's draft")))
        val runtime = testRuntime(factory, maxLive = 1)
        var profile by mutableStateOf<WebProfile?>(WebProfile.Named("alice"))
        var state: WebViewState? = null
        setContent {
            ProvideWebViewRuntime(runtime) {
                val p = profile
                if (p != null) key(p) { WebView(rememberWebViewState(notes).also { state = it }, Modifier.size(100.dp), settings = WebSettings(profile = p)) }
            }
        }
        waitFor("alice live") { state?.phase == WebViewPhase.Live }
        profile = null
        waitFor("alice's engine given back") { ui { runtime.pool.liveCount } == 0 }
        assertNotNull(ui { runtime.pages.recall(PageKey(WebProfile.Named("alice"), notes.key))?.pageState })
        ui { factory.behavior = FakeEngineBehavior() }
        profile = WebProfile.Named("bob")
        waitFor("bob live") { state?.phase == WebViewPhase.Live && ui { factory.created.size } == 2 }
        val bob = ui { factory.created.last() }
        assertTrue(calls(bob).none { it.startsWith("restore:") || "alice" in it }, "${calls(bob)}")
        profile = null
        waitFor("bob's engine given back") { ui { runtime.pool.liveCount } == 0 }
        profile = WebProfile.Named("alice")
        waitFor("alice live again") { state?.phase == WebViewPhase.Live && ui { factory.created.size } == 3 }
        val alice = ui { factory.created.last() }
        waitFor("alice's draft back") { calls(alice).any { it.startsWith("post:") && "alice's draft" in it } }
        assertTrue(calls(alice).any { it.startsWith("restore:") })
        assertNoViolations(factory)
    }

    /** A cold view whose profile changes doesn't show the old profile's snapshot. */
    @Test fun aColdViewShowsNoSnapshotOfAnotherProfile() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(color = Color.Red))
        val runtime = testRuntime(factory, hiddenReleaseMillis = 50)
        val state = WebViewState(url)
        var activation by mutableStateOf<Activation>(Activation.WhenVisible(settleMillis = 0))
        var shown by mutableStateOf(true)
        var settings by mutableStateOf(WebSettings(profile = WebProfile.Named("alice")))
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(100.dp).clipToBounds()) {
                    WebView(
                        state,
                        Modifier.size(100.dp).offset(y = if (shown) 0.dp else 200.dp).testTag("view"),
                        settings = settings,
                        activation = activation,
                        placeholder = { Box(Modifier.fillMaxSize().background(Color.Green)) },
                    )
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("a snapshot") { ui { state.snapshotKey != null } }
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        activation = Activation.OnInteraction
        shown = true
        waitForIdle()
        assertEquals(Color.Red, centerColor("view"), "alice's snapshot covers alice's page")
        settings = WebSettings(profile = WebProfile.Named("bob"))
        waitForIdle()
        assertEquals(Color.Green, centerColor("view"), "bob's view shows alice's snapshot")
        settings = WebSettings(profile = WebProfile.Named("alice"))
        waitForIdle()
        assertEquals(Color.Red, centerColor("view"))
        assertNoViolations(factory)
    }

    /**
     * A logout: after a clear, nothing of the last user's pages shows or comes back, not even what a state the app still
     * holds remembers, or a page live at the clear: that one saves nothing after it, and its view starts it afresh.
     */
    @Test fun afterAClearNothingOfTheLastUserShowsOrComesBack() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("the last user's draft"), color = Color.Red))
        val runtime = testRuntime(factory, hiddenReleaseMillis = 50)
        val state = WebViewState(notes)
        val other = WebViewState(WebContent.Html("<p>other</p>", WebOrigin("other")))
        var activation by mutableStateOf<Activation>(Activation.WhenVisible(settleMillis = 0))
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    Box(Modifier.size(100.dp).clipToBounds()) {
                        WebView(
                            state,
                            Modifier.size(100.dp).offset(y = if (shown) 0.dp else 200.dp).testTag("view"),
                            activation = activation,
                            placeholder = { Box(Modifier.fillMaxSize().background(Color.Green)) },
                        )
                    }
                    WebView(other, Modifier.size(100.dp), activation = Activation.Eager)
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live && other.phase == WebViewPhase.Live }
        waitFor("a snapshot") { ui { state.snapshotKey != null } }
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        assertNotNull(ui { state.pageState })
        activation = Activation.OnInteraction
        shown = true
        waitForIdle()
        assertEquals(Color.Red, centerColor("view"))
        // The user logs out while the other page is live.
        val live = ui { other.engine as FakeWebEngine }
        val saves = calls(live).count { it == "save" }
        ui { runtime.clear() }
        waitForIdle()
        assertEquals(Color.Green, centerColor("view"), "the last user's snapshot still shows")
        // The page live at the clear went back unsaved, and its view started it afresh on another engine.
        waitFor("the other page started afresh") { other.phase == WebViewPhase.Live && ui { other.engine } !== live }
        assertEquals(saves, calls(live).count { it == "save" }, "the last user's live page saved after the clear")
        assertNull(ui { runtime.pages.recall(PageKey(WebProfile.Ephemeral, other.content.key)) })
        val restarted = ui { other.engine as FakeWebEngine }
        assertTrue(calls(restarted).none { it.startsWith("restore:") }, "${calls(restarted)}")
        ui { factory.behavior = FakeEngineBehavior(color = Color.Red) }
        onNodeWithTag("view").performClick()
        waitFor("live again") { state.phase == WebViewPhase.Live }
        val next = ui { state.engine as FakeWebEngine }
        assertTrue(calls(next).none { it.startsWith("restore:") || "draft" in it }, "${calls(next)}")
        assertNoViolations(factory)
    }

    /**
     * A logout while a plugin's page is live in a tab the user isn't on (composed, hidden, keeping its engine): the page
     * is the last user's, so its engine goes back unsaved, and the tab, shown again, loads the plugin afresh, never the
     * page that was live across the clear.
     */
    @Test fun aClearTakesTheLastUsersPageFromAHiddenTabToo() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("the last user's draft")))
        val runtime = testRuntime(factory)
        val plugin = WebContent.Bundle(WebOrigin("plugin"), WebResourceProvider { null })
        val state = WebViewState(plugin)
        var onTab by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(100.dp).clipToBounds()) {
                    WebView(
                        state,
                        Modifier.size(100.dp).offset(x = if (onTab) 0.dp else 200.dp),
                        activation = Activation.WhenVisible(settleMillis = 0),
                    )
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        onTab = false
        waitFor("the engine told it's hidden") { "visible:false" in calls(engine) }
        assertEquals(WebViewPhase.Live, state.phase, "a hidden tab keeps its page")
        ui { runtime.clear() }
        mainClock.advanceTimeBy(60_000)
        waitFor("the last user's page given back") { ui { engine.released || "reset" in engine.calls } }
        assertEquals(WebViewPhase.Cold, state.phase)
        assertNull(ui { runtime.pages.recall(PageKey(WebProfile.Default, plugin.key)) }, "saved after the clear")
        onTab = true
        waitFor("live again") { state.phase == WebViewPhase.Live }
        val next = ui { state.engine as FakeWebEngine }
        val started = calls(next).filter { it.startsWith("load") || it.startsWith("restore") }
        assertEquals("load:${plugin.key}", started.last(), "the plugin wasn't loaded afresh: $started")
        assertNoViolations(factory)
    }

    /** Any navigator command into an engine that throws: nothing reaches the app, and the view shows Crashed. */
    @Test fun aNavigatorCommandIntoAnEngineThatThrowsCrashesTheViewNotTheApp() = runComposeUiTest {
        val fake = FakeWebEngineFactory()
        val runtime = testRuntime(
            WebEngineFactory { config ->
                val engine = (fake.create(config) as WebEngineResult.Created).engine
                WebEngineResult.Created(
                    object : WebEngine by engine {
                        override fun goBack() = throw IllegalStateException("back failed")
                    },
                )
            },
        )
        val errors = mutableListOf<Throwable>()
        ui { runtime.onError = { errors += it } }
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        ui { state.navigator.back() }
        waitFor("crashed") { state.phase == WebViewPhase.Crashed }
        assertEquals(listOf("back failed"), ui { errors.map { it.message } })
        runOnIdle { state.navigator.reload() }
        waitFor("live again") { state.phase == WebViewPhase.Live && ui { fake.created.size } == 2 }
        assertNoViolations(fake)
    }

    /** A view whose layout left (moved where nothing places it) counts as hidden, and gives its engine back. */
    @Test fun aViewWhoseLayoutIsDetachedCountsAsHidden() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = 50)
        val state = WebViewState(url)
        var placed by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                val view = remember {
                    movableContentOf { WebView(state, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0)) }
                }
                if (placed) Box { view() } else Unplaced { view() }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        placed = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        assertEquals(0, ui { runtime.pool.liveCount })
        assertNoViolations(factory)
    }
}

/** Measures its content and never places it. */
@Composable
private fun Unplaced(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        measurables.forEach { it.measure(constraints) }
        layout(0, 0) {}
    }
}
