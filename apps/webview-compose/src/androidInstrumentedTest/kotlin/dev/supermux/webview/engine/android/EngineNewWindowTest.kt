package dev.supermux.webview.engine.android

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.engine.EngineEvent
import kotlinx.coroutines.delay
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Windows the page opens (review items 4 and 5), on the phone. */
@RunWith(AndroidJUnit4::class)
class EngineNewWindowTest {
    /**
     * A page that, when tapped, opens a window and writes [write] into it (window.open with no URL: WebView hands the
     * engine a window that hasn't navigated yet), then titles itself "opened".
     */
    private fun opener(write: String): WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>opener</title>
<script>function openLate() {
  var w = window.open('');
  if (!w) { document.title = 'blocked'; return; }
  w.document.write('$write');
  w.document.close();
  document.title = 'opened';
}</script></head><body onclick="openLate()" style="margin:0;height:100vh;background:#cfe">tap</body></html>""",
    )

    private suspend fun tapTheMiddle(harness: EngineHarness) {
        val at = IntArray(2)
        val (x, y) = harness.main {
            webView.getLocationOnScreen(at)
            (at[0] + webView.width / 2f) to (at[1] + webView.height / 2f)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 50)) {
            val event = MotionEvent.obtain(down, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
    }

    /**
     * A window that navigates only after a reset (a meta refresh a second later): the next lease's client hears nothing
     * of it. Before, the catcher asked whichever client the engine had by then.
     */
    @Test fun aWindowThatNavigatesAfterAResetReachesNoLaterLease() = EngineHarness.run {
        val first = RecordingClient()
        main { client = first }
        loadAndPaint(opener("<meta http-equiv=\"refresh\" content=\"1;url=https://example.com/late\">"))
        tapTheMiddle(this)
        seenOrAwait("the window opened") { it == EngineEvent.TitleChanged("opened") }
        assertTrue(main { reset() })
        val next = RecordingClient()
        main { client = next }
        loadAndPaint(TestPages.solid("#ffffff", title = "the next lease"))
        delay(3_000)
        val heard = main { next.asked.filterIsInstance<NewWindowRequest>() + first.asked.filterIsInstance<NewWindowRequest>() }
        assertEquals(emptyList<NewWindowRequest>(), heard, "a window of the last lease was heard of")
    }

    /** The same window, navigating within its own lease: the client hears of it, once. */
    @Test fun aWindowThatNavigatesWithinItsLeaseReachesItsClient() = EngineHarness.run {
        val client = RecordingClient()
        main { this.client = client }
        loadAndPaint(opener("<meta http-equiv=\"refresh\" content=\"0;url=https://example.com/now\">"))
        tapTheMiddle(this)
        seenOrAwait("the window opened") { it == EngineEvent.TitleChanged("opened") }
        val heard = kotlinx.coroutines.withTimeout(10_000) {
            var requests: List<NewWindowRequest> = emptyList()
            while (requests.isEmpty()) {
                delay(100)
                requests = main { client.asked.filterIsInstance<NewWindowRequest>() }
            }
            requests
        }
        assertEquals(listOf(NewWindowRequest("https://example.com/now", true)), heard)
    }

    /**
     * The renderer dies while a window's catcher is alive: every WebView on it is told, the catcher too, and the app
     * lives (a WebView whose client doesn't handle it kills the app). Before, this test's process crashed.
     */
    @Test fun aRendererDeathWhileAWindowIsCaughtLeavesTheAppAlive() = EngineHarness.run {
        main { client = RecordingClient() }
        loadAndPaint(opener(""))
        tapTheMiddle(this)
        seenOrAwait("the window opened") { it == EngineEvent.TitleChanged("opened") }
        assertTrue(main { WebViewCompat.getWebViewRenderProcess(webView)!!.terminate() })
        await("the crash") { it == EngineEvent.Crashed }
        delay(1_000)
        assertFalse(main { isUsable }, "the engine is unusable for good, and the app is still here to say so")
    }
}
