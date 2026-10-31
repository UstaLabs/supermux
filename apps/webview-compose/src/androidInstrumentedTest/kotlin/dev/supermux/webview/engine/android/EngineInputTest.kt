package dev.supermux.webview.engine.android

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermux.webview.engine.EngineEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Nested scrolling, the keyboard inset, back and focus (Task 18), on the phone. */
@RunWith(AndroidJUnit4::class)
class EngineInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun touch(action: Int, start: Long, time: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(start, time, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    private fun tap(x: Float, y: Float) {
        val start = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, start, start, x, y)
        touch(MotionEvent.ACTION_UP, start, start + 50, x, y)
    }

    /** The WebView's centre x, top y and height on screen. */
    private suspend fun EngineHarness.onScreen(): Triple<Float, Float, Int> = main {
        val at = IntArray(2)
        webView.getLocationOnScreen(at)
        Triple(at[0] + webView.width / 2f, at[1].toFloat(), webView.height)
    }

    /**
     * A drag over a page that can't scroll moves the list with the finger, through nested scrolling: as far as the
     * finger went, and never back. Once the WebView kept the gesture, the page took the list moving it under the finger
     * for the finger moving, and handed that back too: for a steady 24 px a move, the list went 17, 33, -22, 122, -255,
     * 542, -1388 px, until the page left the screen; then, steadied, 64 px a move, as WebView reported it scaled
     * (on the phone, 2026-10-31).
     */
    @Test fun aDragOverAPageThatCantScrollMovesTheListWithTheFinger() {
        val list = LazyListState()
        val harness = EngineHarness.open(surface = { engine ->
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                item { Box(Modifier.fillMaxWidth().height(120.dp)) }
                item { engine.Surface(Modifier.fillMaxWidth().height(300.dp)) }
                item { Box(Modifier.fillMaxWidth().height(3_000.dp)) }
            }
        })
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.solid("#00ff00"))
                val (x, top, height) = harness.onScreen()
                val density = harness.activity.resources.displayMetrics.density
                // The items above the first visible one (120 dp, then the page's 300 dp), and the offset into it.
                suspend fun scrolled(): Float = withContext(Dispatchers.Main) {
                    val above = listOf(0f, 120f, 420f).getOrElse(list.firstVisibleItemIndex) { 420f } * density
                    above + list.firstVisibleItemScrollOffset
                }
                val from = top + height * 0.8f
                val dragged = height * 0.6f
                val start = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, start, start, x, from)
                val trail = mutableListOf(scrolled())
                for (i in 1..20) {
                    touch(MotionEvent.ACTION_MOVE, start, start + i * 16L, x, from - dragged * i / 20)
                    trail += scrolled()
                }
                delay(200)
                val held = scrolled()
                touch(MotionEvent.ACTION_UP, start, start + 21 * 16L, x, from - dragged)
                val back = trail.zipWithNext { before, after -> before - after }.max()
                Log.i(TAG, "a drag of $dragged px over a page that can't scroll: the list went $held px, $back px back at most")
                assertTrue(back <= 2f, "the list went back $back px under a steady drag: $trail")
                assertTrue(held in dragged * 0.75f..dragged * 1.05f, "the list went $held px under a drag of $dragged px: $trail")
            }
        } finally {
            harness.close()
        }
    }

    /** A fast flick: [steps] moves 8 ms apart, then the finger lifts. */
    private fun flick(x: Float, fromY: Float, toY: Float, steps: Int = 6) {
        val start = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, start, start, x, fromY)
        for (i in 1..steps) touch(MotionEvent.ACTION_MOVE, start, start + i * 8L, x, fromY + (toY - fromY) * i / steps)
        touch(MotionEvent.ACTION_UP, start, start + (steps + 1) * 8L, x, toY)
    }

    /**
     * A flick inside a page that scrolls itself (an inner scroller, its body fixed) is the page's: the scroller moves,
     * the list around it doesn't (review item 10). On the phone the list took the gesture from the page at its second
     * move, as Compose does over a view that doesn't ask it not to, and flung itself: 6,200 px.
     */
    @Test fun aFlickInsideAPageThatScrollsItselfLeavesTheListAlone() {
        val list = LazyListState()
        val harness = EngineHarness.open(surface = { engine ->
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                item { Box(Modifier.fillMaxWidth().height(120.dp)) }
                item { engine.Surface(Modifier.fillMaxWidth().height(400.dp)) }
                item { Box(Modifier.fillMaxWidth().height(3_000.dp)) }
            }
        })
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.innerScroller)
                delay(300)
                val (x, top, height) = harness.onScreen()
                flick(x, top + height * 0.8f, top + height * 0.3f)
                delay(1_000)
                val scrolled = harness.main { evaluate("document.getElementById('s').scrollTop") }!!.toFloat()
                assertTrue(scrolled > 0f, "the flick reached the page's scroller: $scrolled")
                val moved = withContext(Dispatchers.Main) { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 }
                assertTrue(!moved, "the list moved: item ${list.firstVisibleItemIndex}, offset ${list.firstVisibleItemScrollOffset}")
            }
        } finally {
            harness.close()
        }
    }

    /** A flick over a page that can't scroll at all reached the list as a drag: it flings the list. */
    @Test fun aFlickOverAPageThatCantScrollFlingsTheList() {
        val list = LazyListState()
        val harness = EngineHarness.open(surface = { engine ->
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                item { Box(Modifier.fillMaxWidth().height(120.dp)) }
                item { engine.Surface(Modifier.fillMaxWidth().height(400.dp)) }
                item { Box(Modifier.fillMaxWidth().height(3_000.dp)) }
            }
        })
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.solid("#00ff00"))
                delay(300)
                val (x, top, height) = harness.onScreen()
                val dragged = height * 0.5f
                flick(x, top + height * 0.8f, top + height * 0.3f)
                delay(1_000)
                val density = harness.activity.resources.displayMetrics.density
                val went = withContext(Dispatchers.Main) {
                    // The items above the first visible one (120 dp, then the page's 400 dp), and the offset into it.
                    val above = listOf(0f, 120f, 520f).getOrElse(list.firstVisibleItemIndex) { 520f } * density
                    above + list.firstVisibleItemScrollOffset
                }
                assertTrue(went > dragged * 1.5f, "the list went no further than the drag, so it wasn't flung: $went px for $dragged")
            }
        } finally {
            harness.close()
        }
    }

    @Test fun aTapGivesThePageFocusAndTheEngineSaysSo() = EngineHarness.run {
        loadAndPaint(TestPages.solid("#ffffff"))
        val (x, top, height) = onScreen()
        tap(x, top + height / 2f)
        await("focus") { it == EngineEvent.FocusChanged(true) }
    }

    @Test fun theKeyboardInsetShrinksThePagesVisualViewport() = EngineHarness.run {
        assumeTrue("WebView M139+", (webViewMajor(Features.version(activity)) ?: 0) >= WEBVIEW_IME_VIEWPORT_MAJOR)
        loadAndPaint(TestPages.solid("#ffffff"))
        val before = main { evaluate("window.visualViewport.height") }!!.toFloat()
        main { webView.imeBottom = webView.height / 2 }
        delay(500)
        val after = main { evaluate("window.visualViewport.height") }!!.toFloat()
        assertTrue(after < before * 0.75f, "the visual viewport shrank: $before → $after")
    }

    @Test fun backGoesBackInAPageTheUserIsIn() = EngineHarness.run {
        val page = TestPages.solid("#ffffff")
        loadAndPaint(page)
        val (x, top, height) = onScreen()
        tap(x, top + height / 2f)
        await("focus") { it == EngineEvent.FocusChanged(true) }
        // Right after the tap: Chromium skips history entries a page adds without the user's activation, for back and
        // canGoBack() alike (its history manipulation intervention).
        main { evaluate("history.pushState({}, '', '#two')") }
        await("the pushState") { it == EngineEvent.UrlChanged(page.origin.url + "#two") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("back in the page") { it == EngineEvent.UrlChanged(page.origin.url) }
    }
}
