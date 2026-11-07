package dev.supermux.webview.engine.android

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngineResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Renderer death and hangs (Task 17), on the phone. */
@RunWith(AndroidJUnit4::class)
class EngineCrashTest {
    @Test fun aKilledRendererMakesTheEngineUnusableForGood() = EngineHarness.run {
        loadAndPaint(TestPages.solid("#ff00ff"))
        assertTrue(main { WebViewCompat.getWebViewRenderProcess(webView)!!.terminate() })
        await("the crash") { it == EngineEvent.Crashed }
        assertFalse(main { isUsable })
        main { load(TestPages.solid("#00ff00")) }
        expectNone("anything after the crash", millis = 1_000) { it is EngineEvent.UrlChanged }
        assertNull(main { snapshot() })
        assertFalse(main { reset() })
    }

    @Test fun aHungPageIsTerminatedAndEveryEngineOnItsRendererSaysSo() = EngineHarness.run {
        val other = main {
            (AndroidWebEngineFactory().create(EngineConfig(WebProfile.Default)) as WebEngineResult.Created).engine as AndroidWebEngine
        }
        val otherEvents = Channel<EngineEvent>(Channel.UNLIMITED)
        val collecting = CoroutineScope(Dispatchers.Main).launch { other.events.collect { otherEvents.send(it) } }
        try {
            main {
                other.configure(setupFor(TestPages.solid("#ffffff")))
                other.load(TestPages.solid("#ffffff"))
            }
            loadAndPaint(TestPages.hang)
            main { evaluate("hang()") }
            delay(1_000)
            // Input the hung renderer can't answer: WebView calls it unresponsive after about 5 s.
            val (x, y) = main {
                val at = IntArray(2)
                webView.getLocationOnScreen(at)
                (at[0] + webView.width / 2f) to (at[1] + webView.height / 2f)
            }
            tap(x, y)
            await("the crash", timeoutMillis = 30_000) { it == EngineEvent.Crashed }
            withTimeout(10_000) {
                while (otherEvents.receive() != EngineEvent.Crashed) Unit
            }
            assertFalse(main { other.isUsable }, "the other engine shared the renderer")
        } finally {
            collecting.cancel()
            main { other.release() }
        }
    }

    private fun tap(x: Float, y: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0))
    }
}
