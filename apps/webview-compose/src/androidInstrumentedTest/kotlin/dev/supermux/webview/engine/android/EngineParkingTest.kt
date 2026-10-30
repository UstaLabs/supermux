package dev.supermux.webview.engine.android

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.EngineEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A page parked live for the next view of it, and adopted again (keep pages alive), on the phone. */
@RunWith(AndroidJUnit4::class)
class EngineParkingTest {
    /**
     * Parked as the runtime parks a page (hidden, its Surface gone), then adopted by a new Surface: the replay tells
     * the new subscriber the page's URL and title, and its first paint comes once the WebView has drawn the page in its
     * new place. Nothing loads again, and the page keeps what it had.
     */
    @Test fun replayStateAfterParking() {
        var shown by mutableStateOf(true)
        val harness = EngineHarness.open(surface = { engine -> if (shown) engine.Surface(Modifier.fillMaxSize()) })
        try {
            runBlocking {
                val page = TestPages.marked("#00ff00", title = "parked page")
                harness.loadAndPaint(page)
                assertEquals("\"kept\"", harness.main { evaluate("window.marker = 'kept'") })
                // Parked as the runtime parks it: told it is hidden, then its Surface leaves the composition.
                harness.main {
                    setVisible(false)
                    shown = false
                }
                delay(500)
                assertFalse(harness.main { webView.isAttachedToWindow }, "parked out of the window")
                // Adopted: a new Surface, the replay, shown.
                harness.main {
                    shown = true
                    replayState()
                    setVisible(true)
                }
                val commit = harness.await("the replayed commit") { it is EngineEvent.UrlChanged } as EngineEvent.UrlChanged
                assertEquals(page.origin.url, commit.url)
                harness.await("the replayed title") { it == EngineEvent.TitleChanged("parked page") }
                harness.await("the first paint in its new place") { it == EngineEvent.FirstPaint }
                assertTrue(harness.main { webView.isAttachedToWindow && webView.visibility == View.VISIBLE })
                assertEquals("\"kept\"", harness.main { evaluate("window.marker") }, "the page was started again")
                harness.expectNone("another document", millis = 1_000) { it is EngineEvent.UrlChanged }
                // On screen again: the page's own green, not a blank or white frame.
                val image = assertNotNull(harness.main { snapshot() })
                val pixels = image.toPixelMap()
                val center = pixels[pixels.width / 2, pixels.height / 2].toArgb()
                val red = center shr 16 and 0xff
                val green = center shr 8 and 0xff
                val blue = center and 0xff
                assertTrue(red < 40 && green > 200 && blue < 40, "the page's green: #${Integer.toHexString(center)}")
            }
        } finally {
            harness.close()
        }
    }

    /**
     * A parked page that floods its bridge: past what the queue holds while no view subscribes, the engine goes the way
     * of a crash (unusable), so the pool discards it rather than hand it to a view. Its WebView stays until the engine
     * is released: the page's renderer lives on, and the flood is heard inside WebView's own message callback.
     */
    @Test fun aParkedPageThatFloodsItsBridgeCrashes() = EngineHarness.run {
        loadAndPaint(TestPages.echo)
        seenOrAwait("the page's \$ready") { it is EngineEvent.PageMessage && it.json.startsWith(WebBridge.READY_PREFIX) }
        // Parked as the runtime parks it: told it is hidden, and nobody subscribes.
        main { setVisible(false) }
        stopCollecting()
        main { evaluate("for (var i = 0; i < 1000; i++) hostBridge.post('tick', i)") }
        delay(1_000)
        assertTrue(main { isUsable }, "within what a parked page may hold")
        main { evaluate("for (var i = 0; i < 100; i++) hostBridge.post('tick', i)") }
        withTimeout(10_000) { while (main { isUsable }) delay(50) }
        assertFalse(main { webView.gone }, "the crash path destroyed the WebView")
    }

    /** A replay with no Surface yet waits for one: the first paint isn't told before the page can be drawn again. */
    @Test fun aReplayedFirstPaintWaitsForASurface() {
        var shown by mutableStateOf(true)
        val harness = EngineHarness.open(surface = { engine -> if (shown) engine.Surface(Modifier.fillMaxSize()) })
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.solid("#0000ff", title = "waiting page"))
                harness.main {
                    setVisible(false)
                    shown = false
                }
                delay(500)
                harness.main {
                    replayState()
                    setVisible(true)
                }
                harness.await("the replayed commit") { it is EngineEvent.UrlChanged }
                harness.expectNone("a first paint with no Surface", millis = 1_000) { it == EngineEvent.FirstPaint }
                assertEquals(View.INVISIBLE, harness.main { webView.visibility })
                harness.main { shown = true }
                harness.await("the first paint once shown") { it == EngineEvent.FirstPaint }
                assertEquals(View.VISIBLE, harness.main { webView.visibility })
            }
        } finally {
            harness.close()
        }
    }
}
