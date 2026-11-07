package dev.supermux.webview.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebSettings
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.pageSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class FakeWebEngineSurfaceTest {
    private val demo = WebContent.Html("<p/>", WebOrigin("demo"))

    /** A lease's setup that gives [demo]'s documents the shim. */
    private val setup = WebSettings.Default.pageSetup(dark = false, bridgeOrigins = setOf(demo.origin.origin))

    private fun FakeWebEngine.show() {
        configure(setup)
        load(demo)
    }

    @Test fun theSurfaceIsWhiteUntilFirstPaint() = runComposeUiTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(paintsOnLoad = false, color = Color.Red))
        setContent { Box(Modifier.size(100.dp)) { engine.Surface(Modifier.fillMaxSize()) } }
        engine.show()
        assertEquals(Color.White, onNodeWithTag("fake-engine-1").captureToImage().toPixelMap()[50, 50])
        engine.emit(EngineEvent.FirstPaint)
        assertEquals(Color.Red, onNodeWithTag("fake-engine-1").captureToImage().toPixelMap()[50, 50])
        assertEquals(emptyList(), engine.violations)
    }

    @Test fun aComposedEngineSnapshotsItsColor() = runComposeUiTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(color = Color.Red))
        setContent { Box(Modifier.size(100.dp)) { engine.Surface(Modifier.fillMaxSize()) } }
        engine.show()
        waitForIdle()
        val side = with(density) { 100.dp.roundToPx() }
        val snapshot = assertNotNull(engine.snapshot())
        assertEquals(side, snapshot.width)
        assertEquals(side, snapshot.height)
        assertEquals(Color.Red, snapshot.toPixelMap()[side / 2, side / 2])
    }

    @Test fun aSurfaceThatLeftTheCompositionTakesNoSnapshot() = runComposeUiTest {
        val engine = FakeWebEngine(1)
        var shown by mutableStateOf(true)
        setContent { if (shown) Box(Modifier.size(100.dp)) { engine.Surface(Modifier.fillMaxSize()) } }
        engine.show()
        waitForIdle()
        assertNotNull(engine.snapshot())
        shown = false
        waitForIdle()
        assertNull(engine.snapshot())
    }

    @Test fun theContentWidthIsInCssPixels() = runComposeUiTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(contentHeight = { 300f }))
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Box(Modifier.size(100.dp)) { engine.Surface(Modifier.fillMaxSize()) }
            }
        }
        waitForIdle()
        val sizes = mutableListOf<EngineEvent.ContentSize>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { if (it is EngineEvent.ContentSize) sizes += it }
        }
        engine.show()
        job.cancel()
        assertEquals(listOf(EngineEvent.ContentSize(100f, 300f)), sizes)
    }

    /**
     * A page styled with viewport units (`100vh` + 16 px) reports its height with its viewport's, after the load and
     * again whenever its viewport's height changes, as the shim's ResizeObserver does.
     */
    @Test fun aPageSizedByItsViewportReportsWhenTheViewportChanges() = runComposeUiTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(contentHeightInViewport = { _, vh -> vh + 16f }))
        var height by mutableStateOf(100.dp)
        setContent { Box(Modifier.size(100.dp, height)) { engine.Surface(Modifier.fillMaxSize()) } }
        waitForIdle()
        val sizes = mutableListOf<EngineEvent.ContentSize>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { if (it is EngineEvent.ContentSize) sizes += it }
        }
        runOnUiThread { engine.show() }
        assertEquals(listOf(EngineEvent.ContentSize(100f, 116f, 100f)), runOnUiThread { sizes.toList() })
        height = 150.dp
        waitForIdle()
        assertEquals(
            listOf(EngineEvent.ContentSize(100f, 116f, 100f), EngineEvent.ContentSize(100f, 166f, 150f)),
            runOnUiThread { sizes.toList() },
        )
        job.cancel()
        assertEquals(emptyList(), engine.violations)
    }

    /** Without a viewport model, a page's height doesn't follow its viewport: it reports once per load. */
    @Test fun aPageOfFixedHeightReportsOncePerLoad() = runComposeUiTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(contentHeight = { 300f }))
        var height by mutableStateOf(100.dp)
        setContent { Box(Modifier.size(100.dp, height)) { engine.Surface(Modifier.fillMaxSize()) } }
        waitForIdle()
        val sizes = mutableListOf<EngineEvent.ContentSize>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { if (it is EngineEvent.ContentSize) sizes += it }
        }
        runOnUiThread { engine.show() }
        height = 150.dp
        waitForIdle()
        assertEquals(listOf(EngineEvent.ContentSize(100f, 300f)), runOnUiThread { sizes.toList() })
        job.cancel()
    }

    /** Paint holding: a reload keeps the old page on screen until the new one paints, with no snapshot meanwhile. */
    @Test fun aReloadKeepsShowingTheOldPageUntilTheNewOnePaints() = runComposeUiTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(color = Color.Red))
        setContent { Box(Modifier.size(100.dp)) { engine.Surface(Modifier.fillMaxSize()) } }
        runOnUiThread { engine.show() }
        waitForIdle()
        assertNotNull(runOnUiThread { kotlinx.coroutines.runBlocking { engine.snapshot() } })
        runOnUiThread {
            engine.behavior = FakeEngineBehavior(paintsOnLoad = false, color = Color.Red)
            engine.reload()
        }
        assertEquals(Color.Red, onNodeWithTag("fake-engine-1").captureToImage().toPixelMap()[50, 50])
        assertNull(runOnUiThread { kotlinx.coroutines.runBlocking { engine.snapshot() } }, "the new document hasn't painted")
        runOnUiThread { engine.emit(EngineEvent.FirstPaint) }
        assertNotNull(runOnUiThread { kotlinx.coroutines.runBlocking { engine.snapshot() } })
        assertEquals(emptyList(), engine.violations)
    }

    /** Task 15b: each content paints, and snapshots, in its own colour; between documents the old one's stays. */
    @Test fun eachContentPaintsItsOwnColour() = runComposeUiTest {
        val engine = FakeWebEngine(1)
        val next = WebContent.Html("<p>next</p>", demo.origin)
        fun shown() = onNodeWithTag("fake-engine-1").captureToImage().toPixelMap()[50, 50]
        fun snapshotted() = runOnUiThread { kotlinx.coroutines.runBlocking { engine.snapshot() } }
            ?.toPixelMap()?.let { it[it.width / 2, it.height / 2] }
        setContent { Box(Modifier.size(100.dp)) { engine.Surface(Modifier.fillMaxSize()) } }
        runOnUiThread { engine.show() }
        val first = engine.behavior.colorOf(demo)
        val second = engine.behavior.colorOf(next)
        assertNotEquals(first, second)
        assertEquals(first, shown())
        assertEquals(first, snapshotted())
        // The next document hasn't painted yet: the surface holds the previous one's colour.
        runOnUiThread {
            engine.behavior = engine.behavior.copy(paintsOnLoad = false)
            engine.load(next)
        }
        assertEquals(first, shown())
        runOnUiThread { engine.emit(EngineEvent.FirstPaint) }
        assertEquals(second, shown())
        assertEquals(second, snapshotted())
        assertEquals(emptyList(), engine.violations)
    }

    /** The runtime never composes one engine's surface in two places: a native view can have one parent only. */
    @Test fun aSurfaceComposedInTwoPlacesAtOnceIsAViolation() = runComposeUiTest {
        val engine = FakeWebEngine(1)
        var twice by mutableStateOf(false)
        setContent {
            Box(Modifier.size(100.dp)) { engine.Surface(Modifier.fillMaxSize()) }
            if (twice) Box(Modifier.size(100.dp)) { engine.Surface(Modifier.fillMaxSize()) }
        }
        waitForIdle()
        assertEquals(emptyList(), engine.violations)
        twice = true
        waitForIdle()
        assertEquals(listOf("Surface composed twice"), engine.violations)
    }

    @Test fun noSnapshotBeforeFirstPaint() = runComposeUiTest {
        val engine = FakeWebEngine(1, behavior = FakeEngineBehavior(paintsOnLoad = false))
        setContent { Box(Modifier.size(100.dp)) { engine.Surface(Modifier.fillMaxSize()) } }
        engine.show()
        waitForIdle()
        assertNull(engine.snapshot())
        engine.emit(EngineEvent.FirstPaint)
        assertNotNull(engine.snapshot())
    }

    @Test fun aReleasedEngineShowsNothing() = runComposeUiTest {
        val engine = FakeWebEngine(1)
        var shown by mutableStateOf(false)
        setContent { if (shown) Box(Modifier.size(100.dp)) { engine.Surface(Modifier.fillMaxSize()) } }
        engine.release()
        shown = true
        waitForIdle()
        onNodeWithTag("fake-engine-1").assertDoesNotExist()
        assertEquals(setOf("Surface after release"), engine.violations.toSet())
    }

    @Test fun noSnapshotAtZeroSize() = runComposeUiTest {
        val engine = FakeWebEngine(1)
        setContent { Box(Modifier.size(0.dp)) { engine.Surface(Modifier.fillMaxSize()) } }
        engine.show()
        waitForIdle()
        assertEquals(IntSize.Zero, engine.surfaceSize)
        assertNull(engine.snapshot())
    }
}
