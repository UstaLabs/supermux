package dev.supermux.webview.engine.android

import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngineResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reset and engine state (Task 15), on the phone. */
@RunWith(AndroidJUnit4::class)
class EngineResetTest {
    @Test fun aResetEngineStartsItsNextLeaseWithNoHistoryAndHearsNothingOfTheLastOne() = EngineHarness.run {
        loadAndPaint(TestPages.restless)
        loadAndPaint(TestPages.solid("#ff0000", title = "second"))
        assertTrue(main { reset() })
        assertNull(main { webView.url }, "a fresh WebView: no page")
        assertEquals(0, main { webView.copyBackForwardList().size }, "and no history")
        assertEquals(View.INVISIBLE, main { webView.visibility }, "invisible until the next lease's page paints")
        val mark = loadMarked(TestPages.solid("#00ff00", title = "third"))
        val history = await("the history") { it is EngineEvent.HistoryChanged } as EngineEvent.HistoryChanged
        assertFalse(history.canGoBack, "history begins with the lease's first document")
        await("the first paint") { it == EngineEvent.FirstPaint }
        assertEquals("1", main { evaluate("history.length") }, "the page's own history.back() reaches nothing")
        expectNone("anything of the last lease", millis = 500) {
            (it as? EngineEvent.TitleChanged)?.title.let { title -> title == "second" || title?.startsWith("A ") == true }
        }
        assertTrue(seen.drop(mark).none { it == EngineEvent.UrlChanged("about:blank") }, "the reset's own page is never heard")
    }

    @Test fun anEphemeralEnginesDataIsWipedAndANamedProfileKeepsItsOwn() = EngineHarness.run(EngineConfig(WebProfile.Ephemeral)) {
        val named = main {
            (AndroidWebEngineFactory().create(EngineConfig(WebProfile.Named("isolation"))) as WebEngineResult.Created).engine as AndroidWebEngine
        }
        try {
            main {
                named.configure(setupFor(TestPages.store))
                named.load(TestPages.store)
            }
            val namedCookies = suspend {
                main { ProfileStore.getInstance().getProfile(namedProfile("isolation"))?.cookieManager?.getCookie(TestPages.store.origin.url).orEmpty() }
            }
            withTimeout(15_000) { while ("kept=1" !in namedCookies()) delay(100) }
            loadAndPaint(TestPages.store)
            seenOrAwait("stored") { it == EngineEvent.TitleChanged("stored") }
            assertTrue(main { reset() })
            loadAndPaint(TestPages.storeReader)
            seenOrAwait("what is left") { it == EngineEvent.TitleChanged("read local=null cookie=") }
            assertTrue("kept=1" in namedCookies(), "another profile's data is untouched")
        } finally {
            main { named.release() }
        }
    }

    /**
     * What a page keeps besides cookies and web storage, in an Ephemeral engine (review item 6): Cache Storage and a
     * service worker, on a TestServer origin (127.0.0.1 is a secure context). After a reset, a page of the same origin
     * finds neither. A WebView that can't wipe a profile whole fails the reset instead.
     */
    @Test fun anEphemeralResetWipesCacheStorageAndServiceWorkers() = TestServer().use { server ->
        server.route("/sw.js", TestServer.Response(200, "self.addEventListener('fetch', function () {});", type = "text/javascript"))
        server.page(
            "/store",
            """<!doctype html><html><head><title>storing</title></head><body>store<script>
(async function () {
  try {
    var cache = await caches.open('kept');
    await cache.put('/cached', new Response('kept'));
    await navigator.serviceWorker.register('/sw.js');
    await navigator.serviceWorker.ready;
    document.title = 'stored';
  } catch (e) { document.title = 'failed ' + e; }
})();</script></body></html>""",
        )
        server.page(
            "/read",
            """<!doctype html><html><head><title>reading</title></head><body>read<script>
(async function () {
  var keys = await caches.keys();
  var workers = await navigator.serviceWorker.getRegistrations();
  document.title = 'caches ' + keys.length + ' workers ' + workers.length;
})();</script></body></html>""",
        )
        EngineHarness.run(EngineConfig(WebProfile.Ephemeral)) {
            loadAndPaint(WebContent.Url(server.origin + "/store"))
            seenOrAwait("stored") { it == EngineEvent.TitleChanged("stored") }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
                assertFalse(main { reset() }, "a WebView that can't wipe a profile whole can't reset an Ephemeral engine")
                return@run
            }
            loadAndPaint(WebContent.Url(server.origin + "/read"))
            seenOrAwait("what the same lease finds") { it == EngineEvent.TitleChanged("caches 1 workers 1") }
            assertTrue(main { reset() })
            val mark = loadMarked(WebContent.Url(server.origin + "/read"))
            val found: (EngineEvent) -> Boolean = { (it as? EngineEvent.TitleChanged)?.title?.startsWith("caches ") == true }
            val title = seen.drop(mark).firstOrNull(found) ?: await("what the next lease finds", predicate = found)
            assertEquals(EngineEvent.TitleChanged("caches 0 workers 0"), title, "what the next lease found")
        }
    }

    /**
     * Restoring into a recycled engine builds no WebView (review item 8): its reset built a fresh one already, as the
     * engine was given back, so the restore, which comes as a view scrolls back, only restores. The restore's time on
     * main (configure and restoreState, in one frame) and the reset's go to logcat, for the report.
     */
    @Test fun aRestoreIntoARecycledEngineBuildsNoWebView() = EngineHarness.run {
        val page = TestPages.solid("#ffff00", title = "stateful")
        loadAndPaint(page)
        main { evaluate("history.pushState({}, '', '#two')") }
        await("the pushState commit") { it == EngineEvent.UrlChanged(page.origin.url + "#two") }
        val state = assertNotNull(main { saveState() })
        val resetMillis = main {
            val start = SystemClock.elapsedRealtimeNanos()
            assertTrue(reset())
            (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        }
        val (built, restoreMillis) = main {
            val before = webViewsBuilt
            val start = SystemClock.elapsedRealtimeNanos()
            configure(setupFor(page))
            assertTrue(restoreState(state, page))
            (webViewsBuilt - before) to (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        }
        Log.i(
            TAG,
            "a restore into a recycled engine: $built WebView(s) built in it; configure and restoreState took " +
                "%.1f ms on main; the reset before it %.1f ms".format(restoreMillis, resetMillis),
        )
        assertEquals(0, built, "the restore built a WebView")
        await("the restored commit") { it == EngineEvent.UrlChanged(page.origin.url + "#two") }
        val history = await("the restored history") { it is EngineEvent.HistoryChanged } as EngineEvent.HistoryChanged
        assertTrue(history.canGoBack, "the restored history")
        await("the restored paint") { it == EngineEvent.FirstPaint }
        assertEquals("2", main { evaluate("history.length") }, "the restored history, and nothing before it")
    }

    /**
     * A release during a reset ends it at once, false (review, minor): before, a reset waited up to 5 s for an
     * about:blank that a released engine never committed. Default and Ephemeral (whose reset waits for its wipe).
     */
    @Test fun aReleaseDuringAResetEndsItAtOnce() {
        for (profile in listOf(WebProfile.Default, WebProfile.Ephemeral)) {
            val harness = EngineHarness.open(EngineConfig(profile))
            try {
                runBlocking {
                    harness.loadAndPaint(TestPages.solid("#ffffff"))
                    val took = harness.main {
                        val start = SystemClock.elapsedRealtime()
                        // Runs until the reset first waits, if it does; the release comes then.
                        val resetting = CoroutineScope(Dispatchers.Main).async(start = CoroutineStart.UNDISPATCHED) { reset() }
                        release()
                        resetting.await()
                        SystemClock.elapsedRealtime() - start
                    }
                    assertTrue(took < 1_000, "$profile: the reset ended $took ms after it began")
                }
            } finally {
                harness.close()
            }
        }
    }

    @Test fun stateComesBackForItsOwnContentEvenInAUsedEngine() = EngineHarness.run {
        val page = TestPages.solid("#ffff00", title = "stateful")
        loadAndPaint(page)
        main { evaluate("history.pushState({}, '', '#two')") }
        await("the pushState commit") { it == EngineEvent.UrlChanged(page.origin.url + "#two") }
        val state = assertNotNull(main { saveState() })
        assertFalse(main { restoreState(state, TestPages.solid("#000000")) }, "another content's state")
        assertTrue(main { restoreState(state, page) })
        await("the restored commit") { it == EngineEvent.UrlChanged(page.origin.url + "#two") }
        val history = await("the restored history") { it is EngineEvent.HistoryChanged } as EngineEvent.HistoryChanged
        assertTrue(history.canGoBack, "the restored history, not appended to the old one")
        await("the restored paint") { it == EngineEvent.FirstPaint }
        assertEquals("2", main { evaluate("history.length") })
    }

    /**
     * Between leases nobody collects an engine's events, so what it reports then waits in its queue. A reset must drop
     * the last lease's focus, or the next lease's view would take the window's focus for a page nobody touched.
     */
    @Test fun aResetDropsTheFocusTheLastLeaseLeftQueued() {
        val scenario = ActivityScenario.launch(EngineTestActivity::class.java)
        var engine: AndroidWebEngine? = null
        try {
            runBlocking {
                val launched = CompletableDeferred<EngineTestActivity>()
                scenario.onActivity { launched.complete(it) }
                val activity = launched.await()
                val page = TestPages.solid("#ffffff")
                val made = withContext(Dispatchers.Main) {
                    ActivityRegistry.register(activity)
                    val created = (AndroidWebEngineFactory().create(EngineConfig(WebProfile.Default)) as WebEngineResult.Created).engine as AndroidWebEngine
                    activity.setContent { created.Surface(Modifier.fillMaxSize()) }
                    created.configure(setupFor(page))
                    created.load(page)
                    created
                }
                engine = made
                // Nobody listens: the page paints, and takes the focus, with its events left in the engine's queue.
                withTimeout(15_000) { while (withContext(Dispatchers.Main) { made.webView.visibility } != View.VISIBLE) delay(50) }
                withContext(Dispatchers.Main) { made.requestFocus() }
                assertTrue(withContext(Dispatchers.Main) { made.webView.hasFocus() }, "the painted page has the focus")
                assertTrue(withContext(Dispatchers.Main) { made.reset() })
                val heard = Channel<EngineEvent>(Channel.UNLIMITED)
                val collecting = CoroutineScope(Dispatchers.Main).launch { made.events.collect { heard.send(it) } }
                try {
                    val focus = withTimeoutOrNull(1_000L) {
                        var found: EngineEvent? = null
                        while (found == null) found = heard.receive().takeIf { it is EngineEvent.FocusChanged }
                        found
                    }
                    assertNull(focus, "the last lease's focus reached the next lease's subscriber")
                } finally {
                    collecting.cancel()
                }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { engine?.release() }
            scenario.close()
        }
    }
}
