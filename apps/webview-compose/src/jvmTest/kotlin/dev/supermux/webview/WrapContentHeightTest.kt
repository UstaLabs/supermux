package dev.supermux.webview

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WebSizing.WrapContentHeight] in a list that is being read: a page's new height moves whatever the list lays out after
 * its view, so the view takes it where it moves nothing anyone is reading (the user's report, 2026-11-05: "as some of
 * the webviews load, they change the size of the container"). The chat demo's views were laid out at their guess
 * (`initial`) and, a second or two after coming on screen, sprang to their page's height: in the reversed chat, a
 * preview under the reader's eyes that shrank by 81 dp moved what they read by 81 dp, over 12 frames.
 */
@OptIn(ExperimentalTestApi::class)
class WrapContentHeightTest {
    private val page = WebContent.Html("<p>a preview</p>", WebOrigin("preview"))

    private fun ComposeUiTest.waitFor(what: String, condition: () -> Boolean) =
        waitUntil(conditionDescription = what, timeoutMillis = TIMEOUT, condition = condition)

    private fun ComposeUiTest.heightOf(tag: String): Int = onNodeWithTag(tag).fetchSemanticsNode().size.height

    private fun ComposeUiTest.topOf(tag: String): Float = onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    private fun ComposeUiTest.px(dp: Int): Int = with(density) { dp.dp.roundToPx() }

    /** The page reports [heightCss] now, as a ResizeObserver would. */
    private fun ComposeUiTest.report(engine: FakeWebEngine, heightCss: Float) =
        runOnUiThread { engine.emit(EngineEvent.ContentSize(200f, heightCss)) }

    /**
     * A chat as the demo lays it out: reversed (the newest row at the bottom, the list anchored there), the preview the
     * newest row and a row the user reads above it.
     */
    @Test fun aPageSmallerThanItsGuessMovesNothingOnScreenAndShrinksTheViewOnceItHasLeft() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 150f }))
        val runtime = testRuntime(factory)
        val list = LazyListState()
        var state: WebViewState? = null
        setContent {
            ProvideWebViewRuntime(runtime) {
                LazyColumn(Modifier.size(200.dp, 600.dp), state = list, reverseLayout = true) {
                    item(key = "preview") {
                        WebView(
                            rememberWebViewState(page).also { state = it },
                            Modifier.testTag("preview"),
                            sizing = WebSizing.WrapContentHeight(max = 1_000.dp, initial = 300.dp),
                        )
                    }
                    item(key = "read") { Box(Modifier.fillMaxWidth().height(100.dp).testTag("read")) }
                    items(20) { Box(Modifier.fillMaxWidth().height(100.dp)) }
                }
            }
        }
        waitForIdle()
        val readAt = topOf("read")
        waitFor("the page's height reported") { state?.contentSize != null }
        waitFor("live") { state?.phase == WebViewPhase.Live }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(150.dp, state?.contentSize?.height)
        assertEquals(px(300), heightOf("preview"), "on screen, the view keeps its height")
        assertEquals(readAt, topOf("read"), "the row read above it didn't move")
        // Read on, up the chat, until the preview has scrolled away (its view saves the page as it goes); then back down.
        runOnUiThread { runtime.scope.launch { list.scrollToItem(12) } }
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        runOnUiThread { runtime.scope.launch { list.scrollToItem(0) } }
        waitForIdle()
        assertEquals(px(150), heightOf("preview"), "back on screen, at its page's height from its first frame")
    }

    /**
     * The demo's narrow run (2026-11-05): a reversed list keeps its first visible row in place, and a row whose web view
     * has scrolled out of the list while its header still shows is that row. Shrinking it then moved every row above
     * it, 7 to 11 dp each time. The view keeps its height while its row is placed.
     */
    @Test fun aRowStillOnScreenKeepsItsHeightWhileItsWebViewIsScrolledOut() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 150f }))
        val runtime = testRuntime(factory)
        val list = LazyListState()
        setContent {
            ProvideWebViewRuntime(runtime) {
                LazyColumn(Modifier.size(200.dp, 600.dp), state = list, reverseLayout = true) {
                    item(key = "row") {
                        Column {
                            Box(Modifier.fillMaxWidth().height(100.dp).testTag("header"))
                            WebView(
                                rememberWebViewState(page),
                                Modifier.testTag("preview"),
                                sizing = WebSizing.WrapContentHeight(max = 1_000.dp, initial = 300.dp),
                            )
                        }
                    }
                    items(20) { Box(Modifier.fillMaxWidth().height(100.dp)) }
                }
            }
        }
        waitFor("live") { runOnUiThread { factory.created.isNotEmpty() } }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(px(300), heightOf("preview"))
        // Up the chat until the web view is below the list, its header the lowest thing showing.
        runOnUiThread { list.dispatchRawDelta(px(350).toFloat()) }
        waitForIdle()
        val headerAt = topOf("header")
        assertEquals(0, list.firstVisibleItemIndex, "the row is the list's first visible row")
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(px(300), heightOf("preview"), "its row placed, the view keeps its height")
        assertEquals(headerAt, topOf("header"), "nothing moved")
    }

    /** In a plain scrolling Column, whose views stay placed however far off screen: taken once it is far. */
    @Test fun aHeldHeightIsTakenOnceAPlainColumnHasScrolledTheViewFarAway() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 150f }))
        val runtime = testRuntime(factory)
        val scroll = ScrollState(0)
        val state = WebViewState(page)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.size(200.dp, 400.dp).verticalScroll(scroll)) {
                    WebView(state, Modifier.testTag("preview"), sizing = WebSizing.WrapContentHeight(max = 1_000.dp, initial = 300.dp))
                    Box(Modifier.width(200.dp).height(20_000.dp))
                }
            }
        }
        waitFor("live and sized") { state.phase == WebViewPhase.Live && state.contentSize != null }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(px(300), heightOf("preview"))
        runOnUiThread { scroll.dispatchRawDelta(px(10_000).toFloat()) }
        waitForIdle()
        assertEquals(px(150), heightOf("preview"), "far off screen, the view took its page's height")
        runOnUiThread { scroll.dispatchRawDelta(-scroll.value.toFloat()) }
        waitForIdle()
        assertEquals(px(150), heightOf("preview"))
    }

    @Test fun aPageTallerThanItsGuessGrowsTheViewOnScreen() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 450f }))
        val runtime = testRuntime(factory)
        val state = WebViewState(page)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.width(200.dp)) {
                    WebView(state, Modifier.testTag("preview"), sizing = WebSizing.WrapContentHeight(max = 1_000.dp, initial = 300.dp))
                }
            }
        }
        waitFor("live and sized") { state.phase == WebViewPhase.Live && state.contentSize != null }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(px(450), heightOf("preview"), "its content is never cut")
    }

    /** The user acts on the page (a section closed, a form sent): it shrinks in view, as they act. */
    @Test fun aPageTheUserIsUsingShrinksOnScreen() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 300f }))
        val runtime = testRuntime(factory)
        val state = WebViewState(page)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.width(200.dp)) {
                    WebView(state, Modifier.testTag("preview"), sizing = WebSizing.WrapContentHeight(max = 1_000.dp, initial = 300.dp))
                }
            }
        }
        waitFor("live and sized") { state.phase == WebViewPhase.Live && state.contentSize != null }
        val engine = runOnUiThread { factory.created.single() }
        onNodeWithTag("preview").performClick()
        report(engine, 120f)
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(px(120), heightOf("preview"))
    }

    /** Under its cover (no page painted yet) there's no page to watch grow: the rows after it move once, not for 200 ms. */
    @Test fun aNewHeightUnderTheCoverIsTakenAtOnce() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(paintsOnLoad = false, contentHeight = { 300f }))
        val runtime = testRuntime(factory)
        val state = WebViewState(page)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.width(200.dp)) {
                    WebView(state, Modifier.testTag("preview"), sizing = WebSizing.WrapContentHeight(max = 1_000.dp, initial = 300.dp))
                }
            }
        }
        waitFor("loaded and sized") { state.phase == WebViewPhase.Activating && state.contentSize != null }
        val engine = runOnUiThread { factory.created.single() }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        mainClock.autoAdvance = false
        report(engine, 500f)
        val heights = List(6) {
            mainClock.advanceTimeByFrame()
            heightOf("preview")
        }
        mainClock.autoAdvance = true
        assertTrue(heights.all { it == px(300) || it == px(500) }, "no frame on the way: $heights")
        assertEquals(px(500), heights.last())
    }

    /** A page in view that grows by itself, as one does when it streams, is followed with the spring. */
    @Test fun aPaintedPageThatGrowsInViewIsAnimated() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 300f }))
        val runtime = testRuntime(factory)
        val state = WebViewState(page)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.width(200.dp)) {
                    WebView(state, Modifier.testTag("preview"), sizing = WebSizing.WrapContentHeight(max = 1_000.dp, initial = 300.dp))
                }
            }
        }
        waitFor("live and sized") { state.phase == WebViewPhase.Live && state.contentSize != null }
        val engine = runOnUiThread { factory.created.single() }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        mainClock.autoAdvance = false
        report(engine, 500f)
        val heights = List(8) {
            mainClock.advanceTimeByFrame()
            heightOf("preview")
        }
        mainClock.autoAdvance = true
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertTrue(heights.any { it > px(300) && it < px(500) }, "on its way, frame by frame: $heights")
        assertEquals(px(500), heightOf("preview"))
    }

    /** While its list moves, a new height is taken at once: the rows after the view never drift against the scroll. */
    @Test fun aNewHeightWhileTheListMovesIsTakenAtOnce() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 300f }))
        val runtime = testRuntime(factory)
        val scroll = ScrollState(0)
        val state = WebViewState(page)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column(Modifier.size(200.dp, 1_000.dp).verticalScroll(scroll)) {
                    Box(Modifier.width(200.dp).height(100.dp))
                    WebView(
                        state,
                        Modifier.testTag("preview"),
                        // Live while it moves, as a view that went live before the scroll stays live.
                        activation = Activation.Eager,
                        sizing = WebSizing.WrapContentHeight(max = 1_000.dp, initial = 300.dp),
                    )
                    Box(Modifier.width(200.dp).height(3_000.dp))
                }
            }
        }
        waitFor("live and sized") { state.phase == WebViewPhase.Live && state.contentSize != null }
        val engine = runOnUiThread { factory.created.single() }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        mainClock.autoAdvance = false
        val heights = mutableListOf<Int>()
        repeat(8) { i ->
            runOnUiThread { scroll.dispatchRawDelta(2f) }
            if (i == 1) report(engine, 500f)
            mainClock.advanceTimeByFrame()
            heights += heightOf("preview")
        }
        mainClock.autoAdvance = true
        assertTrue(heights.all { it == px(300) || it == px(500) }, "no frame on the way: $heights")
        assertEquals(px(500), heights.last())
    }
}
