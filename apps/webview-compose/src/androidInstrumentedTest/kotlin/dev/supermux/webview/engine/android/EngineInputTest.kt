package dev.supermux.webview.engine.android

import android.os.SystemClock
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

    private fun swipe(x: Float, fromY: Float, toY: Float) {
        val start = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, start, start, x, fromY)
        val steps = 20
        for (i in 1..steps) touch(MotionEvent.ACTION_MOVE, start, start + i * 16L, x, fromY + (toY - fromY) * i / steps)
        touch(MotionEvent.ACTION_UP, start, start + (steps + 1) * 16L, x, toY)
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

    @Test fun aDragOverAPageThatCantScrollScrollsTheListAroundIt() {
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
                swipe(x, top + height * 0.8f, top + height * 0.2f)
                delay(500)
                val moved = withContext(Dispatchers.Main) { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 }
                assertTrue(moved, "the list scrolled under the drag")
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
