package dev.supermux.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.OverlayCutouts
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.SavedPageState
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals(listOf("engine broke"), ui { errors.map { it.message } })
        // Destroyed, never reused.
        waitFor("destroyed") { ui { fake.created.single().released } }
        assertEquals(0, ui { runtime.pool.liveCount })
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
        val runtime = testRuntime(factory, graceMillis = 50)
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
        val px = with(density) { 100.dp.roundToPx() }.toLong()
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
        val runtime = testRuntime(factory, graceMillis = 50)
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
        val memory = assertNotNull(ui { runtime.pages.recall(url.key) })
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
        val runtime = testRuntime(factory, graceMillis = 50)
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
                    WebView(pane, Modifier.size(100.dp))
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
                    if (otherShown) WebView(other, Modifier.size(100.dp))
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
                    WebView(stateA, Modifier.size(100.dp))
                    WebView(stateB, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                    if (cShown) WebView(stateC, Modifier.size(100.dp))
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
        val runtime = testRuntime(factory, graceMillis = 50)
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
        val runtime = testRuntime(factory, graceMillis = 50)
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
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
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

    /** A view whose layout left (moved where nothing places it) counts as hidden, and gives its engine back. */
    @Test fun aViewWhoseLayoutIsDetachedCountsAsHidden() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, graceMillis = 50)
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
