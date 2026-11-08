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
import androidx.webkit.WebViewCompat
import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.EngineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
     * A parked page that floods its bridge holds nothing of it: what it says while no view subscribes reaches no one,
     * and is dropped as it comes. The engine stays usable, so the view that adopts the page gets it as it is, and hears
     * what the page says from then on, nothing of the flood.
     */
    @Test fun aParkedPageThatFloodsItsBridgeStaysAdoptable() = EngineHarness.run {
        loadAndPaint(TestPages.echo)
        seenOrAwait("the page's \$ready") { it is EngineEvent.PageMessage && it.json.startsWith(WebBridge.READY_PREFIX) }
        // Parked as the runtime parks it: told it is hidden, and nobody subscribes.
        main { setVisible(false) }
        stopCollecting()
        main { evaluate("for (var i = 0; i < 1100; i++) hostBridge.post('tick', i)") }
        delay(1_000)
        assertTrue(main { isUsable }, "a chatty parked page went the way of a crash")
        // The view that adopts it hears what the page says from then on.
        val heard = mutableListOf<EngineEvent>()
        val adopter = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        main { adopter.launch { events.collect { heard += it } } }
        main { evaluate("hostBridge.post('marker', 1)") }
        withTimeout(10_000) {
            while (main { heard.none { it is EngineEvent.PageMessage && "\"marker\"" in it.json } }) delay(50)
        }
        assertTrue(main { heard.none { it is EngineEvent.PageMessage && "\"tick\"" in it.json } }, "the flood reached the adopter")
        adopter.cancel()
    }

    /**
     * Parks the page as the runtime parks it: told it is hidden, its Surface gone by [hide], and nobody subscribed. Then
     * listens as the pool does while the page is parked ([AndroidWebEngine.onUnusable]): each time the engine tells,
     * whether it was usable then goes into the list returned, on main.
     */
    private suspend fun EngineHarness.parkAndListen(hide: () -> Unit): MutableList<Boolean> {
        main {
            setVisible(false)
            hide()
        }
        delay(500)
        stopCollecting()
        val told = mutableListOf<Boolean>()
        main { onUnusable = { told += isUsable } }
        return told
    }

    /**
     * A parked page whose renderer dies tells the pool, through the onUnusable it set as it parked the page, so the
     * pool releases it at once rather than at the next lease: once, unusable by then. Its Activity going after, which
     * retires it too, tells nothing more, nor does the release that follows.
     */
    @Test fun aParkedPageWhoseRendererDiesTellsThePoolOnce() {
        var shown by mutableStateOf(true)
        val harness = EngineHarness.open(surface = { engine -> if (shown) engine.Surface(Modifier.fillMaxSize()) })
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.solid("#ff00ff"))
                val told = harness.parkAndListen { shown = false }
                assertTrue(harness.main { WebViewCompat.getWebViewRenderProcess(webView)!!.terminate() })
                withTimeout(10_000) { while (harness.main { told.isEmpty() }) delay(50) }
                assertEquals(listOf(false), harness.main { told.toList() }, "told once, and unusable by then")
                harness.scenario.recreate()
                assertTrue(harness.main { retired })
                harness.main { release() }
                assertEquals(listOf(false), harness.main { told.toList() }, "told of one death, once")
            }
        } finally {
            harness.close()
        }
    }

    /**
     * A parked page whose Activity goes is retired, and released by the engine itself as an idle engine is: it tells the
     * pool once, unusable by then, so the pool frees its slot at once. The release that follows tells nothing more.
     */
    @Test fun aParkedPageWhoseActivityGoesTellsThePoolOnce() {
        var shown by mutableStateOf(true)
        val harness = EngineHarness.open(surface = { engine -> if (shown) engine.Surface(Modifier.fillMaxSize()) })
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.solid("#00ff00"))
                val told = harness.parkAndListen { shown = false }
                harness.scenario.recreate()
                assertTrue(harness.main { retired })
                assertFalse(harness.main { isUsable })
                assertEquals(listOf(false), harness.main { told.toList() }, "told once, and unusable by then")
                harness.main { release() }
                assertEquals(listOf(false), harness.main { told.toList() }, "told of one death, once")
            }
        } finally {
            harness.close()
        }
    }

    /** A release is no death: the pool gives the page back itself, and isn't told. */
    @Test fun aReleaseTellsThePoolNothing() {
        var shown by mutableStateOf(true)
        val harness = EngineHarness.open(surface = { engine -> if (shown) engine.Surface(Modifier.fillMaxSize()) })
        try {
            runBlocking {
                harness.loadAndPaint(TestPages.solid("#0000ff"))
                val told = harness.parkAndListen { shown = false }
                harness.main { release() }
                delay(500)
                assertEquals(emptyList<Boolean>(), harness.main { told.toList() })
            }
        } finally {
            harness.close()
        }
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
