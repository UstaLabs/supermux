package dev.supermux.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.LocalWebViewOverlays
import dev.supermux.webview.runtime.OverlayRegistry
import dev.supermux.webview.runtime.OverlayShape
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Task 13/14 review's probes (Task 14b), kept as tests. Each asserts what the requirements ask. Adapted where a fix
 * changes their premise: P5 counts the engines the view had (a released engine is not reused without spare room), P26
 * waits for the load (a zero height no longer counts as a size), and each ends with its fakes' contract held.
 */
@OptIn(ExperimentalTestApi::class)
class ReviewProbeTest {
    private val url = WebContent.Url("https://example.test/")
    private val notes = WebContent.Html("<p>notes</p>", WebOrigin("notes"))

    private fun <T> ComposeUiTest.ui(block: () -> T): T = runOnUiThread(block)

    private fun ComposeUiTest.calls(engine: FakeWebEngine): List<String> = ui { engine.calls.toList() }

    /** The engine contract held: no engine was used against it (see [FakeWebEngine.violations]). */
    private fun ComposeUiTest.assertNoViolations(factory: FakeWebEngineFactory) = ui {
        factory.created.forEach { assertEquals(emptyList(), it.violations, "engine ${it.id}'s violations") }
    }

    private fun ComposeUiTest.waitFor(what: String, timeout: Long = TIMEOUT, condition: () -> Boolean) =
        waitUntil(conditionDescription = what, timeoutMillis = timeout, condition = condition)

    private fun whiteCount(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        var n = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y] == Color.White) n++
        return n
    }

    private fun ComposeUiTest.centerColor(tag: String): Color {
        val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    /** An engine whose snapshot() can be held open, as a stuck WKWebView's takeSnapshot never calls back. */
    private class Stalling(val fake: FakeWebEngine) : WebEngine by fake {
        var snapshotGate: CompletableDeferred<Unit>? = null

        override suspend fun snapshot(): ImageBitmap? {
            snapshotGate?.await()
            return fake.snapshot()
        }
    }

    /** An engine that throws from load(), as a platform call can. */
    private class Throwing(val fake: FakeWebEngine) : WebEngine by fake {
        override fun load(content: WebContent) {
            throw IllegalStateException("probe: load failed")
        }
    }

    /** Counts how often this engine's Surface is composed in a new place while still in another. */
    private class Counting(val fake: FakeWebEngine) : WebEngine by fake {
        val active = mutableListOf<Any>()
        var overlaps = 0

        @Composable
        override fun Surface(modifier: androidx.compose.ui.Modifier) {
            val token = remember { Any() }
            if (active.any { it !== token }) overlaps++
            DisposableEffect(token) {
                active += token
                onDispose { active.remove(token) }
            }
            fake.Surface(modifier)
        }
    }

    // P1: a view that moves to another parent (a new host for the same state) while the old one still saves.
    @Test fun p1_aMovedViewKeepsItsPhaseBridgeAndState() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("draft-1")))
        val runtime = testRuntime(factory)
        val state = WebViewState(notes)
        var wide by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                if (wide) Row { WebView(state, Modifier.size(200.dp)) } else Column { WebView(state, Modifier.size(200.dp)) }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val first = ui { factory.created.single() }
        val gate = CompletableDeferred<Unit>()
        ui {
            first.saveStateGate = gate
            // The next engine's page has other state: "draft-1" lives only in the first engine's page.
            factory.behavior = FakeEngineBehavior(pageState = JsonPrimitive("fresh"))
        }
        wide = true
        waitFor("live on the moved view's engine") { ui { factory.created.size } == 2 && state.phase == WebViewPhase.Live }
        val second = ui { factory.created.last() }
        ui { gate.complete(Unit) }
        waitFor("the old engine given back") { ui { runtime.pool.liveCount } == 1 }
        waitForIdle()
        val problems = mutableListOf<String>()
        if (state.phase != WebViewPhase.Live) problems += "phase is ${state.phase} while the moved view is live"
        if (!ui { state.bridge.isConnected }) problems += "the moved view's bridge was disconnected by the old view"
        if (calls(second).none { it.startsWith("post:") && "\$restoreState" in it && "draft-1" in it }) {
            problems += "the page's draft never reached the moved view's page (pageState=${ui { state.pageState }})"
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("; "))
        assertNoViolations(factory)
    }

    // P2: a reload starts a new document; the fake's surface is white until it paints.
    @Test fun p2_aReloadNeverShowsTheUnpaintedPage() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(color = Color.Red))
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp).testTag("view")) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        ui { engine.behavior = FakeEngineBehavior(paintsOnLoad = false, color = Color.Red) }
        runOnIdle { state.navigator.reload() }
        waitForIdle()
        val white = whiteCount(onNodeWithTag("view").captureToImage())
        assertEquals(0, white, "white pixels after reload, before the new document painted (phase ${state.phase})")
        assertNoViolations(factory)
    }

    // P3: the renderer died before the view subscribed: its Crashed is lost (no subscriber).
    @Test fun p3_anEngineThatDiedBeforeBindIsNotLeftActivatingForever() = runComposeUiTest {
        val fake = FakeWebEngineFactory(behavior = FakeEngineBehavior(paintsOnLoad = false))
        val runtime = testRuntime(
            WebEngineFactory { config ->
                fake.create(config).also { (it as? WebEngineResult.Created)?.let { c -> (c.engine as FakeWebEngine).emit(EngineEvent.Crashed) } }
            },
        )
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitFor("an engine") { ui { fake.created.isNotEmpty() } }
        mainClock.advanceTimeBy(10_000)
        waitForIdle()
        assertTrue(
            state.phase == WebViewPhase.Crashed || ui { fake.created.size } > 1,
            "phase ${state.phase} on a dead engine (isUsable=${ui { fake.created.first().isUsable }}), ${ui { fake.created.size }} engine(s)",
        )
        assertNoViolations(fake)
    }

    // P4: an engine that throws from load().
    @Test fun p4_anEngineThatThrowsFromLoadDoesNotTakeTheViewDown() = runComposeUiTest {
        val fake = FakeWebEngineFactory()
        val runtime = testRuntime(
            WebEngineFactory { config -> WebEngineResult.Created(Throwing((fake.create(config) as WebEngineResult.Created).engine as FakeWebEngine)) },
        )
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitFor("an engine") { ui { fake.created.isNotEmpty() } }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertNotEquals(WebViewPhase.Activating, state.phase)
        assertNoViolations(fake)
    }

    // P5: deactivate's snapshot never returns.
    @Test fun p5_aStuckSnapshotDoesNotKeepTheLeaseOrWedgeTheView() = runComposeUiTest {
        val fake = FakeWebEngineFactory()
        val engines = mutableListOf<Stalling>()
        val runtime = testRuntime(
            WebEngineFactory { config ->
                WebEngineResult.Created(Stalling((fake.create(config) as WebEngineResult.Created).engine as FakeWebEngine).also { engines += it })
            },
            graceMillis = 50,
        )
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(100.dp).clipToBounds()) {
                    WebView(
                        state,
                        Modifier.size(100.dp).offset(y = if (shown) 0.dp else 200.dp),
                        activation = Activation.WhenVisible(settleMillis = 0),
                    )
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val gate = CompletableDeferred<Unit>()
        try {
            ui { engines.single().snapshotGate = gate }
            shown = false
            mainClock.advanceTimeBy(60_000)
            waitForIdle()
            val held = ui { runtime.pool.liveCount }
            shown = true
            mainClock.advanceTimeBy(5_000)
            waitForIdle()
            // Released for good (no spare room), its engine isn't the one the view gets when shown again.
            val reacted = ui { engines.sumOf { engine -> engine.fake.calls.count { it == "visible:true" } } }
            assertEquals(0, held, "a minute after it scrolled away, the view still holds its lease (phase ${state.phase})")
            assertTrue(reacted >= 2, "the view never reacted to being shown again")
        } finally {
            ui { gate.complete(Unit) }
        }
        waitForIdle()
        assertNoViolations(fake)
    }

    // P6: covers are held by hosts outside the snapshot cache's budget.
    @Test fun p6_coldViewsHoldNoMoreSnapshotsThanTheCacheKeeps() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(color = Color.Red))
        val px = with(density) { 100.dp.roundToPx() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            factory,
            RuntimeConfig(maxLive = 4, maxIdle = 0, snapshotCacheBytes = 2L * px * px * 4, graceMillis = 50),
            scope,
            clock = { mainClock.currentTime },
        )
        try {
            val states = List(8) { WebViewState(WebContent.Url("https://example.test/$it")) }
            var index by mutableStateOf(0)
            var all by mutableStateOf(false)
            var activation by mutableStateOf<Activation>(Activation.WhenVisible(settleMillis = 0))
            setContent {
                ProvideWebViewRuntime(runtime) {
                    Box(Modifier.size(100.dp, if (all) 800.dp else 100.dp).clipToBounds()) {
                        Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset(y = if (all) 0.dp else (-100 * index).dp)) {
                            states.forEachIndexed { i, s -> WebView(s, Modifier.size(100.dp).testTag("v$i"), activation = activation) }
                        }
                    }
                }
            }
            for (i in states.indices) {
                index = i
                waitFor("view $i snapshotted") { states[i].phase == WebViewPhase.Live && ui { states[i].snapshotKey != null } }
            }
            index = states.size // all scrolled away
            waitFor("all cold") { states.all { it.phase == WebViewPhase.Cold } }
            activation = Activation.OnInteraction
            all = true
            waitForIdle()
            val showingSnapshot = states.indices.count { centerColor("v$it") == Color.Red }
            val cached = ui { runtime.snapshots.count }
            assertTrue(
                showingSnapshot <= cached,
                "$showingSnapshot cold views show (and hold) a snapshot bitmap, the cache keeps $cached within its budget",
            )
            assertNoViolations(factory)
        } finally {
            scope.cancel()
        }
    }

    // P7: an engine handed from an evicted view to its evicter is composed in the new view before the old one drops it.
    @Test fun p7_anEngineIsNeverComposedInTwoViewsAtOnce() = runComposeUiTest {
        val fake = FakeWebEngineFactory()
        val engines = mutableListOf<Counting>()
        val runtime = testRuntime(
            WebEngineFactory { config ->
                WebEngineResult.Created(Counting((fake.create(config) as WebEngineResult.Created).engine as FakeWebEngine).also { engines += it })
            },
            maxLive = 1,
        )
        val a = WebViewState(WebContent.Url("https://a.test/"))
        val b = WebViewState(WebContent.Url("https://b.test/"))
        var showA by mutableStateOf(true)
        var showB by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    if (showB) WebView(b, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                    Box(Modifier.size(if (showA) 100.dp else 0.dp)) { WebView(a, Modifier.fillMaxSize(), activation = Activation.Eager) }
                }
            }
        }
        waitFor("a live") { a.phase == WebViewPhase.Live }
        showA = false
        waitForIdle()
        showB = true
        waitFor("b live") { b.phase == WebViewPhase.Live }
        val handedOver = ui { engines.size == 1 }
        assertEquals(0, ui { engines.sumOf { it.overlaps } }, "engine handed over in the same frame (single engine reused: $handedOver)")
        assertNoViolations(fake)
    }

    // P8: a menu in a Popup over a live page.
    @Test fun p8_anOverlayInAPopupCutsAHoleInALivePage() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(300.dp)) {
                    WebView(state, Modifier.fillMaxSize())
                    Popup(offset = IntOffset(50, 50)) { Box(Modifier.size(80.dp).webViewOverlay()) }
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("one hole") { ui { factory.created.single().cutouts?.holes?.size } == 1 }
        val hole = ui { factory.created.single().cutouts!!.holes.single().bounds }
        assertEquals(50f, hole.left, 0.5f)
        assertEquals(50f, hole.top, 0.5f)
        assertNoViolations(factory)
    }

    // P9: the window's registry is swapped while the page is live.
    @Test fun p9_swappingTheWindowRegistryWhileLiveMovesTheHoles() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        val withMenu = OverlayRegistry().apply { setOverlay("menu", OverlayShape(Rect(10f, 10f, 50f, 50f))) }
        val empty = OverlayRegistry()
        var registry by mutableStateOf(empty)
        setContent {
            ProvideWebViewRuntime(runtime) {
                CompositionLocalProvider(LocalWebViewOverlays provides registry) { WebView(state, Modifier.size(200.dp)) }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        waitFor("no hole") { ui { engine.cutouts?.holes?.size } == 0 }
        registry = withMenu
        waitFor("the menu's hole") { ui { engine.cutouts?.holes?.size } == 1 }
        registry = empty
        waitFor("no hole again") { ui { engine.cutouts?.holes?.size } == 0 }
        assertNoViolations(factory)
    }

    // P10: an evicted view's save overruns the pool's 5 s bound.
    @Test fun p10_anEvictionWhoseSaveHangsGivesTheEngineUpAfterTheBound() = runComposeUiTest {
        val fake = FakeWebEngineFactory()
        val engines = mutableListOf<Stalling>()
        val runtime = testRuntime(
            WebEngineFactory { config ->
                WebEngineResult.Created(Stalling((fake.create(config) as WebEngineResult.Created).engine as FakeWebEngine).also { engines += it })
            },
            maxLive = 1,
        )
        val a = WebViewState(WebContent.Url("https://a.test/"))
        val b = WebViewState(WebContent.Url("https://b.test/"))
        var showA by mutableStateOf(true)
        var showB by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    Box(Modifier.size(if (showA) 100.dp else 0.dp)) { WebView(a, Modifier.fillMaxSize(), activation = Activation.Eager) }
                    if (showB) WebView(b, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                }
            }
        }
        waitFor("a live") { a.phase == WebViewPhase.Live }
        val gate = CompletableDeferred<Unit>()
        try {
            ui { engines.single().snapshotGate = gate }
            showA = false
            waitForIdle()
            showB = true
            waitFor("b live", timeout = 20_000) { b.phase == WebViewPhase.Live }
            assertEquals(WebViewPhase.Cold, a.phase)
            assertTrue(ui { engines.first().fake.released }, "the stuck engine is released")
            assertEquals(1, ui { runtime.pool.liveCount })
        } finally {
            ui { gate.complete(Unit) }
        }
        waitForIdle()
        ui { fake.created.forEach { assertEquals(emptyList(), it.violations, "engine ${it.id}") } }
    }

    // P11: a cold view's snapshot in the other theme.
    @Test fun p11_aColdViewNeverShowsASnapshotOfTheOtherTheme() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(color = Color.Red))
        val runtime = testRuntime(factory, graceMillis = 50)
        val state = WebViewState(url)
        var activation by mutableStateOf<Activation>(Activation.WhenVisible(settleMillis = 0))
        var shown by mutableStateOf(true)
        var settings by mutableStateOf(WebSettings(darkMode = DarkMode.Light))
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(100.dp).clipToBounds()) {
                    WebView(
                        state,
                        Modifier.size(100.dp).offset(y = if (shown) 0.dp else 200.dp).testTag("view"),
                        settings = settings,
                        activation = activation,
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
        assertEquals(Color.Red, centerColor("view"))
        settings = WebSettings(darkMode = DarkMode.Dark)
        waitForIdle()
        assertEquals(Color(0xFF202124), centerColor("view"), "the dark placeholder, not the light snapshot")
        assertNoViolations(factory)
    }

    // P16: an origin removed from bridgeOrigins mid-lease.
    @Test fun p16_anOriginRemovedFromTheBridgeOriginsGetsNoMoreHostMessages() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var settings by mutableStateOf(WebSettings(bridgeOrigins = setOf("https://example.test")))
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp), settings = settings) } }
        waitFor("live and ready") { state.phase == WebViewPhase.Live && ui { state.bridge.isReady } }
        val engine = ui { factory.created.single() }
        settings = WebSettings()
        waitFor("reconfigured") { ui { engine.lastSetup?.bridgeOrigins }?.isEmpty() == true }
        ui { state.bridge.post("secret", JsonPrimitive("token")) }
        assertTrue(calls(engine).none { it.startsWith("post:") && "secret" in it }, "the revoked origin still got the host's post")
        assertNoViolations(factory)
    }

    // P18: a lazy item scrolled away and back while its old view still saves.
    @Test fun p18_aViewRecreatedWhileItsPredecessorStillSavesGetsTheSavedState() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("draft-1")))
        val runtime = testRuntime(factory)
        var shown by mutableStateOf(true)
        var state: WebViewState? = null
        setContent {
            ProvideWebViewRuntime(runtime) {
                if (shown) WebView(rememberWebViewState(notes).also { state = it }, Modifier.size(100.dp))
            }
        }
        waitFor("live") { state?.phase == WebViewPhase.Live }
        val first = ui { factory.created.single() }
        val gate = CompletableDeferred<Unit>()
        ui {
            first.saveStateGate = gate
            factory.behavior = FakeEngineBehavior(pageState = JsonPrimitive("fresh"))
        }
        shown = false
        waitForIdle()
        shown = true
        waitFor("live again") { ui { factory.created.size } == 2 && state?.phase == WebViewPhase.Live }
        ui { gate.complete(Unit) }
        waitFor("the old engine given back") { ui { runtime.pool.liveCount } == 1 }
        waitForIdle()
        val second = ui { factory.created.last() }
        assertTrue(
            calls(second).any { it.startsWith("post:") && "\$restoreState" in it && "draft-1" in it },
            "the page's draft never reached the recreated view's page: ${calls(second).filter { it.startsWith("post") || it.startsWith("restore") || it.startsWith("load") }}",
        )
        assertNoViolations(factory)
    }

    // P21: a same-config content switch while the engine is still being created loads only the new content.
    @Test fun p21_aSameConfigSwitchDuringCreationLoadsOnlyTheNewContent() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(200.dp), activation = Activation.Eager) } }
        mainClock.advanceTimeBy(200)
        runOnIdle { state.navigator.load("https://example.test/b") }
        ui {
            factory.createGate = null
            gate.complete(Unit)
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        assertEquals(listOf("load:url:https://example.test/b"), calls(engine).filter { it.startsWith("load") })
        assertEquals(emptyList(), ui { engine.violations })
    }

    // P22: the view leaves the composition while its deactivate is still saving.
    @Test fun p22_disposedMidDeactivateReleasesOnceAndCleanly() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("draft")))
        val runtime = testRuntime(factory, graceMillis = 50)
        val state = WebViewState(notes)
        var shown by mutableStateOf(true)
        var composed by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                if (composed) {
                    Box(Modifier.size(if (shown) 100.dp else 0.dp)) {
                        WebView(state, Modifier.fillMaxSize(), activation = Activation.WhenVisible(settleMillis = 0))
                    }
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        val gate = CompletableDeferred<Unit>()
        ui { engine.saveStateGate = gate }
        shown = false
        waitFor("saving") { "save" in calls(engine) }
        composed = false
        waitForIdle()
        ui { gate.complete(Unit) }
        waitFor("released") { ui { runtime.pool.liveCount } == 0 }
        waitForIdle()
        assertEquals(WebViewPhase.Cold, state.phase)
        assertEquals(1, calls(engine).count { it == "save" })
        assertEquals(emptyList(), ui { engine.violations })
    }

    // P24: the view leaves while its engine is still being created.
    @Test fun p24_aViewDisposedWhileItsEngineIsCreatedLeaksNoLease() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var composed by mutableStateOf(true)
        setContent { ProvideWebViewRuntime(runtime) { if (composed) WebView(state, Modifier.size(100.dp), activation = Activation.Eager) } }
        mainClock.advanceTimeBy(200)
        composed = false
        waitForIdle()
        ui {
            factory.createGate = null
            gate.complete(Unit)
        }
        waitFor("created") { ui { factory.created.size } == 1 }
        waitForIdle()
        assertEquals(0, ui { runtime.pool.liveCount })
        assertTrue(ui { factory.created.single().released }, "with no spare room, the engine is released")
        assertEquals(WebViewPhase.Cold, state.phase)
        assertNoViolations(factory)
    }

    // P25: a failed creation is not retried while the view is hidden, and is once it shows again.
    @Test fun p25_aFailedCreationWaitsForTheViewToShowAgain() = runComposeUiTest {
        val factory = FakeWebEngineFactory().apply { failCreate = true }
        var attempts = 0
        val runtime = testRuntime(WebEngineFactory { attempts++; factory.create(it) })
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(if (shown) 100.dp else 0.dp)) { WebView(state, Modifier.fillMaxSize(), activation = Activation.WhenVisible(0)) }
            }
        }
        waitFor("unavailable") { state.phase is WebViewPhase.Unavailable }
        shown = false
        waitForIdle()
        mainClock.advanceTimeBy(120_000)
        assertEquals(1, ui { attempts }, "no retry while hidden")
        ui { factory.failCreate = false }
        shown = true
        waitFor("live") { state.phase == WebViewPhase.Live }
        assertEquals(2, ui { attempts })
        assertNoViolations(factory)
    }

    // P28: does the cut-out follow an overlay within the frame that moved it?
    @Test fun p28_theCutoutFollowsAMovingOverlayInTheSameFrame() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var x by mutableStateOf(0.dp)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(300.dp)) {
                    WebView(state, Modifier.fillMaxSize())
                    Box(Modifier.offset(x, 10.dp).size(40.dp).webViewOverlay())
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        waitFor("a hole") { ui { engine.cutouts?.holes?.size } == 1 }
        mainClock.autoAdvance = false
        val lags = mutableListOf<Float>()
        repeat(10) { i ->
            x = (20 * (i + 1)).dp
            mainClock.advanceTimeByFrame()
            val drawnAt = with(density) { x.toPx() }
            val holeAt = ui { engine.cutouts!!.holes.single().bounds.left }
            lags += drawnAt - holeAt
        }
        mainClock.autoAdvance = true
        println("PROBE p28 cut-out lag per frame (px): $lags")
        assertTrue(lags.all { it == 0f }, "the hole trails the overlay: $lags")
        assertNoViolations(factory)
    }

    // P26: a page whose first size report is 0 (an SPA shell with margin:0 before it renders). The real shim reports
    // nothing while its viewport is 0 tall (measure() returns null without innerHeight), modelled here.
    @Test fun p26_aPageThatOnceReportsZeroHeightIsNotStuckInvisible() = runComposeUiTest {
        var rendered = false
        val factory = FakeWebEngineFactory(
            behavior = FakeEngineBehavior(contentHeightInViewport = { _, vh -> if (vh <= 0f) null else if (rendered) 300f else 0f }),
        )
        val runtime = testRuntime(factory, graceMillis = 50)
        var shown by mutableStateOf(true)
        var state: WebViewState? = null
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.width(200.dp)) {
                    if (shown) {
                        WebView(
                            rememberWebViewState(notes).also { state = it },
                            Modifier.testTag("view"),
                            sizing = WebSizing.WrapContentHeight(max = 1_000.dp),
                            activation = Activation.WhenVisible(settleMillis = 0),
                        )
                    }
                }
            }
        }
        // Loaded, with its zero height reported: no size to wait for, since a zero height is none.
        waitFor("loaded") { state?.phase == WebViewPhase.Live }
        waitForIdle()
        rendered = true
        mainClock.advanceTimeBy(5_000)
        waitForIdle()
        val first = "after the page rendered: height ${onNodeWithTag("view").fetchSemanticsNode().size.height} px, phase ${state?.phase}"
        // A new view of the same content, as a lazy list makes when the item scrolls back.
        shown = false
        waitForIdle()
        shown = true
        mainClock.advanceTimeBy(5_000)
        waitForIdle()
        val second = "a new view of it: height ${onNodeWithTag("view").fetchSemanticsNode().size.height} px, phase ${state?.phase}"
        assertTrue(state?.phase == WebViewPhase.Live && onNodeWithTag("view").fetchSemanticsNode().size.height > 0, "$first; $second")
        assertNoViolations(factory)
    }

    // P29: a live page that only moves (scrolled, fully visible, nothing over it).
    @Test fun p29_aMovingLivePageWithNothingOverItIsNotReclippedEveryFrame() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        val scroll = androidx.compose.foundation.ScrollState(0)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.size(200.dp, 400.dp).verticalScroll(scroll)) {
                    Box(Modifier.size(200.dp, 100.dp))
                    WebView(state, Modifier.size(200.dp, 100.dp))
                    Box(Modifier.size(200.dp, 1_000.dp))
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        waitForIdle()
        val before = calls(engine).count { it.startsWith("overlays:") }
        mainClock.autoAdvance = false
        repeat(60) {
            runOnUiThread { scroll.dispatchRawDelta(1f) }
            mainClock.advanceTimeByFrame()
        }
        mainClock.autoAdvance = true
        waitForIdle()
        val during = calls(engine).count { it.startsWith("overlays:") } - before
        println("PROBE p29 applyOverlays calls during 60 frames of scrolling a fully visible page: $during")
        assertTrue(during <= 2, "$during applyOverlays calls for a fully visible page with nothing over it")
        assertNoViolations(factory)
    }
}
