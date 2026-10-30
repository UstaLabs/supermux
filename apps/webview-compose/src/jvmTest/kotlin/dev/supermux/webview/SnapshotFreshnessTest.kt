package dev.supermux.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.SnapshotKey
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How long these tests' hidden views keep their engines: views here go cold by being hidden for 2.5 s, as they did
 * when every hidden embed gave its engine back after a 2 s grace.
 */
private const val HIDDEN_RELEASE_MILLIS = 2_000L

/** A page that hasn't changed since its last snapshot isn't snapshotted again on its way out (Task 5). */
@OptIn(ExperimentalTestApi::class)
class SnapshotFreshnessTest {
    private val url = WebContent.Url("https://example.test/")

    private fun ComposeUiTest.waitFor(what: String, condition: () -> Boolean) =
        waitUntil(conditionDescription = what, timeoutMillis = TIMEOUT, condition = condition)

    private fun ComposeUiTest.snapshots(engine: FakeWebEngine): Int = runOnUiThread { engine.calls.count { it == "snapshot" } }

    /**
     * [state]'s view in a clipping box, as in a list. Not [shown], it is scrolled out of the box: hidden, but still laid
     * out, so the way out can snapshot it.
     */
    @Composable
    private fun ClippedView(
        runtime: WebViewRuntime,
        state: WebViewState,
        shown: Boolean,
        modifier: Modifier = Modifier.size(100.dp),
        settings: WebSettings = WebSettings.Default,
    ) {
        ProvideWebViewRuntime(runtime) {
            Box(Modifier.size(200.dp).clipToBounds()) {
                WebView(
                    state,
                    modifier.offset(y = if (shown) 0.dp else 300.dp),
                    settings = settings,
                    activation = Activation.WhenVisible(settleMillis = 0),
                )
            }
        }
    }

    /** Waits for the view to go live and for its settle snapshot: its engine, and that snapshot's key. */
    private fun ComposeUiTest.settled(factory: FakeWebEngineFactory, state: WebViewState): Pair<FakeWebEngine, SnapshotKey> {
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = runOnUiThread { factory.created.single() }
        mainClock.advanceTimeBy(600)
        waitFor("the settle snapshot") { snapshots(engine) == 1 && runOnUiThread { state.snapshotKey } != null }
        return engine to assertNotNull(runOnUiThread { state.snapshotKey })
    }

    /** Hides the view with [hide], and waits until it has gone cold. */
    private fun ComposeUiTest.goCold(state: WebViewState, hide: () -> Unit) {
        runOnUiThread(hide)
        mainClock.advanceTimeBy(2_500)
        waitFor("cold") { state.phase == WebViewPhase.Cold }
    }

    @Test fun aPageLaidOutAtAnotherSizeSinceItsSnapshotIsSnapshottedAgainOnItsWayOut() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = HIDDEN_RELEASE_MILLIS)
        val state = WebViewState(url)
        var width by mutableStateOf(100.dp)
        var shown by mutableStateOf(true)
        setContent { ClippedView(runtime, state, shown, Modifier.size(width, 100.dp)) }
        val (engine, before) = settled(factory, state)
        runOnUiThread { width = 150.dp }
        waitForIdle()
        goCold(state) { shown = false }
        assertEquals(2, snapshots(engine))
        val after = assertNotNull(runOnUiThread { state.snapshotKey })
        assertEquals(before.width * 3 / 2, after.width, "the snapshot shows the page at its new width")
    }

    @Test fun aPageConfiguredAgainSinceItsSnapshotIsSnapshottedAgainOnItsWayOut() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = HIDDEN_RELEASE_MILLIS)
        val state = WebViewState(url)
        var dark by mutableStateOf(false)
        var shown by mutableStateOf(true)
        setContent {
            ClippedView(runtime, state, shown, settings = WebSettings(darkMode = if (dark) DarkMode.Dark else DarkMode.Light))
        }
        val (engine, before) = settled(factory, state)
        assertFalse(before.dark)
        runOnUiThread { dark = true }
        waitFor("configured again") { runOnUiThread { engine.calls.count { it == "configure" } } == 2 }
        goCold(state) { shown = false }
        assertEquals(2, snapshots(engine))
        assertTrue(assertNotNull(runOnUiThread { state.snapshotKey }).dark, "the snapshot shows the page in its new theme")
    }

    @Test fun aPageDrawnAtAnotherDensitySinceItsSnapshotIsSnapshottedAgainOnItsWayOut() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = HIDDEN_RELEASE_MILLIS)
        val state = WebViewState(url)
        var density by mutableStateOf(1f)
        var shown by mutableStateOf(true)
        // 100 px at any density: only the density changes.
        val fixedPixels = Modifier.layout { measurable, _ ->
            val placeable = measurable.measure(Constraints.fixed(100, 100))
            layout(100, 100) { placeable.place(0, 0) }
        }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) { ClippedView(runtime, state, shown, fixedPixels) }
        }
        val (engine, before) = settled(factory, state)
        assertEquals(1f, before.density)
        runOnUiThread { density = 2f }
        waitForIdle()
        goCold(state) { shown = false }
        assertEquals(2, snapshots(engine))
        assertEquals(2f, assertNotNull(runOnUiThread { state.snapshotKey }).density)
    }

    /** A page that committed since its snapshot (a hash, pushState, a link) may show other content now. */
    @Test fun aPageThatCommittedSinceItsSnapshotIsSnapshottedAgainOnItsWayOut() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = HIDDEN_RELEASE_MILLIS)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent { ClippedView(runtime, state, shown) }
        val (engine, _) = settled(factory, state)
        runOnUiThread { engine.emit(EngineEvent.UrlChanged("https://example.test/#step-2")) }
        waitForIdle()
        goCold(state) { shown = false }
        assertEquals(2, snapshots(engine))
    }

    /** A page that reported a new size since its snapshot has laid itself out again. */
    @Test fun aPageThatReportedANewSizeSinceItsSnapshotIsSnapshottedAgainOnItsWayOut() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = HIDDEN_RELEASE_MILLIS)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent { ClippedView(runtime, state, shown) }
        val (engine, _) = settled(factory, state)
        runOnUiThread { engine.emit(EngineEvent.ContentSize(100f, 777f)) }
        waitForIdle()
        goCold(state) { shown = false }
        assertEquals(2, snapshots(engine))
    }

    /** A page with a transparent background shows what is under the view through it: its snapshot is kept losslessly. */
    @Test fun aTransparentPagesSnapshotIsStoredAsPng() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = HIDDEN_RELEASE_MILLIS)
        val state = WebViewState(url)
        setContent { ClippedView(runtime, state, shown = true, settings = WebSettings(transparentBackground = true)) }
        val (_, key) = settled(factory, state)
        val stored = assertNotNull(runOnUiThread { runtime.snapshots[key] })
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), stored.take(4).map { it.toInt() and 0xFF }, "not a PNG")
    }

    @Test fun aSnapshotTheCacheLetGoIsTakenAgainOnTheWayOut() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = HIDDEN_RELEASE_MILLIS)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent { ClippedView(runtime, state, shown) }
        val (engine, before) = settled(factory, state)
        // A full cache, or memory pressure, let it go.
        runOnUiThread { runtime.snapshots.trimTo(0) }
        goCold(state) { shown = false }
        assertEquals(2, snapshots(engine))
        assertNotNull(runOnUiThread { runtime.snapshots[before] }, "the page has its snapshot again")
    }

    @Test fun aPageSnapshottedAfterItSettledIsNotSnapshottedAgainWhenItGoesCold() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = HIDDEN_RELEASE_MILLIS)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, Modifier.size(if (shown) 100.dp else 0.dp), activation = Activation.WhenVisible(settleMillis = 0))
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = runOnUiThread { factory.created.single() }
        mainClock.advanceTimeBy(600)
        waitFor("the settle snapshot") { snapshots(engine) == 1 }
        runOnUiThread { shown = false }
        mainClock.advanceTimeBy(2_500)
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        assertEquals(1, snapshots(engine))
    }

    /**
     * A view that keeps moving after it painted (its list scrolls, or flings) isn't snapshotted while it moves: a
     * snapshot may draw the page on the main thread, longer than a frame. Once it has been still for the settle time,
     * it is (review item 9).
     */
    @Test fun noSnapshotIsTakenWhileTheViewMoves() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var y by mutableStateOf(0.dp)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(400.dp)) {
                    WebView(state, Modifier.size(100.dp).offset(y = y), activation = Activation.WhenVisible(settleMillis = 0))
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = runOnUiThread { factory.created.single() }
        // Two seconds of a list scrolling under it: it moves every frame, well past the settle time after its paint.
        repeat(125) {
            runOnUiThread { y += 1.dp }
            mainClock.advanceTimeBy(16)
        }
        assertEquals(0, snapshots(engine), "snapshotted while it moved")
        mainClock.advanceTimeBy(600)
        waitFor("the snapshot once it is still") { snapshots(engine) == 1 }
    }

    @Test fun aPageNotSnapshottedSinceItPaintedIsSnapshottedOnItsWayOut() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = HIDDEN_RELEASE_MILLIS)
        val state = WebViewState(url)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, Modifier.size(if (shown) 100.dp else 0.dp), activation = Activation.WhenVisible(settleMillis = 0))
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val engine = runOnUiThread { factory.created.single() }
        // Hidden before its settle snapshot: that one finds a 0×0 surface and gets nothing, so the way out tries again.
        runOnUiThread { shown = false }
        mainClock.advanceTimeBy(2_500)
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        assertEquals(2, snapshots(engine))
    }
}
