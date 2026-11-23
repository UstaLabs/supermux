package dev.supermux.webview.engine.android

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.gestures.scrollBy
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * A page taller than the screen in a list (an html card in the chat, #162): every drag and flick over it starts on the
 * page. WebView reported its overscroll there a move late, in px one way and scaled the other, with a scroll range for a
 * page that fits its view: a drag up moved the list 38 % of the finger, and a flick never flung it (2026-11-21).
 */
@RunWith(AndroidJUnit4::class)
class TallPageHandoffTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun touch(action: Int, start: Long, time: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(start, time, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    private fun drag(x: Float, fromY: Float, toY: Float, steps: Int, stepMillis: Long, holdMillis: Long) {
        val start = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, start, start, x, fromY)
        for (i in 1..steps) touch(MotionEvent.ACTION_MOVE, start, start + i * stepMillis, x, fromY + (toY - fromY) * i / steps)
        if (holdMillis > 0) Thread.sleep(holdMillis)
        touch(MotionEvent.ACTION_UP, start, start + (steps + 1) * stepMillis + holdMillis, x, toY)
    }

    private class Rig(val harness: EngineHarness, val list: LazyListState, val x: Float, val screen: Float, val density: Float)

    /** A list of a 120 dp spacer, a 4,000 dp page and 3,000 dp more; the list scrolled [into] px so the page fills the screen. */
    private fun withTallPage(into: Int, block: suspend Rig.() -> Unit) {
        val list = LazyListState()
        val harness = EngineHarness.open(surface = { engine ->
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                item { Box(Modifier.fillMaxWidth().height(120.dp)) }
                item { engine.Surface(Modifier.fillMaxWidth().height(PAGE_DP.dp)) }
                item { Box(Modifier.fillMaxWidth().height(3_000.dp)) }
            }
        })
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.solid("#00ff00"))
                withContext(Dispatchers.Main) { list.scrollBy(into.toFloat()) }
                delay(500)
                val (x, screen) = harness.main {
                    val at = IntArray(2)
                    webView.getLocationOnScreen(at)
                    (at[0] + webView.width / 2f) to harness.activity.window.decorView.height.toFloat()
                }
                Rig(harness, list, x, screen, harness.activity.resources.displayMetrics.density).block()
            }
        } finally {
            harness.close()
        }
    }

    private suspend fun Rig.scrolled(): Float = withContext(Dispatchers.Main) {
        val above = listOf(0f, 120f, 120f + PAGE_DP).getOrElse(list.firstVisibleItemIndex) { 120f + PAGE_DP } * density
        above + list.firstVisibleItemScrollOffset
    }

    @Test fun aDragUpOverAPageTallerThanTheScreenMovesTheListWithTheFinger() = withTallPage(into = 1_500) {
        val before = scrolled()
        val dragged = screen * 0.5f
        drag(x, screen * 0.75f, screen * 0.25f, steps = 30, stepMillis = 16, holdMillis = 200)
        delay(300)
        val went = scrolled() - before
        Log.i(TAG, "drag up $dragged px over a tall page: the list went $went px")
        assertTrue(went in dragged * 0.9f..dragged * 1.05f, "the list went $went px under a drag of $dragged px")
    }

    @Test fun aDragDownOverAPageTallerThanTheScreenMovesTheListWithTheFinger() = withTallPage(into = 3_000) {
        val before = scrolled()
        val dragged = screen * 0.5f
        drag(x, screen * 0.25f, screen * 0.75f, steps = 30, stepMillis = 16, holdMillis = 200)
        delay(300)
        val went = before - scrolled()
        Log.i(TAG, "drag down $dragged px over a tall page: the list went $went px")
        assertTrue(went in dragged * 0.9f..dragged * 1.05f, "the list went $went px under a drag of $dragged px")
    }

    @Test fun aFlickUpOverAPageTallerThanTheScreenFlingsTheList() = withTallPage(into = 1_500) {
        val before = scrolled()
        val dragged = screen * 0.3f
        drag(x, screen * 0.7f, screen * 0.4f, steps = 6, stepMillis = 8, holdMillis = 0)
        delay(1_500)
        val went = scrolled() - before
        Log.i(TAG, "flick up $dragged px over a tall page: the list went $went px")
        assertTrue(went > dragged * 2f, "the list went $went px for a flick of $dragged px: it wasn't flung")
    }

    @Test fun aFlickDownOverAPageTallerThanTheScreenFlingsTheList() = withTallPage(into = 6_000) {
        val before = scrolled()
        val dragged = screen * 0.3f
        drag(x, screen * 0.4f, screen * 0.7f, steps = 6, stepMillis = 8, holdMillis = 0)
        delay(1_500)
        val went = before - scrolled()
        Log.i(TAG, "flick down $dragged px over a tall page: the list went $went px")
        assertTrue(went > dragged * 2f, "the list went $went px for a flick of $dragged px: it wasn't flung")
    }

    private companion object {
        const val PAGE_DP = 4_000
    }
}
