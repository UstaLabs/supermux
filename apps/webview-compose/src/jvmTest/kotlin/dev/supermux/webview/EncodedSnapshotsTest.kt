package dev.supermux.webview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.ImageCodec
import dev.supermux.webview.runtime.KeptSnapshot
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.SnapshotCodec
import dev.supermux.webview.runtime.SnapshotKey
import dev.supermux.webview.runtime.TestCodec
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Sharp snapshots, Task 2: snapshots stored encoded, and decoded on demand. A view asks for its cover's decode as it is
 * composed, shows the placeholder while it runs, and its cover from the frame after; a new snapshot takes the old one's
 * place only once it is stored.
 */
@OptIn(ExperimentalTestApi::class)
class EncodedSnapshotsTest {
    private val url = WebContent.Url("https://example.test/")

    /** A fixed theme: Auto follows the machine's appearance, which flips to dark in the evening on macOS. */
    private val light = WebSettings(darkMode = DarkMode.Light)

    private fun <T> ComposeUiTest.ui(block: () -> T): T = runOnUiThread(block)

    private fun ComposeUiTest.waitFor(what: String, condition: () -> Boolean) =
        waitUntil(conditionDescription = what, timeoutMillis = TIMEOUT, condition = condition)

    private fun ComposeUiTest.centerColor(tag: String): Color {
        val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    /** [actual] is [expected] as a JPEG gives it back: within a few steps a channel. */
    private fun assertAbout(expected: Color, actual: Color, what: String) {
        val close = abs(expected.red - actual.red) <= 0.025f && abs(expected.green - actual.green) <= 0.025f &&
            abs(expected.blue - actual.blue) <= 0.025f
        assertTrue(close, "$what: $actual, not about $expected")
    }

    /**
     * A view composed off screen asks for its cover's decode then, not once it is seen: shown later, its cover is there
     * in the very first frame.
     */
    @Test fun aViewDecodesItsCoverAsItIsComposedBeforeItIsSeen() = runComposeUiTest {
        val codec = TestCodec()
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(color = Color.Red))
        val runtime = testRuntime(factory, codec = codec)
        val state = WebViewState(url)
        var composed by mutableStateOf(true)
        var shown by mutableStateOf(true)
        var activation by mutableStateOf<Activation>(Activation.WhenVisible(settleMillis = 0))
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(100.dp).clipToBounds()) {
                    if (composed) {
                        WebView(
                            state,
                            Modifier.size(100.dp).offset(y = if (shown) 0.dp else 200.dp).testTag("view"),
                            settings = light,
                            activation = activation,
                            placeholder = { Box(Modifier.fillMaxSize().background(Color.Green)) },
                        )
                    }
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("a snapshot") { ui { state.snapshotKey } != null }
        val key = assertNotNull(ui { state.snapshotKey })
        // The view goes; only the stored snapshot is left.
        ui {
            composed = false
            activation = Activation.OnInteraction
            shown = false
        }
        waitForIdle()
        ui {
            runtime.snapshots.trimDecodedTo(0)
            codec.decodeGate = CompletableDeferred()
        }
        // A view of the page is composed below the fold: it isn't seen, and it decodes its cover already.
        ui { composed = true }
        waitForIdle()
        assertEquals(1, ui { codec.decodes }, "the decode starts as the view is composed")
        ui { codec.decodeGate?.complete(Unit) }
        waitFor("decoded") { ui { runtime.snapshots.isDecoded(key) } }
        mainClock.autoAdvance = false
        ui { shown = true }
        mainClock.advanceTimeByFrame()
        assertAbout(Color.Red, centerColor("view"), "the cover, in the first frame the view is seen")
        mainClock.autoAdvance = true
        assertEquals(1, ui { codec.decodes })
    }

    /** A cover that isn't decoded yet: the placeholder shows while it decodes, and the cover from the frame after. */
    @Test fun aCoverShowsInTheFirstFrameAfterItsDecodeFinishes() = runComposeUiTest {
        val codec = TestCodec()
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(color = Color.Red))
        val runtime = testRuntime(factory, hiddenReleaseMillis = 50, codec = codec)
        val state = WebViewState(url)
        var activation by mutableStateOf<Activation>(Activation.WhenVisible(settleMillis = 0))
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(100.dp).clipToBounds()) {
                    WebView(
                        state,
                        Modifier.size(100.dp).offset(y = if (shown) 0.dp else 200.dp).testTag("view"),
                        settings = light,
                        activation = activation,
                        placeholder = { Box(Modifier.fillMaxSize().background(Color.Green)) },
                    )
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        waitFor("a snapshot") { ui { state.snapshotKey } != null }
        val key = assertNotNull(ui { state.snapshotKey })
        shown = false
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        ui {
            runtime.snapshots.trimDecodedTo(0)
            codec.decodeGate = CompletableDeferred()
        }
        // Back on screen as a view that waits to be tapped: cold, its cover decoding.
        activation = Activation.OnInteraction
        shown = true
        waitForIdle()
        assertEquals(Color.Green, centerColor("view"), "the placeholder, while the cover decodes")
        assertEquals(1, ui { codec.decodes })
        mainClock.autoAdvance = false
        ui { codec.decodeGate?.complete(Unit) }
        waitFor("decoded") { ui { runtime.snapshots.isDecoded(key) } }
        mainClock.advanceTimeByFrame()
        assertAbout(Color.Red, centerColor("view"), "the cover, from the first frame after its decode")
        mainClock.autoAdvance = true
    }

    /**
     * A page snapshotted again on its way out: the new snapshot is encoded while the old one stays, the view's and the
     * cache's, and takes its place once stored.
     */
    @Test fun aNewSnapshotTakesTheOldOnesPlaceOnlyOnceItIsStored() = runComposeUiTest {
        val codec = TestCodec()
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = 2_000, codec = codec)
        val state = WebViewState(url)
        var width by mutableStateOf(100.dp)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(200.dp).clipToBounds()) {
                    WebView(
                        state,
                        Modifier.size(width, 100.dp).offset(y = if (shown) 0.dp else 300.dp),
                        settings = light,
                        activation = Activation.WhenVisible(settleMillis = 0),
                    )
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        mainClock.advanceTimeBy(600)
        waitFor("the settle snapshot") { ui { state.snapshotKey } != null }
        val before = assertNotNull(ui { state.snapshotKey })
        assertEquals(1, ui { codec.encodes })
        // Laid out wider, the page reflows: it is snapshotted again on its way out.
        ui { width = 150.dp }
        waitForIdle()
        ui { codec.encodeGate = CompletableDeferred() }
        ui { shown = false }
        mainClock.advanceTimeBy(2_500)
        waitFor("the new snapshot encoding") { ui { codec.encodes } == 2 }
        ui {
            assertEquals(before, state.snapshotKey, "the old snapshot, until the new one is stored")
            assertTrue(before in runtime.snapshots)
        }
        ui { codec.encodeGate?.complete(Unit) }
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        val after = assertNotNull(ui { state.snapshotKey })
        assertEquals(before.width * 3 / 2, after.width)
        ui {
            assertTrue(after in runtime.snapshots)
            assertTrue(before !in runtime.snapshots, "the old one goes once the new one is stored")
        }
    }

    /** An engine whose snapshots are six pixels a dp, sharper than the runtime keeps them. */
    private class Sharp(val fake: FakeWebEngine) : WebEngine by fake {
        override suspend fun snapshot(): ImageBitmap? {
            val image = fake.snapshot() ?: return null
            val sharp = ImageBitmap(image.width * 6, image.height * 6)
            Canvas(sharp).drawImageRect(image, dstSize = IntSize(sharp.width, sharp.height), paint = Paint())
            return sharp
        }
    }

    /**
     * A snapshot sharper than the runtime keeps is scaled down where it is encoded, which apps' codec does off the main
     * thread ([SnapshotCodec]), never on it: the codec gets the snapshot as the engine took it, and the page keeps it at
     * three pixels a dp.
     */
    @Test fun aSnapshotIsScaledDownWhereItIsEncodedNotOnTheMainThread() = runComposeUiTest {
        val handed = mutableListOf<IntSize>()
        val codec = object : ImageCodec by TestCodec() {
            override suspend fun keep(image: ImageBitmap, viewWidthDp: Float, opaque: Boolean): KeptSnapshot? {
                handed += IntSize(image.width, image.height)
                return super<ImageCodec>.keep(image, viewWidthDp, opaque)
            }
        }
        val fakes = FakeWebEngineFactory()
        val runtime = testRuntime(
            WebEngineFactory { config -> WebEngineResult.Created(Sharp((fakes.create(config) as WebEngineResult.Created).engine as FakeWebEngine)) },
            codec = codec,
        )
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp), settings = light) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        mainClock.advanceTimeBy(600)
        waitFor("the settle snapshot") { ui { state.snapshotKey } != null }
        val taken = ui { fakes.created.single().surfaceSize }
        assertEquals(listOf(IntSize(taken.width * 6, taken.height * 6)), ui { handed.toList() }, "the codec didn't get the snapshot as taken")
        assertEquals(300, ui { state.snapshotKey?.width }, "not kept at three pixels a dp")
    }

    /**
     * A new snapshot the cache can't store (bigger than its budget) leaves the page's last good one where it was, in the
     * cache and as the page's snapshot: never a key to nothing.
     */
    @Test fun aSnapshotTheCacheCantStoreLeavesTheLastGoodOne() = runComposeUiTest {
        // The first snapshot stores in 1,000 bytes; any later one needs 20,000, more than the whole cache.
        val codec = object : ImageCodec {
            var encodes = 0

            override suspend fun encode(image: ImageBitmap, opaque: Boolean): ByteArray = ByteArray(if (encodes++ == 0) 1_000 else 20_000)

            override suspend fun decode(bytes: ByteArray): ImageBitmap? = null
        }
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory, hiddenReleaseMillis = 2_000, snapshotCacheBytes = 12_000, codec = codec)
        val state = WebViewState(url)
        var width by mutableStateOf(100.dp)
        var shown by mutableStateOf(true)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.size(200.dp).clipToBounds()) {
                    WebView(
                        state,
                        Modifier.size(width, 100.dp).offset(y = if (shown) 0.dp else 300.dp),
                        settings = light,
                        activation = Activation.WhenVisible(settleMillis = 0),
                    )
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        mainClock.advanceTimeBy(600)
        waitFor("the settle snapshot") { ui { state.snapshotKey } != null }
        val before = assertNotNull(ui { state.snapshotKey })
        ui { width = 150.dp }
        waitForIdle()
        ui { shown = false }
        mainClock.advanceTimeBy(2_500)
        waitFor("cold") { state.phase == WebViewPhase.Cold }
        ui {
            assertEquals(before, state.snapshotKey, "the page's snapshot is one the cache didn't store")
            assertTrue(before in runtime.snapshots, "the last good snapshot was dropped")
        }
    }

    /**
     * A LazyColumn of 60 pages and two live engines, its snapshots coded off the UI thread as apps code them, with room
     * for only three decoded: scrolled through and back, every page seen keeps its snapshot, and on the way back shows
     * it as its cover once its decode has finished, never the placeholder.
     */
    @Test fun aListOfSixtyPagesShowsTheCoverOfEveryPageItSawScrolledThroughAndBack() = runComposeUiTest(
        testTimeout = 10.minutes,
    ) {
        val factory = FakeWebEngineFactory()
        // Three 100 × 100 covers decoded at most.
        val runtime = testRuntime(factory, maxLive = 2, decodedSnapshotBytes = 120_000, codec = SnapshotCodec)
        val pages = List(60) { WebContent.Html("<p>demo $it</p>", WebOrigin("demo$it")) }
        val list = LazyListState()
        val states = HashMap<Int, WebViewState>()
        var back by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                LazyColumn(Modifier.size(100.dp), state = list) {
                    items(pages.size, key = { it }) { i ->
                        val state = rememberWebViewState(pages[i])
                        states[i] = state
                        WebView(
                            state,
                            Modifier.size(100.dp).testTag("page-$i"),
                            settings = light,
                            // On the way back the pages stay cold: what they show is their covers.
                            activation = Activation.WhenVisible(settleMillis = if (back) 600_000 else 0),
                            placeholder = { Box(Modifier.fillMaxSize().background(Color.Green)) },
                        )
                    }
                }
            }
        }
        val item = with(density) { 100.dp.toPx() }
        val seen = HashMap<Int, SnapshotKey>()
        for (i in 0 until pages.size) {
            if (i > 0) runOnIdle { list.dispatchRawDelta(item) }
            waitFor("page $i snapshotted") { ui { states[i]?.snapshotKey } != null }
            seen[i] = assertNotNull(ui { states[i]?.snapshotKey })
        }
        ui { back = true }
        for (i in pages.size - 2 downTo 0) {
            runOnIdle { list.dispatchRawDelta(-item) }
            val key = seen.getValue(i)
            waitFor("page $i decoded") { ui { runtime.snapshots.isDecoded(key) } }
            waitForIdle()
            assertAbout(FakeEngineBehavior().colorOf(pages[i]), centerColor("page-$i"), "page $i")
        }
        ui {
            seen.forEach { (i, key) -> assertTrue(key in runtime.snapshots, "page $i's snapshot was lost") }
            assertEquals(pages.size, runtime.snapshots.count)
        }
    }
}
