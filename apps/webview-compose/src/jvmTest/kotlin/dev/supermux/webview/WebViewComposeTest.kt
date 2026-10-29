package dev.supermux.webview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.runtime.PageKey
import dev.supermux.webview.runtime.ProvideWebViewOverlays
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.SavedPageState
import dev.supermux.webview.runtime.SnapshotKey
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalTestApi::class)
class WebViewComposeTest {
    private val url = WebContent.Url("https://example.test/")
    private val notes = WebContent.Html("<p>notes</p>", WebOrigin("notes"))

    /** Reads state the test doesn't own (engines, the pool) on the UI thread, where the runtime changes it. */
    private fun <T> ComposeUiTest.ui(block: () -> T): T = runOnUiThread(block)

    private fun ComposeUiTest.calls(engine: FakeWebEngine): List<String> = ui { engine.calls.toList() }

    /** The engine contract held: no engine was used against it (see [FakeWebEngine.violations]). */
    private fun ComposeUiTest.assertNoViolations(factory: FakeWebEngineFactory) = ui {
        factory.created.forEach { assertEquals(emptyList(), it.violations, "engine ${it.id}'s violations") }
    }

    private fun ComposeUiTest.waitFor(what: String, condition: () -> Boolean) =
        waitUntil(conditionDescription = what, timeoutMillis = TIMEOUT, condition = condition)

    private fun assertNoWhite(image: ImageBitmap, what: String) {
        val pixels = image.toPixelMap()
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y] == Color.White) fail("a white pixel at ($x, $y) in $what")
            }
        }
    }

    private fun ComposeUiTest.centerColor(tag: String): Color {
        val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    // --- Leasing, painting and the cover ---

    @Test fun anEagerViewLeasesAnEngineConfiguresItLoadsItAndGoesLive() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(200.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        onNodeWithTag("fake-engine-1").assertExists()
        val engine = ui { factory.created.single() }
        val calls = calls(engine)
        assertEquals(listOf("load:url:https://example.test/"), calls.filter { it.startsWith("load") })
        assertTrue(calls.indexOf("configure") in 0 until calls.indexOf("load:url:https://example.test/"), "configured first: $calls")
        // A network page gets the bridge only where its origin is listed, and the default profile's engine.
        assertEquals(PageSetup(true, false, false, false, null, emptySet()), ui { engine.lastSetup })
        assertEquals(EngineConfig(WebProfile.Default), engine.config)
        assertEquals("https://example.test/", state.url)
        assertNoViolations(factory)
    }

    @Test fun thePlaceholderCoversTheEngineUntilItPaints() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(paintsOnLoad = false))
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, Modifier.size(200.dp), placeholder = { Box(Modifier.fillMaxSize().testTag("placeholder")) })
            }
        }
        waitFor("an engine") { ui { factory.created.isNotEmpty() } }
        waitForIdle()
        onNodeWithTag("placeholder").assertExists()
        assertEquals(WebViewPhase.Activating, state.phase)
        ui { factory.created.single().emit(EngineEvent.FirstPaint) }
        waitFor("live") { state.phase == WebViewPhase.Live }
        onNodeWithTag("placeholder").assertDoesNotExist()
        assertNoViolations(factory)
    }

    /** The fake's page is white until it paints: frame by frame, none of that white ever shows. */
    @Test fun theCoverHidesThePagesWhiteFrameUntilFirstPaint() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(paintsOnLoad = false, color = Color.Red))
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        mainClock.autoAdvance = false
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp).testTag("view")) } }
        var frames = 0
        var leasedFrames = 0
        while (leasedFrames < 10) {
            mainClock.advanceTimeByFrame()
            assertNoWhite(onNodeWithTag("view").captureToImage(), "frame $frames")
            if (ui { factory.created.isNotEmpty() }) leasedFrames++
            if (++frames > 500) fail("no engine was leased")
        }
        onNodeWithTag("fake-engine-1").assertExists()
        assertEquals(WebViewPhase.Activating, state.phase)
        ui { factory.created.single().emit(EngineEvent.FirstPaint) }
        mainClock.autoAdvance = true
        waitFor("live") { state.phase == WebViewPhase.Live }
        assertEquals(Color.Red, centerColor("view"))
        assertNoViolations(factory)
    }

    @Test fun aMainFrameErrorLiftsTheCoverAsAPaintDoes() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(paintsOnLoad = false))
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, Modifier.size(200.dp), placeholder = { Box(Modifier.fillMaxSize().testTag("placeholder")) })
            }
        }
        waitFor("activating") { state.phase == WebViewPhase.Activating }
        val error = WebError.Network("https://example.test/", "offline")
        ui { factory.created.single().emit(EngineEvent.Error(error)) }
        waitFor("live") { state.phase == WebViewPhase.Live }
        onNodeWithTag("placeholder").assertDoesNotExist()
        assertEquals(error, state.error)
        assertNoViolations(factory)
    }

    @Test fun aViewHiddenByLayoutKeepsItsEngineAndIsNeverRebuilt() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(if (shown) 200.dp else 0.dp)) { WebView(state, Modifier.fillMaxSize()) }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        shown = false
        waitFor("the engine told it's hidden") { "visible:false" in calls(engine) }
        shown = true
        waitFor("the engine told it's shown again") { calls(engine).last { it.startsWith("visible:") } == "visible:true" }
        assertEquals(WebViewPhase.Live, state.phase)
        val calls = calls(engine)
        assertFalse("reset" in calls)
        assertEquals(1, calls.count { it.startsWith("load") })
        assertEquals(listOf("visible:true", "visible:false", "visible:true"), calls.filter { it.startsWith("visible:") })
        assertNoViolations(factory)
    }

    @Test fun anOffScreenEmbedGivesItsEngineBackAfterTheGraceAndKeepsASnapshot() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        // Room for the cold-start spare and for this engine, so it is reset and kept rather than destroyed.
        val runtime = testRuntime(factory, maxIdle = 2, graceMillis = 50)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(if (shown) 200.dp else 0.dp)) {
                    WebView(state, Modifier.fillMaxSize(), activation = Activation.WhenVisible(settleMillis = 0))
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.first() }
        waitFor("the capture after the first paint settled") { ui { "snapshot" in engine.calls && state.snapshotKey != null } }
        val key = assertNotNull(ui { state.snapshotKey })
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        waitFor("the engine reset for reuse") { "reset" in calls(engine) }
        ui {
            // At 0×0 the engine had nothing to show: the last good snapshot stays.
            assertEquals(key, state.snapshotKey)
            assertNotNull(runtime.snapshots[key])
            assertTrue("save" in engine.calls)
            assertEquals(0, runtime.pool.liveCount)
        }
        assertNoViolations(factory)
    }

    @Test fun aColdViewShowsItsSnapshotUntilItsContentChanges() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(color = Color.Red))
        val runtime = testRuntime(factory, graceMillis = 50)
        val state = WebViewState(url)
        var activation by mutableStateOf<Activation>(Activation.WhenVisible(settleMillis = 0))
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(100.dp).clipToBounds()) {
                    WebView(
                        state,
                        Modifier.size(100.dp).offset(y = if (shown) 0.dp else 200.dp).testTag("view"),
                        settings = WebSettings(darkMode = DarkMode.Dark),
                        activation = activation,
                        placeholder = { Box(Modifier.fillMaxSize().background(Color.Green).testTag("placeholder")) },
                    )
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("a snapshot") { ui { state.snapshotKey != null } }
        // Scrolled away, then back as a view that waits to be tapped: cold, and covered by its snapshot.
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        activation = Activation.OnInteraction
        shown = true
        waitForIdle()
        assertEquals(WebViewPhase.Cold, state.phase)
        assertTrue(ui { state.snapshotKey?.dark } == true)
        assertEquals(Color.Red, centerColor("view"), "the snapshot, over the placeholder")
        // Other content: the old page's snapshot must not stand in for it.
        runOnIdle { state.load(WebContent.Url("https://other.test/")) }
        waitForIdle()
        assertEquals(Color.Green, centerColor("view"), "the placeholder")
        assertNoViolations(factory)
    }

    // --- Page memory, snapshots and saved state ---

    @Test fun aRecreatedViewOfTheSameContentRemembersItsSnapshotSizeAndState() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 300f }))
        val runtime = testRuntime(factory)
        val content = WebContent.Html("<p>remember me</p>", WebOrigin("mem"))
        var shown by mutableStateOf(true)
        var lastState: WebViewState? = null
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.width(200.dp)) {
                    if (shown) {
                        val state = rememberWebViewState(content)
                        lastState = state
                        WebView(state, sizing = WebSizing.WrapContentHeight(max = 1_000.dp))
                    }
                }
            }
        }
        waitFor("live and sized") { lastState.let { it?.phase == WebViewPhase.Live && it.contentSize != null } }
        // Leaving the composition takes no snapshot: wait for the one taken once the first paint settled.
        waitFor("a snapshot") { ui { lastState?.snapshotKey != null } }
        val first = checkNotNull(lastState)
        shown = false
        waitFor("the engine given back") { ui { runtime.pool.liveCount == 0 } }
        shown = true
        waitForIdle()
        val fresh = checkNotNull(lastState)
        assertNotSame(first, fresh)
        assertNotNull(ui { fresh.snapshotKey })
        assertEquals(300.dp, fresh.contentSize?.height)
        waitFor("live again") { fresh.phase == WebViewPhase.Live }
        assertTrue(ui { factory.created.any { engine -> engine.calls.any { it.startsWith("restore:") } } })
        assertNoViolations(factory)
    }

    @Test fun leavingTheCompositionSavesTheEngineAndPageStateButTakesNoSnapshot() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("draft")))
        val runtime = testRuntime(factory)
        var shown by mutableStateOf(true)
        var state: WebViewState? = null
        setContent {
            ProvideWebViewRuntime(runtime) {
                if (shown) WebView(rememberWebViewState(notes).also { state = it }, Modifier.size(200.dp))
            }
        }
        // Live: the page has announced its save provider, and its first paint hasn't settled into a snapshot yet.
        waitFor("live") { state?.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        assertFalse("snapshot" in calls(engine))
        shown = false
        waitFor("the engine given back") { ui { runtime.pool.liveCount == 0 } }
        val memory = assertNotNull(ui { runtime.pages.recall(PageKey(WebProfile.Ephemeral, notes.key)) })
        assertNotNull(memory.engineState)
        assertEquals(SavedPageState("\"draft\"", notes.origin.origin), memory.pageState)
        assertNull(memory.snapshotKey)
        assertFalse("snapshot" in calls(engine))
        assertNoViolations(factory)
    }

    @Test fun aPagesStateIsKeptWithItsOriginAndGoesBackOnlyToIt() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("draft")))
        val runtime = testRuntime(factory, graceMillis = 50)
        val state = WebViewState(notes)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(if (shown) 200.dp else 0.dp)) {
                    WebView(state, Modifier.fillMaxSize(), activation = Activation.WhenVisible(settleMillis = 0))
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        assertEquals(SavedPageState("\"draft\"", notes.origin.origin), ui { state.pageState })
        shown = true
        waitFor("live again") { state.phase == WebViewPhase.Live && ui { factory.created.size } == 2 }
        val second = ui { factory.created.last() }
        waitFor("the state delivered") { calls(second).any { "\$restoreState" in it } }
        val restores = calls(second).filter { "\$restoreState" in it }
        assertEquals(1, restores.size, "$restores")
        assertTrue(restores.single().startsWith("post:") && "draft" in restores.single(), "$restores")
        assertNoViolations(factory)
    }

    @Test fun stateSavedByAnotherOriginIsNeverDeliveredAndIsKept() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, graceMillis = 50)
        val state = WebViewState(notes)
        val foreign = SavedPageState("\"secret\"", WebOrigin("other").origin)
        ui { state.pageState = foreign }
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(if (shown) 200.dp else 0.dp)) {
                    WebView(state, Modifier.fillMaxSize(), activation = Activation.WhenVisible(settleMillis = 0))
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        waitForIdle()
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        assertTrue(calls(engine).none { "\$restoreState" in it }, "${calls(engine)}")
        assertEquals(0L, ui { state.bridge.droppedMessages }, "the host never offered it to the bridge")
        assertEquals(foreign, ui { state.pageState })
        assertNoViolations(factory)
    }

    /** No document took the state back (no shim without JavaScript), and none answered for it: it is kept. */
    @Test fun aRestoreThatWasNeverDeliveredSurvivesTheNextSave() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, graceMillis = 50)
        val state = WebViewState(notes)
        val saved = SavedPageState("\"draft\"", notes.origin.origin)
        ui { state.pageState = saved }
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(if (shown) 200.dp else 0.dp)) {
                    WebView(
                        state,
                        Modifier.fillMaxSize(),
                        settings = WebSettings(javaScriptEnabled = false),
                        activation = Activation.WhenVisible(settleMillis = 0),
                    )
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        assertEquals(saved, ui { state.pageState })
        assertTrue(calls(ui { factory.created.single() }).none { "\$restoreState" in it })
        assertNoViolations(factory)
    }

    /** The page answers that it keeps nothing: state it never received is kept for it; state it did receive goes. */
    @Test fun aPageThatKeepsNothingLosesOnlyTheStateItWasGiven() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonNull))
        val runtime = testRuntime(factory, graceMillis = 50)
        val memo = WebContent.Html("<p>memo</p>", WebOrigin("memo"))
        val undelivered = WebViewState(notes)
        val delivered = WebViewState(memo)
        val foreign = SavedPageState("\"secret\"", WebOrigin("other").origin)
        ui {
            undelivered.pageState = foreign
            delivered.pageState = SavedPageState("\"draft\"", memo.origin.origin)
        }
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    for (state in listOf(undelivered, delivered)) {
                        Box(Modifier.size(if (shown) 100.dp else 0.dp)) {
                            WebView(state, Modifier.fillMaxSize(), activation = Activation.WhenVisible(settleMillis = 0))
                        }
                    }
                }
            }
        }
        waitFor("live") { undelivered.phase == WebViewPhase.Live && delivered.phase == WebViewPhase.Live }
        val memoEngine = ui { factory.created.single { engine -> engine.calls.any { it == "load:${memo.key}" } } }
        waitFor("memo's state delivered") { calls(memoEngine).any { it.startsWith("post:") && "\$restoreState" in it } }
        shown = false
        waitFor("cold") { undelivered.phase == WebViewPhase.Cold && delivered.phase == WebViewPhase.Cold }
        assertEquals(foreign, ui { undelivered.pageState })
        assertNull(ui { delivered.pageState })
        assertNoViolations(factory)
    }

    /** A load while the old page is being saved: nothing of the old page goes into the state, which shows other content. */
    @Test fun aSaveStillRunningWhenTheContentChangesWritesNothingIntoTheState() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("draft")))
        val runtime = testRuntime(factory, graceMillis = 50)
        val state = WebViewState(notes)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(if (shown) 200.dp else 0.dp)) {
                    WebView(state, Modifier.fillMaxSize(), activation = Activation.WhenVisible(settleMillis = 0))
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        val gate = CompletableDeferred<Unit>()
        ui { engine.saveStateGate = gate }
        shown = false
        waitFor("saving") { "save" in calls(engine) }
        val other = WebContent.Html("<p>other</p>", WebOrigin("other"))
        runOnIdle { state.load(other) }
        ui { gate.complete(Unit) }
        waitFor("the engine given back") { ui { runtime.pool.liveCount } == 0 }
        ui {
            assertEquals(other, state.content)
            assertNull(state.savedEngineState)
            assertNull(state.pageState)
            assertNull(state.snapshotKey)
            // The page's own state came before the switch, and is remembered under its own content; nothing after it is.
            val memory = runtime.pages.recall(PageKey(WebProfile.Ephemeral, notes.key))
            if (memory != null) {
                assertEquals(SavedPageState("\"draft\"", notes.origin.origin), memory.pageState)
                assertNull(memory.engineState)
            }
        }
        assertNoViolations(factory)
    }

    /** At density 2, in a dark theme: a snapshot's key carries both, as a cover must match them to be shown. */
    @Test fun anInteractionRefreshesTheSnapshotAndTheOldOneLeavesTheCache() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var side by mutableStateOf(200.dp)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                ProvideWebViewRuntime(runtime) {
                    WebView(state, Modifier.size(side).testTag("view"), settings = WebSettings(darkMode = DarkMode.Dark))
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("a snapshot") { ui { state.snapshotKey != null } }
        val first = assertNotNull(ui { state.snapshotKey })
        assertEquals(400, first.width)
        assertEquals(2f, first.density)
        assertTrue(first.dark)
        side = 100.dp
        waitForIdle()
        onNodeWithTag("view").performClick()
        waitFor("a new snapshot") { ui { state.snapshotKey != first } }
        val second = assertNotNull(ui { state.snapshotKey })
        assertEquals(200, second.width)
        assertEquals(SnapshotKey(url.key, WebProfile.Default, 200, 200, dark = true, density = 2f), second)
        ui {
            assertNull(runtime.snapshots[first])
            assertNotNull(runtime.snapshots[second])
        }
        assertNoViolations(factory)
    }

    @Test fun aDeactivationBeforeFirstPaintKeepsThePreviousSnapshot() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, graceMillis = 50)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                // Scrolled out of a clipping parent: hidden, but still laid out at full size.
                Box(Modifier.size(100.dp).clipToBounds()) {
                    WebView(
                        state,
                        Modifier.size(100.dp).offset(y = if (shown) 0.dp else 200.dp).testTag("view"),
                        activation = Activation.WhenVisible(settleMillis = 0),
                    )
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("a snapshot") { ui { state.snapshotKey != null } }
        shown = false
        waitFor("the engine given back") { ui { runtime.pool.liveCount == 0 } }
        val key = assertNotNull(ui { state.snapshotKey })
        // The next engine never paints.
        ui { factory.behavior = FakeEngineBehavior(paintsOnLoad = false) }
        shown = true
        waitFor("activating") { state.phase == WebViewPhase.Activating }
        // Covered by the last snapshot meanwhile, never by the unpainted page.
        assertEquals(FakeEngineBehavior().colorOf(url), centerColor("view"))
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold && ui { runtime.pool.liveCount } == 0 }
        ui {
            assertEquals(2, factory.created.size)
            assertTrue("snapshot" in factory.created.last().calls, "it was asked, and had nothing to show")
            assertEquals(key, state.snapshotKey)
            assertNotNull(runtime.snapshots[key])
        }
        assertNoViolations(factory)
    }

    @Test fun anEvictionBeforeFirstPaintKeepsThePreviousSnapshot() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 1, graceMillis = 50)
        val a = WebViewState(url)
        val b = WebViewState(WebContent.Url("https://b.test/"))
        var showA by mutableStateOf(true)
        var showB by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    Box(Modifier.size(100.dp).clipToBounds()) {
                        WebView(
                            a,
                            Modifier.size(100.dp).offset(y = if (showA) 0.dp else 200.dp),
                            activation = Activation.WhenVisible(settleMillis = 0),
                        )
                    }
                    if (showB) WebView(b, Modifier.size(100.dp))
                }
            }
        }
        waitFor("live") { a.phase == WebViewPhase.Live }
        waitFor("a snapshot") { ui { a.snapshotKey != null } }
        showA = false
        waitFor("the engine given back") { ui { runtime.pool.liveCount == 0 } }
        val key = assertNotNull(ui { a.snapshotKey })
        ui { factory.behavior = FakeEngineBehavior(paintsOnLoad = false) }
        showA = true
        waitFor("activating") { a.phase == WebViewPhase.Activating }
        // An Eager view outranks it.
        showB = true
        waitFor("evicted") { a.phase == WebViewPhase.Cold && b.phase == WebViewPhase.Activating }
        ui {
            assertEquals(key, a.snapshotKey)
            assertNotNull(runtime.snapshots[key])
        }
        assertNoViolations(factory)
    }

    // --- Content switches ---

    @Test fun loadingHtmlIntoALiveUrlViewMovesItToAnEphemeralEngine() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(200.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("a snapshot") { ui { state.snapshotKey != null } }
        val demo = WebContent.Html("<p>demo</p>", WebOrigin("demo"))
        runOnIdle { state.load(demo) }
        waitFor("live on a second engine") { ui { factory.created.size } == 2 && state.phase == WebViewPhase.Live }
        val (old, new) = ui { factory.created.toList() }
        assertEquals(WebProfile.Default, old.config.profile)
        assertEquals(WebProfile.Ephemeral, new.config.profile)
        assertTrue(ui { old.released })
        assertEquals(listOf("load:${demo.key}"), calls(new).filter { it.startsWith("load") || it.startsWith("restore") })
        assertEquals(setOf(demo.origin.origin), ui { new.lastSetup?.bridgeOrigins })
        // Nothing of the network page went into the demo's state.
        ui {
            assertNull(state.savedEngineState)
            assertTrue(state.snapshotKey.let { it == null || it.contentKey == demo.key }, "${state.snapshotKey}")
        }
        assertEquals(demo.origin.url, state.url)
        assertNoViolations(factory)
    }

    @Test fun loadingAnotherUrlConfiguresAndLoadsTheSameEngine() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(200.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        val before = calls(engine).size
        runOnIdle { state.navigator.load("https://example.test/next") }
        waitFor("the next page live") { state.url == "https://example.test/next" && state.phase == WebViewPhase.Live }
        val after = calls(engine).drop(before)
        assertEquals(
            listOf("configure", "load:url:https://example.test/next"),
            after.filter { it == "configure" || it.startsWith("load") || it.startsWith("restore") },
        )
        assertFalse("reset" in after || "release" in after)
        assertEquals(1, ui { factory.created.size })
        assertNoViolations(factory)
    }

    /** Every load navigates: loading the first URL after the page moved on goes back to it, not reloads the other. */
    @Test fun loadingTheShownUrlAgainAfterInPageNavigationGoesBackToIt() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(200.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        // The page navigates by itself, as a link would.
        ui { engine.emit(EngineEvent.UrlChanged("https://example.test/elsewhere")) }
        waitFor("elsewhere") { state.url == "https://example.test/elsewhere" }
        runOnIdle { state.navigator.load("https://example.test/") }
        waitFor("loaded again") { calls(engine).count { it == "load:url:https://example.test/" } == 2 }
        waitFor("back") { state.url == "https://example.test/" && state.phase == WebViewPhase.Live }
        assertFalse("reload" in calls(engine))
        assertNoViolations(factory)
    }

    @Test fun contentLoadedWhileItsEngineIsCreatedGetsAnEngineOfItsOwnConfig() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val gate = CompletableDeferred<Unit>()
        factory.createGate = gate
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(200.dp)) } }
        mainClock.advanceTimeBy(200)
        assertTrue(ui { factory.created.isEmpty() })
        val demo = WebContent.Html("<p>demo</p>", WebOrigin("demo"))
        runOnIdle { state.load(demo) }
        ui {
            factory.createGate = null
            gate.complete(Unit)
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val (first, second) = ui { factory.created.toList() }
        assertEquals(WebProfile.Default, first.config.profile)
        assertEquals(WebProfile.Ephemeral, second.config.profile)
        // The engine made for the old content went back unused.
        assertEquals(listOf("release"), calls(first))
        assertEquals(listOf("load:${demo.key}"), calls(second).filter { it.startsWith("load") })
        assertNoViolations(factory)
    }

    @Test fun settingsChangedMidLeaseReachThePage() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var settings by mutableStateOf(WebSettings(darkMode = DarkMode.Light))
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(200.dp), settings = settings) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val first = ui { factory.created.single() }
        settings = WebSettings(darkMode = DarkMode.Dark)
        waitFor("dark") { ui { first.lastSetup?.dark } == true }
        // Applied to the page as it is: not reloaded.
        assertEquals(1, calls(first).count { it.startsWith("load") })
        // Another profile needs another engine, and is another user's cookies and storage: the page moves to one, and
        // starts fresh there, with nothing of the old profile's page (Task 16b).
        settings = WebSettings(darkMode = DarkMode.Dark, profile = WebProfile.Named("work"))
        waitFor("live on a second engine") { ui { factory.created.size } == 2 && state.phase == WebViewPhase.Live }
        val second = ui { factory.created.last() }
        assertEquals(WebProfile.Named("work"), second.config.profile)
        assertEquals(listOf("load:${url.key}"), calls(second).filter { it.startsWith("restore:") || it.startsWith("load:") })
        assertTrue(ui { first.released })
        // The old profile's page is remembered under its own key, for a view that shows it in that profile again.
        assertNotNull(ui { runtime.pages.recall(PageKey(WebProfile.Default, url.key))?.engineState })
        assertNoViolations(factory)
    }

    // --- The pool ---

    @Test fun aHigherPriorityViewEvictsAHiddenPaneWhenTheBudgetIsFull() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 1)
        val a = WebViewState(WebContent.Url("https://a.test/"))
        val b = WebViewState(WebContent.Url("https://b.test/"))
        var showA by mutableStateOf(true)
        var showB by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    Box(Modifier.size(if (showA) 100.dp else 0.dp)) { WebView(a, Modifier.fillMaxSize()) }
                    if (showB) WebView(b, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                }
            }
        }
        waitFor("a live") { a.phase == WebViewPhase.Live }
        showA = false
        waitForIdle()
        assertEquals(WebViewPhase.Live, a.phase)
        showB = true
        waitFor("b live") { b.phase == WebViewPhase.Live }
        assertEquals(WebViewPhase.Cold, a.phase)
        assertEquals(1, ui { runtime.pool.liveCount })
        assertNoViolations(factory)
    }

    @Test fun aDeniedViewGetsAnEngineOnceCapacityFrees() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 1)
        val a = WebViewState(WebContent.Url("https://a.test/"))
        val b = WebViewState(WebContent.Url("https://b.test/"))
        var showA by mutableStateOf(true)
        var showB by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    if (showA) WebView(a, Modifier.size(100.dp))
                    if (showB) WebView(b, Modifier.size(100.dp))
                }
            }
        }
        waitFor("one live") { a.phase == WebViewPhase.Live || b.phase == WebViewPhase.Live }
        // Only the view that was denied listens for capacity.
        waitFor("one listener") { ui { runtime.pool.capacityListenerCount } == 1 }
        val aWon = a.phase == WebViewPhase.Live
        val loser = if (aWon) b else a
        assertEquals(WebViewPhase.Cold, loser.phase)
        if (aWon) showA = false else showB = false
        waitFor("the other live") { loser.phase == WebViewPhase.Live }
        assertEquals(1, ui { runtime.pool.liveCount })
        assertEquals(0, ui { runtime.pool.capacityListenerCount })
        assertNoViolations(factory)
    }

    /** A pane's priority is read live: hidden, it drops at once, and a denied view gets in without waiting its grace. */
    @Test fun aViewWhosePriorityDropsLetsADeniedOneIn() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 1)
        val pane = WebViewState(WebContent.Url("https://pane.test/"))
        val embed = WebViewState(WebContent.Url("https://embed.test/"))
        var paneShown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    Box(Modifier.size(if (paneShown) 100.dp else 0.dp)) { WebView(pane, Modifier.fillMaxSize()) }
                    WebView(embed, Modifier.size(100.dp), activation = Activation.WhenVisible(settleMillis = 0))
                }
            }
        }
        waitFor("the pane live, the embed waiting") {
            pane.phase == WebViewPhase.Live && ui { runtime.pool.capacityListenerCount } == 1
        }
        assertEquals(WebViewPhase.Cold, embed.phase)
        // Hidden, an Eager pane would keep its engine for ten minutes, but it ranks below a visible embed at once.
        val hiddenAt = mainClock.currentTime
        paneShown = false
        waitFor("the embed live") { embed.phase == WebViewPhase.Live }
        assertTrue(mainClock.currentTime - hiddenAt < 1_000, "live after ${mainClock.currentTime - hiddenAt} ms")
        assertEquals(WebViewPhase.Cold, pane.phase)
        assertEquals(0, ui { runtime.pool.capacityListenerCount })
        assertNoViolations(factory)
    }

    /** Spec §4: never swap during a scroll. An embed that keeps moving stays cold, and goes live once still. */
    @Test fun anEmbedActivatesOnlyOnceItHasStoppedMoving() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var y by mutableStateOf(0.dp)
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, Modifier.offset(y = y).size(100.dp), activation = Activation.WhenVisible(settleMillis = 150))
            }
        }
        mainClock.autoAdvance = false
        // A fling: it moves every frame for half a second.
        repeat(30) {
            y += 3.dp
            mainClock.advanceTimeByFrame()
        }
        assertTrue(ui { factory.created.isEmpty() }, "activated while moving")
        mainClock.advanceTimeBy(96)
        assertTrue(ui { factory.created.isEmpty() }, "activated before it settled")
        mainClock.advanceTimeBy(96)
        mainClock.autoAdvance = true
        waitFor("live") { state.phase == WebViewPhase.Live }
        assertNoViolations(factory)
    }

    @Test fun aViewEvictedForMemoryPressureComesBackWhenItEnds() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 2)
        val a = WebViewState(WebContent.Url("https://a.test/"))
        val b = WebViewState(WebContent.Url("https://b.test/"))
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    WebView(a, Modifier.size(100.dp))
                    WebView(b, Modifier.size(100.dp))
                }
            }
        }
        waitFor("both live") { a.phase == WebViewPhase.Live && b.phase == WebViewPhase.Live }
        ui { runtime.onMemoryPressure() }
        waitFor("one evicted") { listOf(a, b).count { it.phase == WebViewPhase.Cold } == 1 }
        val evicted = if (a.phase == WebViewPhase.Cold) a else b
        // It waits for capacity, and the window keeps it out.
        waitFor("listening") { ui { runtime.pool.capacityListenerCount } == 1 }
        mainClock.advanceTimeBy(1_000)
        assertEquals(WebViewPhase.Cold, evicted.phase)
        ui { runtime.onMemoryRecovered() }
        waitFor("back") { evicted.phase == WebViewPhase.Live }
        assertEquals(2, ui { runtime.pool.liveCount })
        assertNoViolations(factory)
    }

    @Test fun coldViewsNeitherLeaseNorListen() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, maxLive = 1)
        val states = List(50) { WebViewState(WebContent.Url("https://example.test/$it")) }
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column { states.forEach { WebView(it, Modifier.size(10.dp), activation = Activation.OnInteraction) } }
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(5_000)
        ui {
            assertTrue(factory.created.isEmpty())
            assertEquals(0, runtime.pool.capacityListenerCount)
            assertTrue(states.all { it.phase == WebViewPhase.Cold })
        }
    }

    @Test fun anUnavailablePlatformShowsUnavailableAndThePlaceholder() = runComposeUiTest {
        val factory = FakeWebEngineFactory().apply { unavailable = UnavailableReason.NotImplemented }
        var attempts = 0
        val runtime = testRuntime(WebEngineFactory { attempts++; factory.create(it) })
        val state = WebViewState(url)
        var settings by mutableStateOf(WebSettings())
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(
                    state,
                    Modifier.size(200.dp),
                    settings = settings,
                    placeholder = { Box(Modifier.fillMaxSize().testTag("placeholder")) },
                )
            }
        }
        waitFor("unavailable") { (state.phase as? WebViewPhase.Unavailable)?.reason == UnavailableReason.NotImplemented }
        assertEquals(WebViewPhase.Unavailable(UnavailableReason.NotImplemented, "fake"), state.phase)
        onNodeWithTag("placeholder").assertExists()
        // Only a failed creation is retried: not even a change that wakes the view retries a missing engine.
        settings = WebSettings(darkMode = DarkMode.Dark)
        waitForIdle()
        mainClock.advanceTimeBy(70_000)
        assertEquals(1, ui { attempts })
        assertEquals(0, ui { runtime.pool.capacityListenerCount })
    }

    @Test fun aFailedCreationIsRetriedAfterTwoThenTenSeconds() = runComposeUiTest {
        val factory = FakeWebEngineFactory().apply { failCreate = true }
        val attempts = mutableListOf<Long>()
        val runtime = testRuntime(WebEngineFactory { attempts += mainClock.currentTime; factory.create(it) })
        val state = WebViewState(url)
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, Modifier.size(200.dp), placeholder = { Box(Modifier.fillMaxSize().testTag("placeholder")) })
            }
        }
        waitFor("unavailable") { state.phase is WebViewPhase.Unavailable }
        assertEquals(WebViewPhase.Unavailable(UnavailableReason.CreationFailed, "fake create failed"), state.phase)
        onNodeWithTag("placeholder").assertExists()
        // Whole stretches of virtual time at once: frame by frame, ten seconds outlast waitUntil's real-time budget.
        mainClock.advanceTimeBy(2_000)
        waitFor("a second attempt") { ui { attempts.size } == 2 }
        assertTrue(state.phase is WebViewPhase.Unavailable)
        ui { factory.failCreate = false }
        mainClock.advanceTimeBy(9_000)
        assertEquals(2, ui { attempts.size }, "not before ten seconds")
        mainClock.advanceTimeBy(1_100)
        waitFor("live") { state.phase == WebViewPhase.Live }
        val (first, second, third) = ui { attempts.toList() }
        assertTrue(second - first in 2_000L..2_100L, "$attempts")
        assertTrue(third - second in 10_000L..10_100L, "$attempts")
        assertNoViolations(factory)
    }

    /** The pool may tell a view about an engine it no longer holds: the view checks, and changes nothing. */
    @Test fun aLateEvictionOfAnEngineTheViewNoLongerHoldsChangesNothing() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(200.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        val before = calls(engine).size
        val stranger = FakeWebEngine(99)
        val host = ui { runtime.pool.leaseHolders.single() as WebViewHost }
        assertSame(state, host.state)
        ui { runtime.scope.launch { host.onEvict(stranger) } }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        assertEquals(WebViewPhase.Live, state.phase)
        assertTrue(calls(engine).drop(before).none { it == "save" || it == "release" || it == "reset" })
        assertTrue(calls(stranger).isEmpty())
        assertEquals(1, ui { runtime.pool.liveCount })
        assertNoViolations(factory)
    }

    @Test fun focusInThePageMakesItTheFocusedView() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(200.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        ui { engine.emit(EngineEvent.FocusChanged(true)) }
        waitFor("focused") { ui { (runtime.focused as? WebViewHost)?.state === state } }
        ui { engine.emit(EngineEvent.FocusChanged(false)) }
        waitFor("not focused") { ui { runtime.focused == null } }
        assertNoViolations(factory)
    }

    @Test fun onInteractionStaysASnapshotUntilTapped() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, Modifier.size(200.dp).testTag("view"), activation = Activation.OnInteraction)
            }
        }
        waitForIdle()
        assertTrue(ui { factory.created.isEmpty() })
        onNodeWithTag("view").performClick()
        waitFor("live") { state.phase == WebViewPhase.Live }
        assertNoViolations(factory)
    }

    @Test fun aCrashShowsCrashedUntilReloadThenComesBackOnANewEngine() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(200.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        ui { factory.created.single().emit(EngineEvent.Crashed) }
        waitFor("crashed") { state.phase == WebViewPhase.Crashed }
        // Destroyed once its surface has left the composition.
        waitFor("destroyed") { ui { factory.created.single().released } }
        runOnIdle { state.navigator.reload() }
        waitFor("live again") { state.phase == WebViewPhase.Live }
        assertEquals(2, ui { factory.created.size })
        assertNoViolations(factory)
    }

    @Test fun aBundleRecomposedWithAFreshProviderIsNotReloaded() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        var tick by mutableStateOf(0)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    BasicText("tick $tick")
                    val provider = object : WebResourceProvider {
                        override suspend fun load(path: String): WebResource? = null
                    }
                    WebView(rememberWebViewState(WebContent.Bundle(WebOrigin("plug"), provider)), Modifier.size(100.dp))
                }
            }
        }
        waitFor("loaded") { ui { factory.created.singleOrNull()?.calls?.any { it.startsWith("load:") } == true } }
        repeat(5) {
            tick++
            waitForIdle()
        }
        assertEquals(1, calls(ui { factory.created.single() }).count { it.startsWith("load:") })
        assertNoViolations(factory)
    }

    // --- Sizing ---

    @Test fun wrapContentHeightFollowsThePageAndIsCapped() = runComposeUiTest {
        val factory = FakeWebEngineFactory(
            behavior = FakeEngineBehavior(contentHeight = { if (it.key.startsWith("html:tall:")) 5_000f else 300f }),
        )
        val runtime = testRuntime(factory)
        val short = WebViewState(WebContent.Html("<p/>", WebOrigin("short")))
        val tall = WebViewState(WebContent.Html("<p/>", WebOrigin("tall")))
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.width(200.dp)) {
                    WebView(short, Modifier.testTag("short"), sizing = WebSizing.WrapContentHeight(max = 400.dp))
                    WebView(tall, Modifier.testTag("tall"), sizing = WebSizing.WrapContentHeight(max = 400.dp))
                }
            }
        }
        waitFor("sized") { short.contentSize != null && tall.contentSize != null }
        waitForIdle()
        onNodeWithTag("short").assertHeightIsEqualTo(300.dp)
        onNodeWithTag("tall").assertHeightIsEqualTo(400.dp)
        assertNoViolations(factory)
    }

    /** Compose can't lay out a side over 262,142 px: at density 10 a 32,000 CSS px page would be 320,000. */
    @Test fun wrapContentHeightStopsWhereConstraintsDo() = runComposeUiTest {
        // It never paints, so no 262,142 px snapshot is taken.
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(paintsOnLoad = false, contentHeight = { 1e9f }))
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(10f)) {
                ProvideWebViewRuntime(runtime) {
                    Column(Modifier.width(50.dp).verticalScroll(rememberScrollState())) {
                        WebView(state, Modifier.testTag("view"), sizing = WebSizing.WrapContentHeight(max = 100_000.dp))
                    }
                }
            }
        }
        waitFor("sized") { state.contentSize != null }
        waitForIdle()
        assertEquals(32_000.dp, state.contentSize?.height)
        assertEquals(262_142, onNodeWithTag("view").fetchSemanticsNode().size.height)
        assertNoViolations(factory)
    }

    /** A `100vh` + 16 px page grows with its view, which grows with it: the loop is caught, and the view stops. */
    @Test fun aPageThatGrowsWithItsViewportStopsGrowing() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeightInViewport = { _, vh -> vh + 16f }))
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.width(200.dp)) {
                    WebView(state, Modifier.testTag("view"), sizing = WebSizing.WrapContentHeight(max = 2_000.dp))
                }
            }
        }
        waitFor("live and sized") { state.phase == WebViewPhase.Live && state.contentSize != null }
        mainClock.advanceTimeBy(5_000)
        val settled = onNodeWithTag("view").fetchSemanticsNode().size.height
        mainClock.advanceTimeBy(5_000)
        assertEquals(settled, onNodeWithTag("view").fetchSemanticsNode().size.height)
        assertTrue(settled < with(density) { 300.dp.roundToPx() }, "settled at $settled px")
        assertTrue(ui { factory.created.single().calls.count { it.startsWith("load") } } == 1)
        assertNoViolations(factory)
    }

    // --- Overlays ---

    @Test fun overlaysOverALivePageReachItsEngine() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(300.dp)) {
                    WebView(state, Modifier.fillMaxSize())
                    Box(Modifier.offset(50.dp, 50.dp).size(80.dp).webViewOverlay())
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("one hole") { ui { factory.created.single().cutouts?.holes?.size } == 1 }
        assertNoViolations(factory)
    }

    /** Two windows' contents, drawn over each other here: an overlay in one cuts no hole in the other's page. */
    @Test fun anOverlayInAnotherWindowCutsNoHole() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var ownOverlay by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(300.dp)) {
                    ProvideWebViewOverlays {
                        Box(Modifier.fillMaxSize()) {
                            WebView(state, Modifier.fillMaxSize())
                            if (ownOverlay) Box(Modifier.offset(150.dp, 150.dp).size(40.dp).webViewOverlay())
                        }
                    }
                    ProvideWebViewOverlays { Box(Modifier.offset(50.dp, 50.dp).size(80.dp).webViewOverlay()) }
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        waitFor("cut-outs applied") { ui { engine.cutouts } != null }
        waitForIdle()
        assertEquals(0, ui { engine.cutouts?.holes?.size })
        ownOverlay = true
        waitFor("its own window's overlay cut out") { ui { engine.cutouts?.holes?.size } == 1 }
        assertNoViolations(factory)
    }

    // --- The client ---

    /**
     * Engines may ask on their own UI thread: decisions are computed there, with no side effect, and handing a URL to
     * the OS happens on main. The scheme is checked again whatever the client decided.
     */
    @Test fun theClientDecidesOffMainAndOpensExternallyOnMain() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(notes)
        val opened = mutableListOf<Pair<String, Boolean>>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri to SwingUtilities.isEventDispatchThread()
            }
        }
        val client = object : WebClient {
            override fun onNavigation(request: NavigationRequest, content: WebContent): NavigationDecision =
                if (request.url.startsWith("file:")) NavigationDecision.OpenExternally else defaultNavigationDecision(request, content)
        }
        setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(200.dp), client = client) }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engineClient = assertNotNull(ui { factory.created.single().client })
        var decisions: List<Any>? = null
        val thread = Thread {
            decisions = listOf(
                engineClient.onNavigation(NavigationRequest("mailto:a@b.test", isMainFrame = true, isUserGesture = true)),
                engineClient.onNavigation(NavigationRequest("https://elsewhere.test/", isMainFrame = true, isUserGesture = true)),
                engineClient.onNavigation(NavigationRequest("javascript:alert(1)", isMainFrame = true, isUserGesture = true)),
                engineClient.onNavigation(NavigationRequest("file:///etc/passwd", isMainFrame = true, isUserGesture = true)),
                engineClient.onNewWindow(NewWindowRequest("https://elsewhere.test/popup", isUserGesture = true)),
                engineClient.onNavigation(NavigationRequest(notes.origin.url + "page", isMainFrame = true, isUserGesture = false)),
            )
        }
        thread.start()
        thread.join()
        assertEquals(
            listOf(
                NavigationDecision.OpenExternally,
                NavigationDecision.OpenExternally,
                NavigationDecision.Block,
                NavigationDecision.OpenExternally,
                NewWindowDecision.Block,
                NavigationDecision.Allow,
            ),
            decisions,
        )
        waitFor("opened") { ui { opened.size } == 2 }
        waitForIdle()
        // On main, and file: never reaches the OS.
        assertEquals(listOf("mailto:a@b.test" to true, "https://elsewhere.test/" to true), ui { opened.toList() })
        assertNoViolations(factory)
    }
}
